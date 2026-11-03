package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A short rest taken (D158): the character as the rest leaves it, and what each Hit Die spent rolled, so the player
 * sees the rolls the server made.
 *
 * @param character the rested character
 * @param rolls     each Hit Die spent, in the order the request named them; none for a rest that spent no dice
 */
public record ShortRestResponse(
        @Schema(requiredMode = REQUIRED) CharacterResponse character,
        @Schema(requiredMode = REQUIRED) List<HitDieRoll> rolls
) {

    /**
     * One Hit Die spent.
     *
     * @param className      the class the die is from, as the sheet's Hit Dice name it
     * @param dieSize        the die's size, such as 10 for a d10
     * @param roll           its roll, from 1 to the die's size
     * @param rolledByServer true when the server rolled it, false when the player entered the roll
     * @param healing        the hit points it heals: the roll plus the Constitution modifier, never less than 0 (PHB
     *                       p.186). Hit points stop at the maximum, so the dice can heal more than the character regains
     */
    public record HitDieRoll(
            @Schema(requiredMode = REQUIRED) String className,
            @Schema(requiredMode = REQUIRED) int dieSize,
            @Schema(requiredMode = REQUIRED) int roll,
            @Schema(requiredMode = REQUIRED) boolean rolledByServer,
            @Schema(requiredMode = REQUIRED) int healing
    ) {
    }
}
