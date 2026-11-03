package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A character's ability scores without its DM Adjustments (D158): the scores its race, Ability Score Improvements and
 * feats give it, which an Ability Score Improvement raises only as far as the Ability Score Maximum (D167). The
 * character's scores, with the adjustments, are the ones everything else is worked out from.
 */
public record BaseAbilityScores(
        @Schema(requiredMode = REQUIRED) int strength,
        @Schema(requiredMode = REQUIRED) int dexterity,
        @Schema(requiredMode = REQUIRED) int constitution,
        @Schema(requiredMode = REQUIRED) int intelligence,
        @Schema(requiredMode = REQUIRED) int wisdom,
        @Schema(requiredMode = REQUIRED) int charisma
) {
}
