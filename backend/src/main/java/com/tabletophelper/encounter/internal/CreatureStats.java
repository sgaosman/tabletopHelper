package com.tabletophelper.encounter.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.EquippedArmour;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.KenseiFeatures;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.RollModifiers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A participant's numbers as the rules need them, read from its character sheet or its monster's stat block:
 * saving throw modifiers, defences against a damage type, the level that scales cantrips, the
 * spellcasting modifier, and the armour a player character wears (M13.5).
 */
@Component
@RequiredArgsConstructor
public class CreatureStats {

    private static final Pattern BONUS = Pattern.compile("^\\s*([+-]?\\d{1,3})\\s*$");
    private static final Pattern WHOLE_RATING = Pattern.compile("^\\s*(\\d{1,2})\\s*$");
    // How the race data records a condition immunity among its resistances
    private static final Pattern RACE_CONDITION_IMMUNITY =
            Pattern.compile("^\\s*condition immunity:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final EquippedArmour equippedArmour;

    /**
     * The participant's AC now: its own, or its beast's in Wild Shape, with the bonuses its entries give while they
     * hold, which are a Kensei monk's Agile Parry's +2 unless it is incapacitated (XGE p.34), and with the Ongoing
     * Modifiers on it (D184), such as Mage Armor's base, Shield of Faith's +2 and Barkskin's floor; null when it has
     * none.
     */
    public static Integer armourClass(EncounterParticipant participant) {
        Integer base = participant.getArmourClass();
        if (base == null) {
            return null;
        }
        boolean parrying = participant.getActiveConditions() != null && participant.getActiveConditions().stream()
                .anyMatch(c -> c != null && KenseiFeatures.AGILE_PARRY.equalsIgnoreCase(c.name()));
        int own = parrying ? base + KenseiFeatures.agileParryBonus(conditionNames(participant)) : base;
        return OngoingModifiers.armourClass(own, ActiveModifiers.modifiersOn(participant));
    }

    /**
     * The condition names a participant has, with its exhaustion level as "exhausted (N)" when it has one (D183), so
     * the attack, save and speed rules that read the conditions read the level too ({@link Exhaustion#level}).
     */
    public static List<String> conditionNames(EncounterParticipant participant) {
        List<String> names = new ArrayList<>();
        if (participant.getActiveConditions() != null) {
            participant.getActiveConditions().stream().map(ConditionEntry::name).forEach(names::add);
        }
        int exhaustion = Exhaustion.clamp(participant.getExhaustionLevel());
        if (exhaustion > 0) {
            names.add(Exhaustion.conditionName(exhaustion));
        }
        return java.util.Collections.unmodifiableList(names);
    }

    /**
     * The participant's hit point maximum now: its own, or its beast's in Wild Shape, halved from exhaustion level 4
     * (PHB p.291, D183). The stored maximum stays whole, so the halving ends when the level drops below 4, and
     * Write-back never sees a halved maximum.
     */
    public static int hpMax(EncounterParticipant participant) {
        int stored = participant.getHpMax() != null ? participant.getHpMax() : 1;
        // Aid raises the maximum, and exhaustion halves the maximum it has then (PHB p.211, p.291; D184)
        List<OngoingModifier> modifiers = ActiveModifiers.modifiersOn(participant);
        int raised = OngoingModifiers.hitPointMaximum(stored, modifiers);
        // Aura of Life: the maximum can't be reduced, so exhaustion doesn't halve it (PHB p.216, D185)
        if (OngoingModifiers.maximumProtected(modifiers)) {
            return Math.max(stored, raised);
        }
        return Exhaustion.hitPointMaximum(raised, Exhaustion.clamp(participant.getExhaustionLevel()));
    }

    /**
     * The participant's blindsight and truesight (MM p.8-9): its beast's in Wild Shape (PHB p.67), or its stat block's,
     * a summoned creature's included; none for a player character, whose sheet records neither.
     */
    public static AttackRules.Senses senses(EncounterParticipant participant) {
        AttackRules.Senses own;
        Optional<WildShapeForm> form = WildShapeForm.of(participant);
        if (form.isPresent()) {
            own = AttackRules.Senses.of(form.get().senses());
        } else {
            own = participant.getMonster() != null ? AttackRules.Senses.of(participant.getMonster().getSenses())
                    : AttackRules.Senses.NONE;
        }
        // Truesight a spell gives, as True Seeing's (PHB p.284, D188)
        boolean granted = ActiveModifiers.modifiersOn(participant).stream()
                .anyMatch(m -> m.kind() == OngoingModifier.Kind.TRUESIGHT);
        return granted && !own.truesight() ? new AttackRules.Senses(own.blindsight(), true) : own;
    }

    // The conditions that include being incapacitated, which take Evasion away (PHB p.79, p.96)
    private static final Set<String> INCAPACITATED = Set.of("incapacitated", "paralyzed", "petrified", "stunned",
            "unconscious");

    /**
     * Whether the participant has Evasion now (D188): a rogue or monk of 7th level or higher (PHB p.96, p.79), a sheet
     * with the feature by name, a monster whose stat block has the trait, or an Ongoing Modifier that gives it, as Fizban's
     * Platinum Shield (FTD p.20); never while it is incapacitated.
     */
    public static boolean evasion(EncounterParticipant participant) {
        if (conditionNames(participant).stream().anyMatch(name -> INCAPACITATED.contains(name.toLowerCase(
                java.util.Locale.ROOT)))) {
            return false;
        }
        if (ActiveModifiers.modifiersOn(participant).stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.EVASION)) {
            return true;
        }
        PlayerCharacter character = participant.getCharacter();
        if (character != null) {
            return FeatureEffects.classLevel(character, "Rogue") >= EVASION_LEVEL
                    || FeatureEffects.classLevel(character, "Monk") >= EVASION_LEVEL
                    || FeatureEffects.hasFeature(character, "Evasion");
        }
        String traits = participant.getMonster() != null ? participant.getMonster().getTraits() : null;
        return traits != null && EVASION_TRAIT.matcher(traits).find();
    }

