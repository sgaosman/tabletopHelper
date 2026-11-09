package com.tabletophelper.encounter.state;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.BeastShapes;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The beast a druid has become through Wild Shape (PHB p.66–67), kept on the {@code wild shape} entry among its
 * conditions, as Rage's is (M17). While it lasts the participant's hit points, hit point maximum and AC are the beast's;
 * the druid's own are kept here and come back when it reverts. The beast's other game statistics are copied from its
 * stat block when the druid transforms, so the rules read them without the monster row: its Strength, Dexterity and
 * Constitution, the save and skill bonuses its stat block lists, its speeds, senses, damage defences and condition
 * immunities. Its attacks are read from the monster's action templates through {@code monsterId}.
 *
 * @param monsterId            the beast's monster row
 * @param beastName            the beast, such as "Wolf"
 * @param challengeRating      its challenge rating, as the stat block writes it
 * @param hpMax                its hit point maximum, the participant's while transformed
 * @param armourClass          its AC, the participant's while transformed
 * @param strength             its Strength score
 * @param dexterity            its Dexterity score
 * @param constitution         its Constitution score
 * @param saves                the saving throw bonuses its stat block lists, by ability ("str" to "cha")
 * @param skills               the skill bonuses its stat block lists, by skill in lower case, such as "perception"
 * @param speed                its speeds in feet by kind, walking first
 * @param senses               its senses as the stat block lists them, such as "darkvision 60 ft."
 * @param damageResistances    the damage types it resists
 * @param damageImmunities     the damage types it is immune to
 * @param damageVulnerabilities the damage types it is vulnerable to
 * @param conditionImmunities  the conditions it is immune to
 * @param normalHpCurrent      the druid's own hit points when it transformed, which the beast form leaves alone
 * @param normalHpMax          the druid's own hit point maximum
 * @param normalArmourClass    the druid's own AC
 * @param druidLevel           the druid level it transformed at, for the duration
 */
public record WildShapeForm(
        @Schema(requiredMode = REQUIRED) UUID monsterId,
        @Schema(requiredMode = REQUIRED) String beastName,
        String challengeRating,
        @Schema(requiredMode = REQUIRED) int hpMax,
        @Schema(requiredMode = REQUIRED) int armourClass,
        @Schema(requiredMode = REQUIRED) int strength,
        @Schema(requiredMode = REQUIRED) int dexterity,
        @Schema(requiredMode = REQUIRED) int constitution,
        Map<String, Integer> saves,
        Map<String, Integer> skills,
        Map<String, Integer> speed,
        String senses,
        List<String> damageResistances,
        List<String> damageImmunities,
        List<String> damageVulnerabilities,
        List<String> conditionImmunities,
        @Schema(requiredMode = REQUIRED) int normalHpCurrent,
        @Schema(requiredMode = REQUIRED) int normalHpMax,
        @Schema(requiredMode = REQUIRED) int normalArmourClass,
        @Schema(requiredMode = REQUIRED) int druidLevel
) {

    public WildShapeForm {
        saves = saves != null ? Map.copyOf(saves) : Map.of();
        skills = skills != null ? Map.copyOf(skills) : Map.of();
        speed = speed != null ? Collections.unmodifiableMap(new LinkedHashMap<>(speed)) : Map.of();
        damageResistances = damageResistances != null ? List.copyOf(damageResistances) : List.of();
        damageImmunities = damageImmunities != null ? List.copyOf(damageImmunities) : List.of();
        damageVulnerabilities = damageVulnerabilities != null ? List.copyOf(damageVulnerabilities) : List.of();
        conditionImmunities = conditionImmunities != null ? List.copyOf(conditionImmunities) : List.of();
    }

    /** The beast form on the participant's {@code wild shape} entry, or empty when it isn't transformed. */
    public static Optional<WildShapeForm> of(EncounterParticipant participant) {
        return of(participant.getActiveConditions());
    }

    /** The beast form on a {@code wild shape} entry among these conditions, or empty. */
    public static Optional<WildShapeForm> of(List<ConditionEntry> conditions) {
        if (conditions == null) {
            return Optional.empty();
        }
        return conditions.stream()
                .filter(c -> c != null && c.wildShape() != null && BeastShapes.ENTRY.equalsIgnoreCase(c.name()))
                .map(ConditionEntry::wildShape)
                .findFirst();
    }
}
