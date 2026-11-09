package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.character.FontOfMagic;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A sorcerer's Flexible Casting in an encounter (PHB p.101, M18), a bonus action on their turn: a spell slot expended
 * for sorcery points, or sorcery points spent to create a slot of 1st to 5th level.
 *
 * @param participantId the sorcerer
 * @param direction     which way the exchange goes
 * @param slotLevel     the level of the slot expended or created
 */
public record FontOfMagicUseRequest(
        @NotNull UUID participantId,
        @NotNull FontOfMagic.Direction direction,
        @NotNull @Min(1) @Max(9) Integer slotLevel
) {
}
