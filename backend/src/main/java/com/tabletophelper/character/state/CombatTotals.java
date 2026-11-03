package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The totals combat changes on a player character, which Write-back applies to its sheet as changes (D166): its hit
 * points, its temporary hit points, each spell slot's used count, keyed "1" to "9" and "pact_N" as spell slots are,
 * and each resource pool's current uses, keyed by pool ID. A participant keeps the totals it joined with in
 * {@code encounter_participants.joined_with} (V24), and the encounter's results set them beside the totals it ended
 * with.
 *
 * @param hpCurrent        hit points, from 0 to the maximum
 * @param hpTemp           temporary hit points
 * @param slotsUsed        how many of each level's slots are spent
 * @param poolsCurrentUses how many uses each pool has left
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CombatTotals(
        int hpCurrent,
        int hpTemp,
        Map<String, Integer> slotsUsed,
        Map<String, Integer> poolsCurrentUses
) {

    public CombatTotals {
        slotsUsed = withoutNulls(slotsUsed);
        poolsCurrentUses = withoutNulls(poolsCurrentUses);
    }

    /**
     * The totals of a sheet or a participant: its slots' used counts, each kept between 0 and the slot's maximum, and
     * its pools' current uses, the first of two pools with one ID counting.
     */
    public static CombatTotals of(int hpCurrent, int hpTemp, Map<String, SpellSlot> slots,
                                  List<ResourcePoolEntry> pools) {
        Map<String, Integer> slotsUsed = new LinkedHashMap<>();
        if (slots != null) {
            slots.forEach((key, slot) -> {
                if (slot != null) {
                    slotsUsed.put(key, Math.max(0, Math.min(slot.max(), slot.used())));
                }
            });
        }
        Map<String, Integer> poolsCurrentUses = new LinkedHashMap<>();
        if (pools != null) {
            for (ResourcePoolEntry pool : pools) {
                if (pool != null && pool.poolId() != null) {
                    poolsCurrentUses.putIfAbsent(pool.poolId(), pool.currentUses());
                }
            }
        }
        return new CombatTotals(hpCurrent, Math.max(0, hpTemp), slotsUsed, poolsCurrentUses);
    }

    // A key without a number, which no code writes, counts as absent
    private static Map<String, Integer> withoutNulls(Map<String, Integer> totals) {
        if (totals == null) {
            return Map.of();
        }
        Map<String, Integer> kept = new LinkedHashMap<>();
        totals.forEach((key, value) -> {
            if (key != null && value != null) {
                kept.put(key, value);
            }
        });
        return Collections.unmodifiableMap(kept);
    }
}
