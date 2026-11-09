package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param slotsCreated     how many of each level's slots Flexible Casting created (PHB p.101, M18), for the levels with
 *                         any; written only when there are some, so totals stored before it existed read as none
 * @param exhaustionLevel  the exhaustion level, 1 to 6 (PHB p.291, D183); null for none, and written only when there
 *                         is some, so totals stored before it existed read as none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CombatTotals(
        int hpCurrent,
        int hpTemp,
        Map<String, Integer> slotsUsed,
        Map<String, Integer> poolsCurrentUses,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Integer> slotsCreated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer exhaustionLevel
) {

    public CombatTotals {
        slotsUsed = withoutNulls(slotsUsed);
        poolsCurrentUses = withoutNulls(poolsCurrentUses);
        slotsCreated = withoutNulls(slotsCreated);
        exhaustionLevel = exhaustionLevel == null || exhaustionLevel <= 0 ? null : Math.min(6, exhaustionLevel);
    }

    /** Totals with no exhaustion. */
    public CombatTotals(int hpCurrent, int hpTemp, Map<String, Integer> slotsUsed,
                        Map<String, Integer> poolsCurrentUses, Map<String, Integer> slotsCreated) {
        this(hpCurrent, hpTemp, slotsUsed, poolsCurrentUses, slotsCreated, null);
    }

    /** The exhaustion level, 0 for none. */
    @JsonIgnore
    public int exhaustion() {
        return exhaustionLevel != null ? exhaustionLevel : 0;
    }

    /** The same totals with this exhaustion level. */
    public CombatTotals withExhaustion(int level) {
        return new CombatTotals(hpCurrent, hpTemp, slotsUsed, poolsCurrentUses, slotsCreated, level);
    }

    /** Totals with no created slots. */
    public CombatTotals(int hpCurrent, int hpTemp, Map<String, Integer> slotsUsed,
                        Map<String, Integer> poolsCurrentUses) {
        this(hpCurrent, hpTemp, slotsUsed, poolsCurrentUses, Map.of(), null);
    }

    /**
     * The totals of a sheet or a participant: its slots' used counts, each kept between 0 and the level's total, the
     * slots created at each level that has any, and its pools' current uses, the first of two pools with one ID
     * counting.
     */
    public static CombatTotals of(int hpCurrent, int hpTemp, Map<String, SpellSlot> slots,
                                  List<ResourcePoolEntry> pools) {
        Map<String, Integer> slotsUsed = new LinkedHashMap<>();
        Map<String, Integer> slotsCreated = new LinkedHashMap<>();
        if (slots != null) {
            slots.forEach((key, slot) -> {
                if (slot != null) {
                    SpellSlot kept = slot.clamped();
                    slotsUsed.put(key, kept.used());
                    if (kept.created() > 0) {
                        slotsCreated.put(key, kept.created());
                    }
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
        return new CombatTotals(hpCurrent, Math.max(0, hpTemp), slotsUsed, poolsCurrentUses, slotsCreated);
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
