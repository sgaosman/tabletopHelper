package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One character level as it was gained, in {@code player_characters.level_history}, oldest first (D168). Levelling
 * down removes the last entry and reverses what it records.
 *
 * @param classId the class the level was taken in; null only for a character created without a class
 * @param choices what was chosen at this level; empty when nothing was
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelHistoryEntry(
        @Schema(requiredMode = REQUIRED) int characterLevel,
        UUID classId,
        String className,
        @Schema(requiredMode = REQUIRED) int classLevel,
        @Schema(requiredMode = REQUIRED) int hpGained,
        List<Feature> featuresGained,
        LevelChoices choices
) {

    public LevelHistoryEntry withChoices(LevelChoices newChoices) {
        return new LevelHistoryEntry(characterLevel, classId, className, classLevel, hpGained, featuresGained,
                newChoices);
    }
}
