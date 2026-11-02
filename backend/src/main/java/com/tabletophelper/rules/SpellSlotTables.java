package com.tabletophelper.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHB 2014 spell slot progressions, shared by {@link SpellSlotCalculator} and the class seeder (D153).
 * Row index is the class level (or combined caster level for the multiclass table); each row lists
 * slot counts by spell level starting at 1st. Pact Magic rows hold the count at the slot's level.
 */
public final class SpellSlotTables {

    private SpellSlotTables() {}

    /** Bard, Cleric, Druid, Sorcerer, Wizard — also the multiclass table by combined caster level. */
    private static final int[][] FULL_CASTER = {
            {},
            {2},
            {3},
            {4, 2},
            {4, 3},
            {4, 3, 2},
            {4, 3, 3},
            {4, 3, 3, 1},
            {4, 3, 3, 2},
            {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 2},
            {4, 3, 3, 3, 2, 1},
            {4, 3, 3, 3, 2, 1},
            {4, 3, 3, 3, 2, 1, 1},
            {4, 3, 3, 3, 2, 1, 1},
            {4, 3, 3, 3, 2, 1, 1, 1},
            {4, 3, 3, 3, 2, 1, 1, 1},
            {4, 3, 3, 3, 2, 1, 1, 1, 1},
            {4, 3, 3, 3, 3, 1, 1, 1, 1},
            {4, 3, 3, 3, 3, 2, 1, 1, 1},
            {4, 3, 3, 3, 3, 2, 2, 1, 1},
    };

    /** Paladin and Ranger. */
    private static final int[][] HALF_CASTER = {
            {},
            {},
            {2},
            {3},
            {3},
            {4, 2},
            {4, 2},
            {4, 3},
            {4, 3},
            {4, 3, 2},
            {4, 3, 2},
            {4, 3, 3},
            {4, 3, 3},
            {4, 3, 3, 1},
            {4, 3, 3, 1},
            {4, 3, 3, 2},
            {4, 3, 3, 2},
            {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 2},
            {4, 3, 3, 3, 2},
    };

    private static final int[][] ARTIFICER = {
            {},
            {2},
            {2},
            {3},
            {3},
            {4, 2},
            {4, 2},
            {4, 3},
            {4, 3},
            {4, 3, 2},
            {4, 3, 2},
            {4, 3, 3},
            {4, 3, 3},
            {4, 3, 3, 1},
            {4, 3, 3, 1},
            {4, 3, 3, 2},
            {4, 3, 3, 2},
            {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 2},
            {4, 3, 3, 3, 2},
    };

    /** Eldritch Knight and Arcane Trickster. */
    private static final int[][] THIRD_CASTER = {
            {},
            {},
            {},
            {2},
            {3},
            {3},
            {3},
            {4, 2},
            {4, 2},
            {4, 2},
            {4, 3},
            {4, 3},
            {4, 3},
            {4, 3, 2},
            {4, 3, 2},
            {4, 3, 2},
            {4, 3, 3},
            {4, 3, 3},
            {4, 3, 3},
            {4, 3, 3, 1},
            {4, 3, 3, 1},
    };

    /** Warlock. */
    private static final int[][] PACT_MAGIC = {
            {},
            {1},
            {2},
            {0, 2},
            {0, 2},
            {0, 0, 2},
            {0, 0, 2},
            {0, 0, 0, 2},
            {0, 0, 0, 2},
            {0, 0, 0, 0, 2},
            {0, 0, 0, 0, 2},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 4},
            {0, 0, 0, 0, 4},
            {0, 0, 0, 0, 4},
            {0, 0, 0, 0, 4},
    };

    /** Slots for one class on its own table at its class level; empty if it has none yet. */
    public static int[] ownTable(String casterType, int level) {
        return row(tableFor(casterType), level);
    }

    /** The multiclass table row for a combined caster level. */
    public static int[] multiclassRow(int casterLevel) {
        return row(FULL_CASTER, casterLevel);
    }

    /** Pact Magic slots at a Warlock level, indexed by slot level. */
    public static int[] pactMagicRow(int warlockLevel) {
        return row(PACT_MAGIC, warlockLevel);
    }

    /**
     * Seeder format: {"classLevel": {"spellLevel": count}}, leaving out levels without slots.
     * Returns null for caster types without a table.
     */
    public static Map<String, Map<String, Integer>> progression(String casterType) {
        int[][] table = tableFor(casterType);
        if (table.length == 0) return null;
        Map<String, Map<String, Integer>> progression = new LinkedHashMap<>();
        for (int level = 1; level < table.length; level++) {
            Map<String, Integer> slots = new LinkedHashMap<>();
            for (int i = 0; i < table[level].length; i++) {
                if (table[level][i] > 0) slots.put(String.valueOf(i + 1), table[level][i]);
            }
            if (!slots.isEmpty()) progression.put(String.valueOf(level), slots);
        }
        return progression;
    }

    private static int[][] tableFor(String casterType) {
        return switch (casterType) {
            case "full" -> FULL_CASTER;
            case "half" -> HALF_CASTER;
            case "artificer" -> ARTIFICER;
            case "third" -> THIRD_CASTER;
            case "pact" -> PACT_MAGIC;
            default -> new int[0][];
        };
    }

    private static int[] row(int[][] table, int level) {
        return level > 0 && level < table.length ? table[level] : new int[0];
    }
}
