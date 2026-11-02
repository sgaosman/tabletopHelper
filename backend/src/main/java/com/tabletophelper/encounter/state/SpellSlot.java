package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The slots of one spell level in {@code encounter_participants.spell_slots_current}, which keys them
 * "1" to "9", and a Warlock's pact slots "pact_N" (D168). Storing the used count rather than the
 * remaining one means raising the maximum leaves it alone.
 */
public record SpellSlot(
        @Schema(requiredMode = REQUIRED) int max,
        @Schema(requiredMode = REQUIRED) int used
) {

    public int remaining() {
        return max - used;
    }

    public SpellSlot spend() {
        return new SpellSlot(max, used + 1);
    }

    public SpellSlot restore() {
        return new SpellSlot(max, used - 1);
    }
}