    /** The rogue's and the monk's level that brings Evasion (PHB p.96, p.79). */
    public static final int EVASION_LEVEL = 7;

    // A stat block's Evasion trait, by its name
    private static final java.util.regex.Pattern EVASION_TRAIT = java.util.regex.Pattern.compile("\\bEvasion\\b");

    /**
     * The participant's side of an attack roll, as attacker or target: its conditions and senses
     * ({@link AttackRules#setup}); nothing for no participant, such as the DM attacking by hand.
     */
    public static AttackRules.Side side(EncounterParticipant participant) {
        return participant == null ? AttackRules.Side.NONE
                : new AttackRules.Side(conditionNames(participant), senses(participant));
    }

    /**
     * The participant as the Resolver needs it for this Effect: AC, the modifier for the Effect's saving throw,
     * the hit's save included, conditions, whether armour it isn't proficient with gives it disadvantage on
     * Strength and Dexterity saves (PHB p.144), and its senses. A creature holding a Bardic Inspiration die it keeps
     * for a failed save rolls it when it fails the Effect's save (PHB p.53, M17).
     */
    public Resolver.Target target(EncounterParticipant participant, Effect effect) {
        return target(participant, effect, null);
    }

    /**
     * As {@link #target(EncounterParticipant, Effect)}, for an Effect the attacker uses, with what the Ongoing
     * Modifiers of both do to it (D184): the named sources of advantage and disadvantage, the dice added to an attack
     * roll and the damage added to a hit, and the dice and modes on the target's save.
     *
     * @param attacker the participant using the Effect, or null for none, as the DM's own attack
     */
    public Resolver.Target target(EncounterParticipant participant, Effect effect, EncounterParticipant attacker) {
        Ability saveAbility = effect.saveAbility();
        List<OngoingModifiers.Labelled> own = ActiveModifiers.labelledOn(participant);
        // A mode kept for effects that give certain conditions counts against this one's (Aura of Purity, D185)
        List<RollMode> saveModes = saveAbility != null
                ? OngoingModifiers.saveModes(saveAbility.name(), own.stream().map(OngoingModifiers.Labelled::modifier)
                        .toList(), effect.conditions())
                : List.of();
        return new Resolver.Target(participant.getId(), armourClass(participant),
                saveModifier(participant, saveAbility), conditionNames(participant),
                armourPenalty(participant), saveModes,
                effect.delivery() instanceof Effect.Save ? inspirationForSaves(participant) : 0, false,
                senses(participant), rollModifiers(participant, effect, attacker, own), evasion(participant));
    }

