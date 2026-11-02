package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
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
 * saving throw modifiers, defences against a damage type, the level that scales cantrips, and the
 * spellcasting modifier.
 */
@Component
@RequiredArgsConstructor
public class CreatureStats {

    private static final Pattern BONUS = Pattern.compile("^\\s*([+-]?\\d{1,3})\\s*$");
    private static final Pattern WHOLE_RATING = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

    private final ObjectMapper objectMapper;

    /** An ability modifier: (score - 10) / 2, rounding down (PHB p.13). */
    public static int modifier(int score) {
        return Math.floorDiv(score - 10, 2);
    }

    /** The condition names a participant has. */
    public static List<String> conditionNames(EncounterParticipant participant) {
        return participant.getActiveConditions() == null ? List.of()
                : participant.getActiveConditions().stream().map(ConditionEntry::name).toList();
    }

    /** The participant as the Resolver needs it for this Effect: AC, save modifier and conditions. */
    public Resolver.Target target(EncounterParticipant participant, Effect effect) {
        Ability saveAbility = effect.delivery() instanceof Effect.Save save ? save.ability() : null;
        return new Resolver.Target(participant.getId(), participant.getArmourClass(),
                saveModifier(participant, saveAbility), conditionNames(participant));
    }

    /** Each participant as the Resolver needs it for this Effect, in the same order. */
    public List<Resolver.Target> targets(List<EncounterParticipant> participants, Effect effect) {
        return participants.stream().map(participant -> target(participant, effect)).toList();
    }

    /** An ability score, 10 when the participant has no sheet or stat block, or the score is missing. */
    public int abilityScore(EncounterParticipant participant, Ability ability) {
        if (participant.getCharacter() != null) {
            return orTen(characterScore(participant.getCharacter(), ability));
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
            int modifier = modifier(abilityScore(participant, ability));
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
            return listed != null ? listed : modifier(abilityScore(participant, ability));
        }
        return 0;
    }

    /**
     * The participant's defences against a damage type; none for untyped damage. A monster's come from its
     * stat block, where only plain damage type names count: a conditional entry, such as resistance to
     * nonmagical attacks, is the DM's call. A player character's come from its sheet, which holds no
     * vulnerabilities until R6 (D161).
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
            PlayerCharacter character = participant.getCharacter();
            String name = character.getName();
            if (lists(character.getDamageResistances(), name + "'s damage resistances", type)) {
                defences.add(Defence.RESISTANCE);
            }
            if (lists(character.getDamageImmunities(), name + "'s damage immunities", type)) {
                defences.add(Defence.IMMUNITY);
            }
        }
        return defences;
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
     * The spellcasting ability modifier behind a spell attack bonus: the bonus less the proficiency bonus for
     * the participant's level (PHB p.205). It replaces the MOD placeholder in spell templates.
     */
    public int spellcastingModifier(EncounterParticipant participant, int spellAttackBonus) {
        return spellAttackBonus - ProficiencyBonus.forLevel(level(participant));
    }

    private boolean proficientInSave(PlayerCharacter character, Ability ability) {
        JsonNode saves = StoredJson.read(objectMapper, character.getSavingThrowProficiencies(),
                character.getName() + "'s saving throw proficiencies");
        for (JsonNode save : saves) {
            if (save.isTextual() && Ability.parse(save.asText()).filter(ability::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // Whether a JSON array of damage types names this one, in any case
    private boolean lists(String json, String what, String damageType) {
        for (JsonNode entry : StoredJson.read(objectMapper, json, what)) {
            if (entry.isTextual() && entry.asText().trim().equalsIgnoreCase(damageType)) {
                return true;
            }
        }
        return false;
    }

    // A save bonus as a stat block lists it: a number, or text such as "+6"; null when absent or unreadable
    private static Integer bonus(JsonNode node) {
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            Matcher matcher = BONUS.matcher(node.asText());
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return null;
    }

    private static int orTen(Integer score) {
        return score != null ? score : 10;
    }

    private static Integer characterScore(PlayerCharacter character, Ability ability) {
        return switch (ability) {
            case STR -> character.getStrength();
            case DEX -> character.getDexterity();
            case CON -> character.getConstitution();
            case INT -> character.getIntelligence();
            case WIS -> character.getWisdom();
            case CHA -> character.getCharisma();
        };
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
