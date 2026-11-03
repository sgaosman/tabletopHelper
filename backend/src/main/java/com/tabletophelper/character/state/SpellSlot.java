package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The slots of one spell level, keyed "1" to "9", and a Warlock's pact slots "pact_N" (D168). Character sheets
 * keep them in {@code player_characters.spell_slots} and participants in
 * {@code encounter_participants.spell_slots_current}, in this one shape. Storing the used count rather than the
 * remaining one means raising the maximum leaves it alone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
