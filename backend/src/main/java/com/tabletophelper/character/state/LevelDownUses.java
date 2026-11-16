package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What a level-down took away spent, in {@code player_characters.level_down_uses} (V40, D222): the pool uses, slots
 * and Hit Dice that the level's removal left the sheet without, because their pool, slot level or class went or their
 * maximum fell. Gaining the same character level again spends them again, so a level removed and gained back leaves
 * them as spent as before. A short rest drops the short-rest pools' and the pact slots' counts. A long rest drops the
 * slots and the pools it fills, but only as many Hit Dice as it would have restored at the character's full level, and
 * keeps a pool that resets to an amount, such as Power Surge, as spent as the rest leaves it at that level (D234,
 * amending D222). The fields D234 added are left out of the JSON when empty, so rows written before read back the same.
 *
 * @param characterLevel the character level that was removed
 * @param pools          uses spent by pool ID, of the pools a short rest doesn't restore
 * @param shortRestPools uses spent by pool ID, of the pools a short rest restores
 * @param slots          slots used by spell level, "1" to "9", and "pact" for a warlock's pact slots
 * @param hitDice        Hit Dice spent by class name
 * @param hitDieSizes    each class's Hit Die size, for the classes in {@code hitDice}, so a long rest can restore the
 *                       largest dice first though the class has gone from the sheet (D234)
 * @param poolResets     the pools in {@code pools} that a long rest doesn't simply fill, with their maximum before the
 *                       level-down: a long-rest pool with a reset amount, such as Power Surge, or one no rest restores.
 *                       Such a pool is kept here even with no use spent, since a long rest can leave it spent (D234)
 * @param hpShortfall    the hit points the lower maximum would have taken off below 0, which the sheet couldn't take;
 *                       taken off again when the level comes back, so its raise isn't the whole increase (D234,
 *                       amending D222). A long rest clears it, since it restores every hit point
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelDownUses(
        @Schema(requiredMode = REQUIRED) int characterLevel,
        Map<String, Integer> pools,
        Map<String, Integer> shortRestPools,
        Map<String, Integer> slots,
        Map<String, Integer> hitDice,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Integer> hitDieSizes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, PoolReset> poolResets,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int hpShortfall
) {

    /** The key of the pact slots, whose level changes as a warlock levels. */
    public static final String PACT = "pact";

    public LevelDownUses {
        pools = pools != null ? Map.copyOf(pools) : Map.of();
        shortRestPools = shortRestPools != null ? Map.copyOf(shortRestPools) : Map.of();
        slots = slots != null ? Map.copyOf(slots) : Map.of();
        hitDice = hitDice != null ? Map.copyOf(hitDice) : Map.of();
        hitDieSizes = hitDieSizes != null ? Map.copyOf(hitDieSizes) : Map.of();
        poolResets = poolResets != null ? Map.copyOf(poolResets) : Map.of();
        hpShortfall = Math.max(0, hpShortfall);
    }

    /** Whether it keeps nothing spent. */
    public boolean isEmpty() {
        return pools.isEmpty() && shortRestPools.isEmpty() && slots.isEmpty() && hitDice.isEmpty()
                && poolResets.isEmpty() && hpShortfall == 0;
    }

    /**
     * How a long rest treats a recorded pool it doesn't simply fill (D234).
     *
     * @param maxUses     the pool's maximum before the level-down
     * @param resetOn     the pool's rest: "longRest" for one with a reset amount, or "none" or "never" for one no rest
     *                    restores
     * @param resetAmount the uses a long rest leaves it with, such as Power Surge's "1"; null for one no rest restores
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PoolReset(int maxUses, String resetOn, String resetAmount) {}
}
