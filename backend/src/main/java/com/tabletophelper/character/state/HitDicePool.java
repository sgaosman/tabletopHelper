package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A character's Hit Dice from one class, in {@code player_characters.hit_dice}, which keys them by class name
 * (D158, D168). V19 filled the column from the deprecated {@code hit_dice_map}, whose {@code faces} is now
 * {@code dieSize}. No resource pool holds hit dice.
 *
 * @param dieSize   the class's Hit Die: 6, 8, 10 or 12
 * @param total     one for each level in the class
 * @param remaining how many of those are left to spend
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HitDicePool(
        @Schema(requiredMode = REQUIRED) int dieSize,
        @Schema(requiredMode = REQUIRED) int total,
        @Schema(requiredMode = REQUIRED) int remaining
) {
}
