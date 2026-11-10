package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.CompanionSummonRequest;
import com.tabletophelper.encounter.dto.SummonChoice;
import com.tabletophelper.encounter.dto.SummonCreature;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.state.SummonSpec;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.SummonRules.InitiativeMode;
import com.tabletophelper.rules.combat.SummonSpirits;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings a summoning spell's creatures into an encounter (D182). A choice is checked against the spell before
 * anything is spent ({@link #check}); the casting then leaves a Spell Effect on the caster that owns its creatures
 * ({@link #afterCast}), so they leave when it ends (D177). The creatures are COMPANION participants, saved at once so
 * they have IDs for the turn order and for undo, which removes them ({@link CommandRecorder}).
 *
 * <ul>
 *   <li>Initiative (PHB p.189, p.225; XGE p.167; TCE p.109): a Tasha's spirit follows its caster, on the caster's count;
 *       a group rolls one d20 with the first creature's DEX modifier, every creature still taking its own turn, and
 *       Animate Dead's and Tiny Servant's creatures roll one per kind of identical creature; anyone else rolls its own.
 *       The dice are the encounter's.</li>
 *   <li>Control: the caster's player acts for the creatures, except Summon Lesser Demons', which are hostile to everyone
 *       and the DM's (XGE p.167), and Giant Insect's, whose actions the DM resolves (PHB p.245). Summon Greater Demon's
 *       demon is the caster's until it succeeds on a Charisma save at the end of one of its turns (XGE p.166), so it
 *       carries the caster's spell save DC.</li>
 *   <li>A Tasha's spirit's AC, hit points and actions are worked out for the slot level and the caster's spell numbers
 *       ({@link SpiritTemplates}).</li>
 *   <li>A familiar and a steed are one at a time (PHB p.240): a new one replaces the caster's earlier one, Find
 *       Steed's and Find Greater Steed's replacing each other (XGE p.156).</li>
 *   <li>A spirit's variant may resist or ignore damage its stat block's row doesn't, such as the Fire Elemental Spirit's
 *       immunity to fire (TCE p.111); the creature carries those defences.</li>
 *   <li>Animate Objects counts its objects by size (PHB p.213), and its stat blocks' hit points, which the bestiary
 *       gives only in words, come from {@link SummonRules#statBlockHitPoints}. Danse Macabre's undead add the caster's
 *       spellcasting ability modifier to their attack and damage rolls (XGE p.153), kept in templates of their own.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class Summoner {

    private final MonsterRepository monsterRepository;
    private final EncounterParticipantRepository participantRepository;
    private final EffectApplier effectApplier;
    private final CombatLogWriter combatLog;
    private final MonsterPools monsterPools;
    private final SpiritTemplates spiritTemplates;
    private final Rng rng;
    private final CreatureStats stats;

    /** Creatures of one stat block, as checked. */
    public record Picked(Monster monster, int count) {
    }

    /**
     * A summoning as checked: the creatures it brings, or a Tasha's spirit's variant.
     *
     * @param option the option the creatures fit
     */
    public record Plan(SummonRules.Spell spell, int slotLevel, SummonRules.Option option, List<Picked> picked,
                       SummonSpirits.Spirit spirit, SummonSpirits.Variant variant, Monster spiritMonster,
                       InitiativeMode initiativeMode, String creatureType) {
    }

    /**
     * What a cast will summon: the creatures, or none when they wait for the DM.
     *
     * @param plan     the creatures as checked; null when they wait for the DM
     * @param optionId the option the caster chose for the DM to fill; null when the DM chooses that too
     */
    public record Prepared(SummonRules.Spell spell, Plan plan, String optionId) {

        /**
         * What the cast does, for its log entry and response: the creatures it summons, or that they wait for the DM,
         * in place of the spell data's note for a spell resolved by hand.
         */
        public String castNote() {
            if (plan != null) {
                return "summons " + creatures(plan);
            }
            return spell.kind() == SummonRules.Kind.LESSER_DEMONS
                    ? "its d6 sets how many demons come, and the DM chooses them"
                    : "its creatures wait for the DM to choose them";
        }

        /** Why the DM still has to act on the cast, or null when its creatures are placed. */
        public String manualReason() {
            return plan != null ? null : "The DM chooses the creatures for " + spell.name();
        }
    }

    /** The summoning spell of that name; a 400 for any other. */
    public static SummonRules.Spell spell(String name) {
        return SummonRules.spell(name).orElseThrow(() -> new ValidationException(name + " isn't a summoning spell"));
    }

    /**
     * Whether the DM chooses the spell's creatures in this encounter: always Summon Lesser Demons' (XGE p.167), and
     * Conjure Animals', Conjure Minor Elementals' and Conjure Woodland Beings' while the campaign says so (PHB
     * p.225–226: "The DM has the creatures' statistics").
     */
    public static boolean dmChooses(Encounter encounter, SummonRules.Spell spell) {
        return spell.dmChooses() || spell.tiered()
                && Boolean.TRUE.equals(encounter.getCampaign().getDmChoosesConjuredCreatures());
    }

    /**
     * What the cast will summon, checked before anything is spent (400 for a choice that doesn't fit): the caster's
     * creatures, or, when the DM chooses them or the cast names none, nothing yet, keeping the caster's option for the
     * DM where it fits.
     */
    public Prepared prepare(Encounter encounter, EncounterParticipant caster, SummonRules.Spell spell, int slotLevel,
                            SummonChoice choice) {
        if (choice == null || dmChooses(encounter, spell)) {
            String optionId = choice != null && choice.optionId() != null && spell.kind() != SummonRules.Kind.LESSER_DEMONS
                    ? option(spell, slotLevel, hasFamiliar(encounter, caster), choice.optionId()).id() : null;
            return new Prepared(spell, null, optionId);
        }
        return new Prepared(spell, check(encounter, caster, spell, slotLevel, choice, false), null);
    }

    /**
     * Checks a choice against the spell at this slot level, a 400 naming what doesn't fit: the option, each creature's
     * stat block, challenge rating and type, their number, a spirit's variant, the initiative mode and a familiar's or
     * steed's type.
     *
     * @param byDm whether the DM places them, who may give a familiar a Pact of the Chain form
     */
    public Plan check(Encounter encounter, EncounterParticipant caster, SummonRules.Spell spell, int slotLevel,
                      SummonChoice choice, boolean byDm) {
        if (slotLevel < spell.level()) {
            throw new ValidationException(spell.name() + " is a level " + spell.level() + " spell, so it can't be cast "
                    + "with a level " + slotLevel + " slot");
        }
        SummonChoice given = choice != null ? choice : new SummonChoice(null, null, null, null, null);
        InitiativeMode mode = given.initiativeMode() != null ? given.initiativeMode() : spell.defaultInitiativeMode();
        if (!spell.initiativeModes().contains(mode)) {
            throw new ValidationException(spell.name() + "'s creatures can't take initiative " + mode + "; choose "
                    + spell.initiativeModes());
        }
        String creatureType = spell.typeChoices().isEmpty() ? spell.creatureType() : chosenType(spell, given);

        if (spell.kind() == SummonRules.Kind.SPIRIT) {
            SummonSpirits.Spirit spirit = spiritTemplates.spirit(spell.name())
                    .orElseThrow(() -> new ConflictException(spell.name() + "'s spirit isn't in the data"));
            SummonSpirits.Variant variant = spirit.variant(given.variant())
                    .orElseThrow(() -> new ValidationException("Choose " + spell.name() + "'s spirit: "
                            + String.join(", ", spirit.variantNames())));
            Monster monster = monsterRepository.findByNameIgnoreCaseAndSource(spirit.monster(), spirit.source())
                    .orElseThrow(() -> new ConflictException(spirit.monster() + "'s stat block isn't loaded"));
            SummonRules.Option only = SummonRules.options(spell, slotLevel, false).getFirst();
            return new Plan(spell, slotLevel, only, List.of(new Picked(monster, 1)), spirit, variant, monster, mode,
                    creatureType);
        }

        SummonRules.Option option = option(spell, slotLevel, hasFamiliar(encounter, caster), given.optionId());
        boolean pact = byDm || FeatureEffects.hasFeature(caster.getCharacter(), SummonRules.PACT_OF_THE_CHAIN);
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (SummonCreature creature : given.creaturesOrNone()) {
            counts.merge(creature.monsterId(), creature.countOrOne(), Integer::sum);
        }
        List<Picked> picked = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            Monster monster = monsterRepository.findById(entry.getKey())
                    .orElseThrow(() -> new ValidationException("Creature not found"));
            SummonRules.refusal(spell, option, monster.getName(), monster.getChallengeRating(), monster.getType(),
                    monster.getSubtype(), pact).ifPresent(reason -> {
                        throw new ValidationException(reason);
                    });
            picked.add(new Picked(monster, entry.getValue()));
        }
        // Animate Objects counts a Medium object as two, a Large as four and a Huge as eight (PHB p.213)
        int total = picked.stream()
                .mapToInt(p -> p.count() * SummonRules.weight(spell, p.monster().getSize()))
                .sum();
        SummonRules.countRefusal(spell, option, total).ifPresent(reason -> {
            throw new ValidationException(reason);
        });
        return new Plan(spell, slotLevel, option, picked, null, null, null, mode, creatureType);
    }

    /**
     * After the spell is cast and paid for, in the same command: its Spell Effect on the caster, linked to the
     * concentration the casting started, and the creatures, or a summoning that waits for the DM. Summon Lesser Demons
     * rolls its d6 here (XGE p.167).
     *
     * @param ability     the ability the spell is cast with, whose modifier Danse Macabre's undead add (XGE p.153);
     *                    null for the caster's main one
     * @param attackBonus the caster's spell attack bonus for this cast, which a Tasha's spirit attacks with
     * @param saveDc      the caster's spell save DC for this cast
     */
    public SpellEffectEntry afterCast(Encounter encounter, EncounterParticipant caster, Prepared prepared,
                                      int slotLevel, String ability, int attackBonus, int saveDc, String how) {
        SummonRules.Spell spell = prepared.spell();
        ConcentrationInstance instance = concentrationOn(caster, spell);
        replaceOneAtATime(encounter, caster, spell);
        int multiplier = SummonRules.multiplier(spell, slotLevel);
        Integer duration = instance != null && instance.durationRounds() != null ? instance.durationRounds()
                : spell.durationRounds();

        if (prepared.plan() == null) {
            Integer roll = null;
            String optionId = prepared.optionId();
            String detail = "";
            if (spell.kind() == SummonRules.Kind.LESSER_DEMONS) {
                roll = rng.roll(6);
                SummonRules.Option option = SummonRules.lesserDemons(roll, slotLevel);
                optionId = option.id();
                detail = ": d6(" + roll + "), " + option.label();
            } else if (optionId != null) {
                String id = optionId;
                detail = ": " + SummonRules.options(spell, slotLevel, hasFamiliar(encounter, caster)).stream()
                        .filter(option -> option.id().equals(id)).findFirst().map(SummonRules.Option::label)
                        .orElse(id);
            }
            SpellEffectEntry effect = effectApplier.startSummonEffect(encounter, caster, spell.name(),
                    slotLevel, instance, duration,
                    new SummonSpec(optionId, multiplier, roll, SummonSpec.Status.AWAITING_DM));
            combatLog.write(encounter, CombatActionType.SUMMON, caster.getDisplayName() + "'s " + spell.name()
                    + " waits for the DM to choose its creatures" + detail + how, caster, null);
            return effect;
        }
        SpellEffectEntry effect = effectApplier.startSummonEffect(encounter, caster, spell.name(), slotLevel,
                instance, duration, new SummonSpec(prepared.plan().option().id(), multiplier, null,
                        SummonSpec.Status.PLACED));
        place(encounter, caster, effect, prepared.plan(), ability, attackBonus, saveDc, how, null);
        return effect;
    }

    /**
     * A summoning the DM places with no slot spent (D182), from the companion command or the add-participant form:
     * the request checked against the spell, linked to the caster's concentration when it asks, or when the spell
     * needs it.
     */
    public SpellEffectEntry companion(Encounter encounter, CompanionSummonRequest request) {
        return companion(encounter, request, null);
    }

    /**
     * As {@link #companion(Encounter, CompanionSummonRequest)}, with the DM's name for the creatures from the
     * add-participant form: one creature takes it, several take it numbered ("Hawk 1", "Hawk 2"); blank, their stat
     * block's name.
     */
    public SpellEffectEntry companion(Encounter encounter, CompanionSummonRequest request, String displayName) {
        EncounterParticipant caster = CombatAccess.participant(encounter, request.casterParticipantId());
        SummonRules.Spell spell = spell(request.spellName());
        int slot = request.slotLevel() != null ? request.slotLevel() : spell.level();
        Plan plan = check(encounter, caster, spell, slot, request.choice(), true);
        boolean linked = request.concentrationLinked() != null ? request.concentrationLinked() : spell.concentration();
        return placeCompanion(encounter, caster, plan, linked,
                displayName != null && !displayName.isBlank() ? displayName.trim() : null);
    }

    /**
     * A summoning the DM places with no slot spent, before the fight or during it (D182): the caster's concentration
     * starts first when it is linked, ending any it held.
     */
    public SpellEffectEntry placeCompanion(Encounter encounter, EncounterParticipant caster, Plan plan,
                                           boolean concentrationLinked, String displayName) {
        SummonRules.Spell spell = plan.spell();
        ConcentrationInstance instance = concentrationLinked
                ? effectApplier.startConcentration(encounter, caster, spell.name(), plan.slotLevel(),
                        spell.durationRounds())
                : null;
        replaceOneAtATime(encounter, caster, spell);
        SpellEffectEntry effect = effectApplier.startSummonEffect(encounter, caster, spell.name(), plan.slotLevel(),
                instance, spell.durationRounds(), new SummonSpec(plan.option().id(),
                        SummonRules.multiplier(spell, plan.slotLevel()), null, SummonSpec.Status.PLACED));
        place(encounter, caster, effect, plan, null, numberOr(caster.getSpellAttackBonus(), 0),
                numberOr(caster.getSpellSaveDc(), 10), " (placed by the DM, no slot spent)", displayName);
        return effect;
    }

    /**
     * The DM's creatures for a summoning: those of one that waits, or new ones in place of a placed one's, which
     * disappear.
     */
    public void placeForDm(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect, Plan plan) {
        SummonSpec spec = effect.summon();
        if (!spec.awaitingDm()) {
            for (EncounterParticipant creature : EffectApplier.summonedBy(encounter, effect.id())) {
                effectApplier.dismiss(encounter, creature, "the DM replaces " + effect.spell() + "'s creatures",
                        false);
            }
        }
        SpellEffectEntry placed = effectApplier.updateSummon(caster, effect.id(), spec.placed(plan.option().id()));
        place(encounter, caster, placed, plan, null, numberOr(caster.getSpellAttackBonus(), 0),
                numberOr(caster.getSpellSaveDc(), 10), " (chosen by the DM)", null);
    }

    /** Whether the caster has a familiar in the fight already (PHB p.240, LLK p.57). */
    public static boolean hasFamiliar(Encounter encounter, EncounterParticipant caster) {
        return encounter.getParticipants().stream()
                .filter(p -> !EncounterTurns.dismissed(p) && caster.getId().equals(p.getSummonedByParticipantId()))
                .anyMatch(p -> p.getSummon() != null && p.getSummon().kind() == SummonRules.Kind.FAMILIAR);
    }

    // ── Making the creatures ─────────────────────────────────────

    // displayName: the creatures' name in place of their stat block's, numbered when there are several; ability: the
    // one the spell was cast with, or null for the caster's main one
    private void place(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect, Plan plan,
                       String ability, int attackBonus, int saveDc, String how, String displayName) {
        int total = plan.picked().stream().mapToInt(Picked::count).sum();
        SummonRules.Spell spell = plan.spell();
        // Danse Macabre's undead add the caster's spellcasting ability modifier to attack and damage rolls (XGE p.153)
        Integer castersModifier = spell.addsSpellcastingModifier()
                ? stats.spellcastingModifier(caster, ability != null ? ability : caster.getSpellcastingAbility(),
                        attackBonus)
                : null;
        int sortOrder = encounter.getParticipants().stream()
                .map(EncounterParticipant::getSortOrder).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(-1) + 1;
        UUID controller = spell.controller() == SummonRules.Controller.DM ? null : caster.getControlledByUserId();

        // One roll for the whole group, or for each kind of identical creature (PHB p.189)
        Map<UUID, Integer> groupRolls = new HashMap<>();
        Map<UUID, UUID> groupIds = new HashMap<>();
        UUID wholeGroup = UUID.randomUUID();
        Integer wholeRoll = null;

        List<EncounterParticipant> made = new ArrayList<>();
        for (Picked picked : plan.picked()) {
            Monster monster = picked.monster();
            int dexMod = Ability.modifier(monster.getDexterity() != null ? monster.getDexterity() : 10);
            String base = displayName != null ? displayName
                    : plan.spirit() != null && plan.spirit().hasChoice()
                    ? monster.getName() + " (" + plan.variant().name() + ")"
                    : monster.getName();
            long existing = encounter.getParticipants().stream()
                    .filter(p -> p.getDisplayName() != null && p.getDisplayName().startsWith(base)).count();
            for (int i = 0; i < picked.count(); i++) {
                Integer initiative;
                UUID groupId = null;
                switch (plan.initiativeMode()) {
                    case FOLLOWS_CASTER -> initiative = caster.getInitiative();
                    case GROUP -> {
                        if (spell.groupsByCreature()) {
                            groupId = groupIds.computeIfAbsent(monster.getId(), id -> UUID.randomUUID());
                            initiative = groupRolls.computeIfAbsent(monster.getId(), id -> rng.roll(20) + dexMod);
                        } else {
                            groupId = wholeGroup;
                            if (wholeRoll == null) {
                                wholeRoll = rng.roll(20) + dexMod;
                            }
                            initiative = wholeRoll;
                        }
                    }
                    default -> initiative = rng.roll(20) + dexMod;
                }
                boolean numbered = (displayName != null ? total : picked.count()) > 1 || existing > 0;
                EncounterParticipant creature = EncounterParticipant.builder()
                        .encounter(encounter)
                        .participantType(ParticipantType.COMPANION)
                        .monster(monster)
                        .displayName(numbered ? base + " " + (existing + i + 1) : base)
                        .initiativeModifier(dexMod)
                        .initiative(initiative)
                        .sortOrder(sortOrder++)
                        .hpMax(1)
                        .hpCurrent(1)
                        .armourClass(monster.getArmourClass() != null ? monster.getArmourClass() : 10)
                        .controlledByUserId(controller)
                        .isVisibleToPlayers(!Boolean.FALSE.equals(caster.getIsVisibleToPlayers()))
                        .summonedByParticipantId(caster.getId())
                        .summon(new SummonLink(effect.id(), spell.name(), effect.slotLevel(), spell.kind(),
                                plan.initiativeMode(), groupId, plan.variant() != null ? plan.variant().name() : null,
                                plan.creatureType(), spell.vanishesAtZero(), spell.cannotAttack(),
                                spell.needsCommand(), null,
                                plan.variant() != null ? plan.variant().damageResistances() : List.of(),
                                plan.variant() != null ? plan.variant().damageImmunities() : List.of(),
                                spell.savesToBreakControl() ? saveDc : null, null))
                        .build();
                monsterPools.setUp(creature, monster);
                if (plan.spirit() != null) {
                    int hp = SpiritTemplates.hitPoints(plan.spirit(), plan.variant(), plan.slotLevel());
                    creature.setHpMax(hp);
                    creature.setHpCurrent(hp);
                    creature.setArmourClass(SpiritTemplates.armourClass(plan.spirit(), plan.variant(),
                            plan.slotLevel()));
                    creature.setActionTemplates(spiritTemplates.templates(plan.spirit(), plan.variant(),
                            plan.slotLevel(), attackBonus, saveDc));
                } else {
                    // Animate Objects' stat blocks give their hit points only in words (PHB p.213)
                    int hp = monster.getHitPoints() != null && monster.getHitPoints() > 0 ? monster.getHitPoints()
                            : SummonRules.statBlockHitPoints(monster.getName()).orElse(1);
                    creature.setHpMax(hp);
                    creature.setHpCurrent(hp);
                    if (castersModifier != null) {
                        creature.setActionTemplates(withBonus(MonsterTemplates.of(monster), castersModifier));
                    }
                }
                encounter.getParticipants().add(creature);
                // Saved at once, so it has an ID for the turn order, its log entries and undo
                made.add(participantRepository.save(creature));
            }
        }
        combatLog.write(encounter, CombatActionType.SUMMON, caster.getDisplayName() + "'s " + spell.name()
                + " summons " + names(made) + initiativeNote(plan.initiativeMode(), made) + how
                + controlNote(spell, saveDc)
                + (castersModifier != null ? ", each adding " + signed(castersModifier) + " to its attack and damage "
                        + "rolls (XGE p.153)" : ""), caster, null);
    }

    /**
     * The stat block's actions with a bonus to every attack roll and to the damage of its hits, as Danse Macabre's
     * undead have the caster's spellcasting ability modifier (XGE p.153).
     */
    MonsterTemplates withBonus(MonsterTemplates templates, int bonus) {
        JsonNode json = templates.json();
        if (json == null || !json.isObject()) {
            return templates;
        }
        ObjectNode copy = (ObjectNode) json.deepCopy();
        for (String list : List.of("actions", "bonusActions", "reactions")) {
            for (JsonNode action : copy.path(list)) {
                if (!(action instanceof ObjectNode attack)
                        || !"ATTACK_ROLL".equalsIgnoreCase(attack.path("deliveryMethod").asString(""))) {
                    continue;
                }
                if (attack.path("attackBonus").isNumber()) {
                    attack.put("attackBonus", attack.path("attackBonus").asInt() + bonus);
                }
                for (JsonNode part : attack.path("effects")) {
                    if (part instanceof ObjectNode effect && "DAMAGE".equalsIgnoreCase(
                            effect.path("effectType").asString("")) && effect.path("damageDice").isString()) {
                        effect.put("damageDice", SummonRules.withFlatBonus(effect.path("damageDice").asString(),
                                bonus));
                    }
                }
            }
        }
        return MonsterTemplates.of(copy);
    }

    private static String signed(int number) {
        return (number >= 0 ? "+" : "") + number;
    }

    // What the DM keeps in mind for a creature that may break free or that the DM runs
    private static String controlNote(SummonRules.Spell spell, int saveDc) {
        if (spell.savesToBreakControl()) {
            return "; at the end of each of its turns it makes a DC " + saveDc + " Charisma save, with disadvantage "
                    + "if its true name is spoken (the DM's call), and on a success the caster's control ends (XGE "
                    + "p.166)";
        }
        if (spell.kind() == SummonRules.Kind.GIANT_INSECT) {
            return "; they obey the caster's commands and act on its turn, and the DM resolves their actions and "
                    + "movement (PHB p.245)";
        }
        if (spell.turnsHostileWhenConcentrationBreaks()) {
            return "; if the caster's concentration is broken it doesn't disappear but turns hostile, out of the "
                    + "caster's control, and disappears 1 hour after it was summoned (PHB p.225)";
        }
        if (spell.kind() == SummonRules.Kind.INFERNAL_CALLING) {
            return "; it is unfriendly and the DM runs it. A command it dislikes needs the caster's Charisma "
                    + "(Deception, Intimidation or Persuasion) check against its Wisdom (Insight), with advantage for "
                    + "its true name; on a failure it ignores the caster's commands for the rest of the spell (XGE "
                    + "p.158)";
        }
        return "";
    }

    // The caster's concentration on the spell, which the cast has just started
    private static ConcentrationInstance concentrationOn(EncounterParticipant caster, SummonRules.Spell spell) {
        ConcentrationInstance held = caster.getConcentration();
        return spell.concentration() && held != null && held.spell() != null
                && held.spell().equalsIgnoreCase(spell.name()) ? held : null;
    }

    // A familiar or a steed is one at a time: the caster's earlier one goes (PHB p.240), Find Steed's and Find Greater
    // Steed's sharing the one mount (XGE p.156)
    private void replaceOneAtATime(Encounter encounter, EncounterParticipant caster, SummonRules.Spell spell) {
        if (!spell.oneAtATime()) {
            return;
        }
        for (SpellEffectEntry earlier : EffectApplier.effects(caster)) {
            if (earlier.summon() != null && SummonRules.spell(earlier.spell())
                    .filter(other -> SummonRules.sharesBond(spell, other)).isPresent()) {
                effectApplier.endEffect(encounter, caster, earlier.id(), spell.name()
                        + (earlier.spell().equalsIgnoreCase(spell.name()) ? " is cast again" : " replaces it"));
            }
        }
    }

    private static SummonRules.Option option(SummonRules.Spell spell, int slotLevel, boolean hasFamiliar,
                                             String optionId) {
        List<SummonRules.Option> options = SummonRules.options(spell, slotLevel, hasFamiliar);
        if (optionId == null || optionId.isBlank()) {
            if (options.size() == 1) {
                return options.getFirst();
            }
            throw new ValidationException("Choose one of " + spell.name() + "'s options: "
                    + String.join(", ", options.stream().map(SummonRules.Option::id).toList()));
        }
        return options.stream().filter(option -> option.id().equals(optionId.trim())).findFirst()
                .orElseThrow(() -> new ValidationException(optionId + " isn't one of " + spell.name()
                        + "'s options at level " + slotLevel + ": " + String.join(", ",
                        options.stream().map(SummonRules.Option::id).toList())));
    }

    private static String chosenType(SummonRules.Spell spell, SummonChoice choice) {
        if (choice.creatureType() == null || choice.creatureType().isBlank()) {
            throw new ValidationException("Choose whether " + spell.name() + "'s creature is "
                    + String.join(", ", spell.typeChoices()));
        }
        SummonRules.refusalOfType(spell, choice.creatureType()).ifPresent(reason -> {
            throw new ValidationException(reason);
        });
        return choice.creatureType().trim().toLowerCase();
    }

    private static String names(List<EncounterParticipant> made) {
        List<String> names = made.stream().map(EncounterParticipant::getDisplayName).toList();
        if (names.size() <= 1) {
            return names.isEmpty() ? "nothing" : names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    // The plan's creatures by kind: "Wolf ×8", "Bestial Spirit (Land)", "Skeleton ×2 and Zombie"
    private static String creatures(Plan plan) {
        List<String> kinds = plan.picked().stream()
                .map(picked -> picked.monster().getName()
                        + (plan.variant() != null && plan.spirit().hasChoice() ? " (" + plan.variant().name() + ")"
                        : "")
                        + (picked.count() > 1 ? " ×" + picked.count() : ""))
                .toList();
        if (kinds.size() <= 1) {
            return kinds.isEmpty() ? "nothing" : kinds.getFirst();
        }
        return String.join(", ", kinds.subList(0, kinds.size() - 1)) + " and " + kinds.getLast();
    }

    private static String initiativeNote(InitiativeMode mode, List<EncounterParticipant> made) {
        if (made.isEmpty()) {
            return "";
        }
        return switch (mode) {
            case FOLLOWS_CASTER -> ", acting right after its caster";
            case GROUP -> {
                List<Integer> rolls = made.stream().map(EncounterParticipant::getInitiative).distinct().toList();
                yield rolls.size() == 1 ? ", initiative " + rolls.getFirst() + " as a group"
                        : ", initiative " + rolls.stream().map(String::valueOf).reduce((a, b) -> a + "/" + b)
                                .orElse("") + " by kind";
            }
            case INDIVIDUAL -> ", each rolling its own initiative";
        };
    }

    private static int numberOr(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    /** The caster's Spell Effect of that ID, wherever it is; empty when no participant has it. */
    public static Optional<Map.Entry<EncounterParticipant, SpellEffectEntry>> effect(Encounter encounter,
                                                                                    UUID effectId) {
        for (EncounterParticipant participant : encounter.getParticipants()) {
            for (SpellEffectEntry effect : EffectApplier.effects(participant)) {
                if (effect.id().equals(effectId)) {
                    return Optional.of(Map.entry(participant, effect));
                }
            }
        }
        return Optional.empty();
    }
}