    /** Each participant as the Resolver needs it for an Effect the attacker uses, in the same order (D184). */
    public List<Resolver.Target> targets(List<EncounterParticipant> participants, Effect effect,
                                         EncounterParticipant attacker) {
        return participants.stream().map(participant -> target(participant, effect, attacker)).toList();
    }

    // What the Ongoing Modifiers do to the Effect against the target (D184)
    private static RollModifiers rollModifiers(EncounterParticipant target, Effect effect,
                                               EncounterParticipant attacker, List<OngoingModifiers.Labelled> own) {
        Ability saveAbility = effect.saveAbility();
        List<RollModifiers.Bonus> saveBonuses = saveAbility != null
                ? OngoingModifiers.saveBonuses(saveAbility.name(), own) : List.of();
        boolean revealed = OngoingModifiers.revealed(own.stream().map(OngoingModifiers.Labelled::modifier).toList());
        if (!(effect.delivery() instanceof Effect.Attack attack)) {
            return new RollModifiers(List.of(), List.of(), List.of(), saveBonuses, Set.of(), revealed);
        }
        List<ActiveModifiers.Found> attackers = ActiveModifiers.attacking(attacker, target);
        List<OngoingModifiers.Labelled> attackerMods = ActiveModifiers.labelled(attackers);
        AttackRules.Side targetSide = revealed ? side(target).revealed() : side(target);
        List<AttackRules.Source> sources = new ArrayList<>(OngoingModifiers.attackerSources(attack.weapon(),
                attackerMods));
        sources.addAll(OngoingModifiers.targetSources(ActiveModifiers.labelled(ActiveModifiers.attacked(target)),
                side(attacker), targetSide, attacker != null ? creatureType(attacker) : null));
        Set<UUID> oneShots = new HashSet<>();
        for (ActiveModifiers.Found found : attackers) {
            OngoingModifier modifier = found.modifier();
            if (modifier.oneShot() && modifier.kind() == OngoingModifier.Kind.ATTACK_MODE
                    && (attack.weapon() || !modifier.weaponOnly())) {
                oneShots.add(modifier.id());
            }
        }
        return new RollModifiers(sources, OngoingModifiers.attackBonuses(attack.weapon(), attackerMods),
                OngoingModifiers.hitDamage(attack.weapon(), attack.withinFiveFeet(), attackerMods), saveBonuses,
                oneShots, revealed);
    }

    /**
     * What the Ongoing Modifiers on the participant add to a saving throw of the ability, a death save when it is null
     * (D184): their dice and amounts, and their advantage or disadvantage.
     */
    public static SaveModifiers saveModifiers(EncounterParticipant participant, Ability ability) {
        return saveModifiers(participant, ability, List.of());
    }

    /**
     * As {@link #saveModifiers(EncounterParticipant, Ability)}, for a save against an effect that gives these
     * conditions, as a repeated save against being paralyzed is (Aura of Purity, PHB p.216, D185).
     */
    public static SaveModifiers saveModifiers(EncounterParticipant participant, Ability ability, List<String> against) {
        List<OngoingModifiers.Labelled> own = ActiveModifiers.labelledOn(participant);
        String name = ability != null ? ability.name() : null;
        return new SaveModifiers(OngoingModifiers.saveBonuses(name, own),
                OngoingModifiers.saveModes(name, own.stream().map(OngoingModifiers.Labelled::modifier).toList(),
                        against));
    }

    /** The dice and modes Ongoing Modifiers give one saving throw (D184). */
    public record SaveModifiers(List<RollModifiers.Bonus> bonuses, List<RollMode> modes) {
    }

    // The sides of the Bardic Inspiration die the creature keeps for its next failed save, or 0
    private static int inspirationForSaves(EncounterParticipant participant) {
        if (participant.getActiveConditions() == null) {
            return 0;
        }
        return participant.getActiveConditions().stream()
                .filter(c -> ClassFeatures.INSPIRED.equalsIgnoreCase(c.name()) && c.heldDie() != null
                        && c.heldDie().onFailedSave())
                .mapToInt(c -> c.heldDie().sides())
                .findFirst()
                .orElse(0);
    }

