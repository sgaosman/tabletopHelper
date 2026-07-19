package com.tabletophelper.character;

import java.util.LinkedHashMap;
import java.util.Map;

public class SpellSlotCalculator {

    private static final int[][] FULL_CASTER_TABLE = {
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

    private static final int[][] PACT_MAGIC_TABLE = {
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

    public static Map<String, Integer> calculateSlots(Map<String, ClassEntry> classEntries) {
        int multiclassCasterLevel = 0;
        int warlockLevel = 0;

        for (var entry : classEntries.entrySet()) {
            ClassEntry ce = entry.getValue();
            int level = ce.level();
            switch (ce.casterType()) {
                case "full" -> multiclassCasterLevel += level;
                case "half" -> multiclassCasterLevel += level / 2;
                case "artificer" -> multiclassCasterLevel += (level + 1) / 2;
                case "pact" -> warlockLevel = level;
            }
        }

        Map<String, Integer> slots = new LinkedHashMap<>();

        if (multiclassCasterLevel > 0 && multiclassCasterLevel <= 20) {
            int[] row = FULL_CASTER_TABLE[multiclassCasterLevel];
            for (int i = 0; i < row.length; i++) {
                if (row[i] > 0) {
                    slots.put(String.valueOf(i + 1), row[i]);
                }
            }
        }

        if (warlockLevel > 0 && warlockLevel <= 20) {
            int[] pactRow = PACT_MAGIC_TABLE[warlockLevel];
            for (int i = 0; i < pactRow.length; i++) {
                if (pactRow[i] > 0) {
                    String key = "pact_" + (i + 1);
                    slots.put(key, pactRow[i]);
                }
            }
        }

        return slots;
    }

    public record ClassEntry(int level, String casterType) {}
}
