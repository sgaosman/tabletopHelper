package com.tabletophelper.encounter.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.EquippedArmour;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

    /** The condition names a participant has. */
    public static List<String> conditionNames(EncounterParticipant participant) {
        return participant.getActiveConditions() == null ? List.of()
                : participant.getActiveConditions().stream().map(ConditionEntry::name).toList();
    }

    /**
     * The participant as the Resolver needs it for this Effect: AC, the modifier for the Effect's saving throw,
     * the hit's save included, conditions, and whether armour it isn't proficient with gives it disadvantage on
     * Strength and Dexterity saves (PHB p.144).
     */
    public Resolver.Target target(EncounterParticipant participant, Effect effect) {
        return new Resolver.Target(participant.getId(), participant.getArmourClass(),
                saveModifier(participant, effect.saveAbility()), conditionNames(participant),
                armourPenalty(participant));
    }

    /**
     * The armour a player character wears, read live from its sheet; nothing for a monster, whose stat block's AC
     * already counts its armour.
     */
    public EquippedArmour.Worn armour(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        return character != null ? equippedArmour.of(character) : EquippedArmour.Worn.nothing();
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

    /** An ability score, 10 when the participant has no sheet or stat block, or the score is missing. */
    public int abilityScore(EncounterParticipant participant, Ability ability) {
        return score(participant, ability);
    }

    /**
     * As {@link #abilityScore}, for callers without the component, such as the turn order. A player character's is
     * read live from its sheet, with the DM's adjustments to it (D158).
     */
    public static int score(EncounterParticipant participant, Ability ability) {
        if (participant.getCharacter() != null) {
            return DmAdjustments.abilityScore(participant.getCharacter(), ability);
        }
        if (participant.getMonster() != null) {
            return orTen(monsterScore(participant.getMonster(), ability));
        }
        return 10;
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
            if (!proficientInSave(character, ability)) {
                return modifier;
            }
            return modifier + (character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2);
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
            if (names(character.getSkillExpertises(), skill)) {
                return modifier + 2 * proficiency;
            }
            return names(character.getSkillProficiencies(), skill) ? modifier + proficiency : modifier;
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
        } else if (participant.getCharacter() != null) {
            defences.addAll(DmAdjustments.defences(participant.getCharacter(), type));
        }
        // A raging creature resists bludgeoning, piercing and slashing damage (PHB p.48); resistances don't stack
        if (ClassFeatures.raging(conditionNames(participant)) && ClassFeatures.rageResists(type)) {
            defences.add(Defence.RESISTANCE);
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
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String name = condition.trim();
        if (participant.getMonster() != null) {
            Monster monster = participant.getMonster();
            return lists(monster.getConditionImmunities(), monster.getName() + "'s condition immunities", name);
        }
        if (participant.getCharacter() != null) {
            PlayerCharacter character = participant.getCharacter();
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