    /**
     * The armour a player character wears, read live from its sheet; nothing for a monster, whose stat block's AC
     * already counts its armour. A druid in beast form wears nothing: its equipment merges into the new form (PHB
     * p.67), so its armour gives neither AC nor penalties.
     */
    public EquippedArmour.Worn armour(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        return character != null && WildShapeForm.of(participant).isEmpty() ? equippedArmour.of(character)
                : EquippedArmour.Worn.nothing();
    }

    /**
     * Whether the participant wears armour or a shield it isn't proficient with: disadvantage on every ability check,
     * saving throw and attack roll that uses Strength or Dexterity, and no spellcasting (PHB p.144).
     */
    public boolean armourPenalty(EncounterParticipant participant) {
        return armour(participant).penalised();
    }

    /** Each participant as the Resolver needs it for this Effect, in the same order. */
    public List<Resolver.Target> targets(List<EncounterParticipant> participants, Effect effect) {
        return participants.stream().map(participant -> target(participant, effect)).toList();
    }

    /**
     * An ability score, 10 when the participant has no sheet or stat block, or the score is missing. A druid in beast
     * form has the beast's Strength, Dexterity and Constitution and keeps its own Intelligence, Wisdom and Charisma
     * (PHB p.67).
     */
    public int abilityScore(EncounterParticipant participant, Ability ability) {
        Optional<WildShapeForm> form = WildShapeForm.of(participant);
        if (form.isPresent()) {
            switch (ability) {
                case STR -> {
                    return form.get().strength();
                }
                case DEX -> {
                    return form.get().dexterity();
                }
                case CON -> {
                    return form.get().constitution();
                }
                default -> {
                    // Its own mental scores
                }
            }
        }
        return score(participant, ability);
    }

    /**
     * As {@link #abilityScore}, for callers without the component, such as the turn order. A player character's is
     * read live from its sheet, with the DM's adjustments to it (D158); a druid in beast form keeps its own here, so its
     * place in the turn order doesn't move when it transforms.
     */
    public static int score(EncounterParticipant participant, Ability ability) {
        if (participant.getCharacter() != null) {
            return DmAdjustments.abilityScore(participant.getCharacter(), ability);
        }
        if (participant.getMonster() != null) {
            int score = orTen(monsterScore(participant.getMonster(), ability));
            // Find Steed's steed and Find Greater Steed's mount have an Intelligence of at least 6 (PHB p.240, XGE
            // p.156, D182)
            if (ability == Ability.INT && participant.getSummon() != null
                    && participant.getSummon().kind() == SummonRules.Kind.STEED) {
                return Math.max(score, SummonRules.STEED_MINIMUM_INTELLIGENCE);
            }
            return score;
        }
        return 10;
    }

    /**
     * The creature type the rules read, such as "undead" for Divine Smite (PHB p.85) or Turn Undead (PHB p.59): the
     * type a summoning spell gives its creature, such as Conjure Animals' fey (PHB p.225) or a familiar's celestial, fey
     * or fiend (PHB p.240), or else its stat block's; null for a creature without one, such as a player character.
     */
    public static String creatureType(EncounterParticipant participant) {
        if (participant.getSummon() != null && participant.getSummon().creatureType() != null) {
            return participant.getSummon().creatureType();
        }
        return participant.getMonster() != null ? participant.getMonster().getType() : null;
    }

