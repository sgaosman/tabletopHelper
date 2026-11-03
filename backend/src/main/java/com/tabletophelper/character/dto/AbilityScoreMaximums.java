package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The highest each of a character's ability scores can be increased to (D167): 20, or 24 for Strength and
 * Constitution once a barbarian has Primal Champion (PHB p.49). An Ability Score Improvement past it is refused.
 */
public record AbilityScoreMaximums(
        @Schema(requiredMode = REQUIRED) int strength,
        @Schema(requiredMode = REQUIRED) int dexterity,
        @Schema(requiredMode = REQUIRED) int constitution,
        @Schema(requiredMode = REQUIRED) int intelligence,
        @Schema(requiredMode = REQUIRED) int wisdom,
        @Schema(requiredMode = REQUIRED) int charisma
) {
}
