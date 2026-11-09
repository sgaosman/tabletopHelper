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
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.SummonRules.InitiativeMode;
import com.tabletophelper.rules.combat.SummonSpirits;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
 *       and the DM's (XGE p.167).</li>
 *   <li>A Tasha's spirit's AC, hit points and actions are worked out for the slot level and the caster's spell numbers
 *       ({@link SpiritTemplates}).</li>
 *   <li>A familiar and a steed are one at a time (PHB p.240): a new one replaces the caster's earlier one.</li>
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
    }

    /** The summoning spell of that name; a 400 for any other. */
    public static SummonRules.Spell spell(String name) {
        return SummonRules.spell(name).orElseThrow(() -> new ValidationException(name + " isn't a summoning spell"));
    }

    /**
     * Whether the DM chooses the spell's creatures in this encounter: always Summon Lesser Demons' (XGE p.167), and
     * Conjure Animals' while the campaign says so (PHB p.225: "The DM has the creatures' statistics").
     */
    public static boolean dmChooses(Encounter encounter, SummonRules.Spell spell) {
        return spell.dmChooses() || spell.kind() == SummonRules.Kind.CONJURE_ANIMALS
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
        int total = picked.stream().mapToInt(Picked::count).sum();
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
     * @param attackBonus the caster's spell attack bonus for this cast, which a Tasha's spirit attacks with
     * @param saveDc      the caster's spell save DC for this cast
     */
    public SpellEffectEntry afterCast(Encounter encounter, EncounterParticipant caster, Prepared prepared,
                                      int slotLevel, int attackBonus, int saveDc, String how) {
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
        place(encounter, caster, effect, prepared.plan(), attackBonus, saveDc, how);
        return effect;
    }

    /**
     * A summoning the DM places with no slot spent (D182), from the companion command or the add-participant form:
     * the request checked against the spell, linked to the caster's concentration when it asks, or when the spell
     * needs it.
     */
    public SpellEffectEntry companion(Encounter encounter, CompanionSummonRequest request) {
        EncounterParticipant caster = CombatAccess.participant(encounter, request.casterParticipantId());
        SummonRules.Spell spell = spell(request.spellName());
        int slot = request.slotLevel() != null ? request.slotLevel() : spell.level();
        Plan plan = check(encounter, caster, spell, slot, request.choice(), true);
        boolean linked = request.concentrationLinked() != null ? request.concentrationLinked() : spell.concentration();
        return placeCompanion(encounter, caster, plan, linked);
    }

    /**
     * A summoning the DM places with no slot spent, before the fight or during it (D182): the caster's concentration
     * starts first when it is linked, ending any it held.
     */
    public SpellEffectEntry placeCompanion(Encounter encounter, EncounterParticipant caster, Plan plan,
                                           boolean concentrationLinked) {
        SummonRules.Spell spell = plan.spell();
        ConcentrationInstance instance = concentrationLinked
                ? effectApplier.startConcentration(encounter, caster, spell.name(), plan.slotLevel(),
                        spell.durationRounds())
                : null;
        replaceOneAtATime(encounter, caster, spell);
        SpellEffectEntry effect = effectApplier.startSummonEffect(encounter, caster, spell.name(), plan.slotLevel(),
                instance, spell.durationRounds(), new SummonSpec(plan.option().id(),
                        SummonRules.multiplier(spell, plan.slotLevel()), null, SummonSpec.Status.PLACED));
        place(encounter, caster, effect, plan, numberOr(caster.getSpellAttackBonus(), 0),
                numberOr(caster.getSpellSaveDc(), 10), " (placed by the DM, no slot spent)");
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
        place(encounter, caster, placed, plan, numberOr(caster.getSpellAttackBonus(), 0),
                numberOr(caster.getSpellSaveDc(), 10), " (chosen by the DM)");
    }

    /** Whether the caster has a familiar in the fight already (PHB p.240, LLK p.57). */
    public static boolean hasFamiliar(Encounter encounter, EncounterParticipant caster) {
        return encounter.getParticipants().stream()
                .filter(p -> !EncounterTurns.dismissed(p) && caster.getId().equals(p.getSummonedByParticipantId()))
                .anyMatch(p -> p.getSummon() != null && p.getSummon().kind() == SummonRules.Kind.FAMILIAR);
    }

    // ── Making the creatures ─────────────────────────────────────

    private void place(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect, Plan plan,
                       int attackBonus, int saveDc, String how) {
        SummonRules.Spell spell = plan.spell();
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
            String base = plan.spirit() != null ? monster.getName() + " (" + plan.variant().name() + ")"
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
                boolean numbered = picked.count() > 1 || existing > 0;
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
                                spell.needsCommand(), null))
                        .build();
                monsterPools.setUp(creature, monster);
                if (plan.spirit() != null) {
                    int hp = SpiritTemplates.hitPoints(plan.spirit(), plan.variant(), plan.slotLevel());
                    creature.setHpMax(hp);
                    creature.setHpCurrent(hp);
                    creature.setArmourClass(SpiritTemplates.armourClass(plan.spirit(), plan.slotLevel()));
                    creature.setActionTemplates(spiritTemplates.templates(plan.spirit(), plan.variant(),
                            plan.slotLevel(), attackBonus, saveDc));
                } else {
                    int hp = monster.getHitPoints() != null && monster.getHitPoints() > 0 ? monster.getHitPoints() : 1;
                    creature.setHpMax(hp);
                    creature.setHpCurrent(hp);
                }
                encounter.getParticipants().add(creature);
                // Saved at once, so it has an ID for the turn order, its log entries and undo
                made.add(participantRepository.save(creature));
            }
        }
        combatLog.write(encounter, CombatActionType.SUMMON, caster.getDisplayName() + "'s " + spell.name()
                + " summons " + names(made) + initiativeNote(plan.initiativeMode(), made) + how, caster, null);
    }

    // The caster's concentration on the spell, which the cast has just started
    private static ConcentrationInstance concentrationOn(EncounterParticipant caster, SummonRules.Spell spell) {
        ConcentrationInstance held = caster.getConcentration();
        return spell.concentration() && held != null && held.spell() != null
                && held.spell().equalsIgnoreCase(spell.name()) ? held : null;
    }

    // A familiar or a steed is one at a time: the caster's earlier one goes (PHB p.240)
    private void replaceOneAtATime(Encounter encounter, EncounterParticipant caster, SummonRules.Spell spell) {
        if (spell.kind() != SummonRules.Kind.FAMILIAR && spell.kind() != SummonRules.Kind.STEED) {
            return;
        }
        for (SpellEffectEntry earlier : EffectApplier.effects(caster)) {
            if (earlier.summon() != null && earlier.spell().equalsIgnoreCase(spell.name())) {
                effectApplier.endEffect(encounter, caster, earlier.id(), spell.name() + " is cast again");
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
