package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Divine Smite on the paladin's latest melee weapon hit (PHB p.85, M17), chosen once the hit is known, on the same turn.
 *
 * @param participantId the paladin
 * @param slotLevel     the level of the spell slot spent, 1 to 9
 * @param pactSlot      spend a pact slot of that level rather than a spell slot; left out, a spell slot of that level,
 *                      or a pact slot when the paladin has only that
 * @param attackLogId   the hit's combat log entry, as the paladin's turn economy names it; when given, a smite on any
 *                      other hit is refused, so a smite never lands on an attack the player didn't see
 */
public record DivineSmiteRequest(
        @NotNull UUID participantId,
        @NotNull @Min(1) @Max(9) Integer slotLevel,
        Boolean pactSlot,
        UUID attackLogId
) {
}
