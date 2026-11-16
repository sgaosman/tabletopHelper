package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What a level-down took away spent, in {@code player_characters.level_down_uses} (V40, D222): the pool uses, slots
 * and Hit Dice that the level's removal left the sheet without, because their pool, slot level or class went or their
 * maximum fell. Gaining the same character level again spends them again, so a level removed and gained back leaves
 * them as spent as before. A long rest drops every entry, since it restores them all; a short rest drops the
 * short-rest pools' and the pact slots' counts.
 *
 * @param characterLevel the character level that was removed
 * @param pools          uses spent by pool ID, of the pools a short rest doesn't restore
 * @param shortRestPools uses spent by pool ID, of the pools a short rest restores
 * @param slots          slots used by spell level, "1" to "9", and "pact" for a warlock's pact slots
 * @param hitDice        Hit Dice spent by class name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelDownUses(
        @Schema(requiredMode = REQUIRED) int characterLevel,
        Map<String, Integer> pools,
        Map<String, Integer> shortRestPools,
        Map<String, Integer> slots,
        Map<String, Integer> hitDice
) {

    /** The key of the pact slots, whose level changes as a warlock levels. */
    public static final String PACT = "pact";

    public LevelDownUses {
        pools = pools != null ? Map.copyOf(pools) : Map.of();
        shortRestPools = shortRestPools != null ? Map.copyOf(shortRestPools) : Map.of();
        slots = slots != null ? Map.copyOf(slots) : Map.of();
        hitDice = hitDice != null ? Map.copyOf(hitDice) : Map.of();
    }

    /** Whether it keeps nothing spent. */
    public boolean isEmpty() {
        return pools.isEmpty() && shortRestPools.isEmpty() && slots.isEmpty() && hitDice.isEmpty();
    }
}
