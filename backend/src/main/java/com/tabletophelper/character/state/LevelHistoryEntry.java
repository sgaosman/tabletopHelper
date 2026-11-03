package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One character level as it was gained, in {@code player_characters.level_history}, oldest first (D168). The hit
 * point maximum is worked out from the hit die results (D167), and levelling down removes the last entry and reverses
 * only what it records. A field an entry doesn't have is left out.
 *
 * @param classId             the class the level was taken in; null only for a character created without a class
 * @param hpGained            what the level added to the hit point maximum when it was gained; the maximum itself is
 *                            worked out from the hit die results, so this is a record only
 * @param hitDieResult        the Hit Die Result: the die's maximum at character level 1 and its fixed average after,
 *                            a multiclass dip's first level included (PHB p.163). V20 filled it in for the levels
 *                            recorded before; one still without it counts as the fixed value
 * @param choices             what was chosen at this level; empty when nothing was
 * @param proficienciesGained the proficiencies multiclassing into the class added
 * @param abilityIncreases    increases the level itself applied, spelled out ("strength"), at the amount actually
 *                            applied: Primal Champion's at Barbarian 20th level
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelHistoryEntry(
        @Schema(requiredMode = REQUIRED) int characterLevel,
        UUID classId,
        String className,
        @Schema(requiredMode = REQUIRED) int classLevel,
        @Schema(requiredMode = REQUIRED) int hpGained,
        Integer hitDieResult,
        List<Feature> featuresGained,
        LevelChoices choices,
        ProficienciesGained proficienciesGained,
        List<AbilityBonus> abilityIncreases
) {

    public LevelHistoryEntry withChoices(LevelChoices newChoices) {
        return new LevelHistoryEntry(characterLevel, classId, className, classLevel, hpGained, hitDieResult,
                featuresGained, newChoices, proficienciesGained, abilityIncreases);
    }

    public LevelHistoryEntry withHpGained(int newHpGained) {
        return new LevelHistoryEntry(characterLevel, classId, className, classLevel, newHpGained, hitDieResult,
                featuresGained, choices, proficienciesGained, abilityIncreases);
    }
}
