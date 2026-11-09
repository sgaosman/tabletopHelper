package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A Circle of the Moon druid's Combat Wild Shape healing (PHB p.69): while transformed, a bonus action that expends a
 * spell slot to regain 1d8 hit points per level of the slot.
 *
 * @param participantId the druid
 * @param slotLevel     the level of the slot expended
 * @param pactSlot      true to expend a pact slot of that level; a pact slot is also used when it is the only slot of
 *                      the level there is
 */
public record WildShapeHealRequest(
        @NotNull UUID participantId,
        @NotNull @Min(1) @Max(9) Integer slotLevel,
        Boolean pactSlot
) {
}
