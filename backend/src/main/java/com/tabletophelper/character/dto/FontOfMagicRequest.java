package com.tabletophelper.character.dto;

import com.tabletophelper.rules.character.FontOfMagic;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One use of a sorcerer's Flexible Casting on the character sheet (PHB p.101, M18): a spell slot expended for sorcery
 * points, or sorcery points spent to create a slot of 1st to 5th level.
 *
 * @param direction which way the exchange goes
 * @param slotLevel the level of the slot expended or created
 */
public record FontOfMagicRequest(
        @NotNull FontOfMagic.Direction direction,
        @NotNull @Min(1) @Max(9) Integer slotLevel
) {
}
