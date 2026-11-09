package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The slots of one spell level, keyed "1" to "9", and a Warlock's pact slots "pact_N" (D168). Character sheets
 * keep them in {@code player_characters.spell_slots} and participants in
 * {@code encounter_participants.spell_slots_current}, in this one shape. Storing the used count rather than the
 * remaining one means raising the maximum leaves it alone.
 *
 * @param max     the slots the character's levels give
 * @param used    how many of max and created are spent
 * @param created the slots a sorcerer's Flexible Casting made from sorcery points (PHB p.101, M18), which vanish on
 *                a long rest; a level with none from the class tables can hold created ones alone. Written only when
 *                more than 0, so a slot stored before it existed reads back unchanged, as 0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpellSlot(
        @Schema(requiredMode = REQUIRED) int max,
        @Schema(requiredMode = REQUIRED) int used,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int created
) {

    /** Slots with none created. */
    public SpellSlot(int max, int used) {
        this(max, used, 0);
    }

    /** Every slot of the level: those the levels give and those created. */
    public int total() {
        return max + created;
    }

    public int remaining() {
        return total() - used;
    }

    public SpellSlot spend() {
        return new SpellSlot(max, used + 1, created);
    }

    public SpellSlot restore() {
        return new SpellSlot(max, used - 1, created);
    }

    /** One more slot made by Flexible Casting (PHB p.101). */
    public SpellSlot create() {
        return new SpellSlot(max, used, created + 1);
    }

    /** The same slots with the used count kept between 0 and the total. */
    public SpellSlot clamped() {
        return new SpellSlot(max, Math.max(0, Math.min(total(), used)), Math.max(0, created));
    }
}