    /**
     * The creature type a spell's rules read (D185), as Blight's for undead, constructs and plants (PHB p.219) or Dominate
     * Beast's for beasts (PHB p.234): a beast while in Wild Shape (PHB p.66), the type a summoning gives, the stat
     * block's, or a player character's race's, humanoid when its data gives none (PHB p.17-18 races are humanoid).
     */
    public static String typeForSpells(EncounterParticipant participant) {
        if (WildShapeForm.of(participant).isPresent()) {
            return "beast";
        }
        String type = creatureType(participant);
        if (type != null) {
            // The stat block's tags count too, such as a werewolf's shapechanger, which Polymorph doesn't affect (PHB
            // p.266)
            boolean ownType = participant.getSummon() == null || participant.getSummon().creatureType() == null;
            String tags = ownType && participant.getMonster() != null ? participant.getMonster().getSubtype() : null;
            return (type.trim() + (tags != null && !tags.isBlank() ? " (" + tags.trim() + ")" : ""))
                    .toLowerCase(Locale.ROOT);
        }
        if (participant.getCharacter() != null) {
            Race race = participant.getCharacter().getRaceRef();
            String raceType = race != null ? race.getCreatureType() : null;
            return raceType != null && !raceType.isBlank() ? raceType.trim().toLowerCase(Locale.ROOT) : "humanoid";
        }
        return null;
    }

    /**
     * The participant's modifier for a saving throw of the ability, 0 when the ability is unknown. A player
     * character adds its proficiency bonus when proficient in that save; a monster uses its stat block's save
     * bonus when it lists one, and its ability modifier otherwise.
     */
    public int saveModifier(EncounterParticipant participant, Ability ability) {
        if (ability == null) {
            return 0;
        }
        if (participant.getCharacter() != null) {
            PlayerCharacter character = participant.getCharacter();
            int modifier = Ability.modifier(abilityScore(participant, ability));
            int own = !proficientInSave(character, ability) ? modifier
                    : modifier + (character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2);
            // In beast form, the beast's listed save bonus when it is higher (PHB p.67)
            return WildShapeForm.of(participant)
                    .map(form -> BeastShapes.bonus(own, form.saves().get(ability.name().toLowerCase(Locale.ROOT))))
                    .orElse(own);
        }
        if (participant.getMonster() != null) {
            Monster monster = participant.getMonster();
            JsonNode saves = StoredJson.read(objectMapper, monster.getSavingThrows(),
                    monster.getName() + "'s saving throws");
            Integer listed = bonus(saves.path(ability.name().toLowerCase()));
            return listed != null ? listed : Ability.modifier(abilityScore(participant, ability));
        }
        return 0;
    }

    /**
     * The participant's modifier for an ability check with a skill, such as Dexterity (Stealth), 0 for a participant
     * with neither sheet nor stat block. A player character adds its proficiency bonus when proficient in the skill,
     * twice with expertise, as its sheet shows it; a monster uses its stat block's skill bonus when it lists one,
     * and its ability modifier otherwise.
     *
     * @param skill the skill as the sheet and stat block name it, such as "Stealth"
     */
    public int skillModifier(EncounterParticipant participant, String skill, Ability ability) {
        int modifier = Ability.modifier(abilityScore(participant, ability));
        if (participant.getCharacter() != null) {
            PlayerCharacter character = participant.getCharacter();
            int proficiency = character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2;
            int own;
            if (names(character.getSkillExpertises(), skill)) {
                own = modifier + 2 * proficiency;
            } else {
                own = names(character.getSkillProficiencies(), skill) ? modifier + proficiency : modifier;
            }
            // In beast form, the beast's listed skill bonus when it is higher (PHB p.67)
            return WildShapeForm.of(participant)
                    .map(form -> BeastShapes.bonus(own, form.skills().get(skill.toLowerCase(Locale.ROOT))))
                    .orElse(own);
        }
        if (participant.getMonster() != null) {
            Monster monster = participant.getMonster();
            JsonNode skills = StoredJson.read(objectMapper, monster.getSkills(), monster.getName() + "'s skills");
            Integer listed = bonus(skills.path(skill.toLowerCase()));
            return listed != null ? listed : modifier;
        }
        return 0;
    }

    private static boolean names(List<String> list, String name) {
        return list != null && list.stream().anyMatch(entry -> entry != null && entry.trim().equalsIgnoreCase(name));
    }

    /**
     * The participant's defences against a damage type; none for untyped damage. A monster's come from its
     * stat block, where only plain damage type names count: a conditional entry, such as resistance to
     * nonmagical attacks, is the DM's call. A player character's come from its sheet, read live: the
     * resistances and immunities its race, features and feats give, and the DM's DEFENCE adjustments, the
     * only source of its vulnerabilities, so one added during the encounter applies at once (D161). Either resists
     * bludgeoning, piercing and slashing damage while it rages (PHB p.48, M17).
     */
    public Set<Defence> defences(EncounterParticipant participant, String damageType) {
        return defences(participant, damageType, false);
    }

    /**
     * As {@link #defences(EncounterParticipant, String)}, for damage that is magical or not (D185): a resistance an Ongoing
     * Modifier gives only against nonmagical damage, as Stoneskin's (PHB p.278), counts only against nonmagical damage,
     * and one that strips a resistance, as Elemental Bane's (XGE p.155), takes it away.
     */
    public Set<Defence> defences(EncounterParticipant participant, String damageType, boolean magical) {
        if (damageType == null || damageType.isBlank()) {
            return Set.of();
        }
        String type = damageType.trim();
        Set<Defence> defences = EnumSet.noneOf(Defence.class);
        if (participant.getMonster() != null) {
            Monster monster = participant.getMonster();
            String name = monster.getName();
            if (lists(monster.getDamageResistances(), name + "'s damage resistances", type)) {
                defences.add(Defence.RESISTANCE);
            }
            if (lists(monster.getDamageImmunities(), name + "'s damage immunities", type)) {
                defences.add(Defence.IMMUNITY);
            }
            if (lists(monster.getDamageVulnerabilities(), name + "'s damage vulnerabilities", type)) {
                defences.add(Defence.VULNERABILITY);
            }
            // A summoned spirit's variant adds its own, which its row lists only as conditional entries, such as the
            // Fire Elemental Spirit's immunity to fire (TCE p.111, D182)
            SummonLink link = participant.getSummon();
            if (link != null && names(link.damageResistances(), type)) {
                defences.add(Defence.RESISTANCE);
            }
            if (link != null && names(link.damageImmunities(), type)) {
                defences.add(Defence.IMMUNITY);
            }
        } else if (participant.getCharacter() != null) {
            defences.addAll(DmAdjustments.defences(participant.getCharacter(), type));
            // A druid in beast form has the beast's defences, and keeps those its own race and features give (PHB p.67)
            WildShapeForm.of(participant).ifPresent(form -> {
                if (names(form.damageResistances(), type)) {
                    defences.add(Defence.RESISTANCE);
                }
                if (names(form.damageImmunities(), type)) {
                    defences.add(Defence.IMMUNITY);
                }
                if (names(form.damageVulnerabilities(), type)) {
                    defences.add(Defence.VULNERABILITY);
                }
            });
        }
        // Resistance an Ongoing Modifier gives, such as Warding Bond's to all damage (PHB p.287, D184)
        List<OngoingModifier> modifiers = ActiveModifiers.modifiersOn(participant);
        if (OngoingModifiers.resists(type, modifiers, magical)) {
            defences.add(Defence.RESISTANCE);
        }
        // Immunity an Ongoing Modifier gives, as Investiture of Flame's to fire (XGE p.159, D188)
        if (modifiers.stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.DAMAGE_IMMUNITY && m.resists(type))) {
            defences.add(Defence.IMMUNITY);
        }
        // A raging creature resists bludgeoning, piercing and slashing damage (PHB p.48); resistances don't stack
        if (ClassFeatures.raging(conditionNames(participant)) && ClassFeatures.rageResists(type)) {
            defences.add(Defence.RESISTANCE);
        }
        // Elemental Bane: the creature loses its resistance to the type chosen (XGE p.155, D185)
        if (OngoingModifiers.losesResistance(type, modifiers)) {
            defences.remove(Defence.RESISTANCE);
        }
        return defences;
    }

    /**
     * Whether the participant is immune to the condition, so an Effect doesn't give it (D161). Both are read live,
     * as damage defences are. A monster's immunities are its stat block's, where only plain condition names count: a
     * conditional entry, such as one while wearing a mask, is the DM's call. A player character's are those its sheet
     * lists and those its race gives, which the race data records as "condition immunity: poisoned"; disease isn't a
     * condition, so a race's immunity to it changes nothing here.
     */
    public boolean immuneToCondition(EncounterParticipant participant, String condition) {
        return immuneToCondition(participant, condition, false);
    }

    /**
     * As {@link #immuneToCondition(EncounterParticipant, String)}, for a condition from a magical source or not (D185):
     * an immunity an Ongoing Modifier gives only against magic, as Freedom of Movement's to being paralyzed or restrained
     * (PHB p.244), counts only for a magical one.
     */
    public boolean immuneToCondition(EncounterParticipant participant, String condition, boolean magical) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String name = condition.trim();
        // Heroism's immunity to being frightened (PHB p.250, D184)
        if (OngoingModifiers.immuneTo(name, ActiveModifiers.modifiersOn(participant), magical)) {
            return true;
        }
        if (participant.getMonster() != null) {
            Monster monster = participant.getMonster();
            return lists(monster.getConditionImmunities(), monster.getName() + "'s condition immunities", name);
        }
        if (participant.getCharacter() != null) {
            PlayerCharacter character = participant.getCharacter();
            if (WildShapeForm.of(participant).filter(form -> names(form.conditionImmunities(), name)).isPresent()) {
                return true;
            }
            List<String> listed = character.getConditionImmunities();
            if (listed != null && listed.stream().anyMatch(c -> c != null && c.trim().equalsIgnoreCase(name))) {
                return true;
            }
            Race race = character.getRaceRef();
            if (race != null) {
                for (JsonNode entry : StoredJson.read(objectMapper, race.getResistances(),
                        race.getName() + "'s resistances")) {
                    Matcher immunity = RACE_CONDITION_IMMUNITY.matcher(entry.isString() ? entry.asString("") : "");
                    if (immunity.matches() && immunity.group(1).trim().equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The level that scales a participant's cantrips: a player character's level, or a monster's challenge
     * rating rounded up, at least 1.
     */
    public int level(EncounterParticipant participant) {
        if (participant.getCharacter() != null) {
            Integer level = participant.getCharacter().getLevel();
            return level != null ? level : 1;
        }
        if (participant.getMonster() != null) {
            String rating = participant.getMonster().getChallengeRating();
            Matcher whole = WHOLE_RATING.matcher(rating != null ? rating : "");
            if (whole.matches()) {
                return Math.max(1, Integer.parseInt(whole.group(1)));
            }
            // A fractional rating, below 1, and anything unreadable count as 1
            return 1;
        }
        return 1;
    }

    /**
     * The spellcasting ability modifier a spell adds to its damage or healing, the MOD placeholder in spell
     * templates: the modifier of the participant's score in the ability the spell is cast with, read as abilityScore
     * reads it, so a DM Adjustment or Manual Modifier to the attack bonus leaves it alone (D164). Only when the
     * ability is unknown is it worked back from the spell attack bonus, as the bonus less the proficiency bonus for
     * the participant's level (PHB p.205).
     */
    public int spellcastingModifier(EncounterParticipant participant, String ability, int spellAttackBonus) {
        return Ability.parse(ability)
                .map(known -> Ability.modifier(abilityScore(participant, known)))
                .orElseGet(() -> spellAttackBonus - ProficiencyBonus.forLevel(level(participant)));
    }

    private static boolean proficientInSave(PlayerCharacter character, Ability ability) {
        List<String> saves = character.getSavingThrowProficiencies();
        if (saves == null) {
            return false;
        }
        for (String save : saves) {
            if (save != null && Ability.parse(save).filter(ability::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // Whether a JSON array of names, such as damage types, names this one, in any case
    private boolean lists(String json, String what, String name) {
        for (JsonNode entry : StoredJson.read(objectMapper, json, what)) {
            if (entry.isString() && entry.asString("").trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // A save bonus as a stat block lists it: a number, or text such as "+6"; null when absent or unreadable
    private static Integer bonus(JsonNode node) {
        if (node.isInt()) {
            return node.asInt(0);
        }
        if (node.isString()) {
            Matcher matcher = BONUS.matcher(node.asString(""));
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return null;
    }

    private static int orTen(Integer score) {
        return score != null ? score : 10;
    }

    private static Integer monsterScore(Monster monster, Ability ability) {
        return switch (ability) {
            case STR -> monster.getStrength();
            case DEX -> monster.getDexterity();
            case CON -> monster.getConstitution();
            case INT -> monster.getIntelligence();
            case WIS -> monster.getWisdom();
            case CHA -> monster.getCharisma();
        };
    }
}
