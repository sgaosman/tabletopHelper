package com.tabletophelper.rules;

import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.TestClassTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks every caster type at every level against the PHB 2014 tables, typed out here
 * independently of the class tables in data/content/class-tables.json (D153).
 */
class SpellSlotCalculatorGoldenTest {

    private static final ClassTables TABLES = TestClassTables.load();

    // Slots by spell level (1st first), indexed by class level; row 0 unused
    private static final int[][] PHB_FULL = {
            {}, {2}, {3}, {4, 2}, {4, 3}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 2},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1},
            {4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1},
            {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1, 1}, {4, 3, 3, 3, 3, 1, 1, 1, 1},
            {4, 3, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 2, 1, 1},
    };
    private static final int[][] PHB_PALADIN_RANGER = {
            {}, {}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
            {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2},
    };
    private static final int[][] ARTIFICER = {
            {}, {2}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
            {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2},
    };
    private static final int[][] PHB_ELDRITCH_KNIGHT = {
            {}, {}, {}, {2}, {3}, {3}, {3}, {4, 2}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3},
            {4, 3, 2}, {4, 3, 2}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1},
    };
    // Warlock: {slot count, slot level}
    private static final int[][] PHB_PACT = {
            {}, {1, 1}, {2, 1}, {2, 2}, {2, 2}, {2, 3}, {2, 3}, {2, 4}, {2, 4}, {2, 5}, {2, 5},
            {3, 5}, {3, 5}, {3, 5}, {3, 5}, {3, 5}, {3, 5}, {4, 5}, {4, 5}, {4, 5}, {4, 5},
    };

    @ParameterizedTest(name = "Wizard {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void fullCaster(int level) {
        assertEquals(expected(PHB_FULL[level]), single("Wizard", level, "full"));
    }

    @ParameterizedTest(name = "Paladin {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void halfCaster(int level) {
        assertEquals(expected(PHB_PALADIN_RANGER[level]), single("Paladin", level, "half"));
    }

    @ParameterizedTest(name = "Artificer {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void artificer(int level) {
        assertEquals(expected(ARTIFICER[level]), single("Artificer", level, "artificer"));
    }

    // Eldritch Knight entries only exist from Fighter 3, when the subclass is chosen
    @ParameterizedTest(name = "Eldritch Knight {0}")
    @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void thirdCaster(int level) {
        assertEquals(expected(PHB_ELDRITCH_KNIGHT[level]), single("Eldritch Knight", level, "third"));
    }

    @ParameterizedTest(name = "Warlock {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void pactMagic(int level) {
        Map<String, Integer> expected = Map.of("pact_" + PHB_PACT[level][1], PHB_PACT[level][0]);
        assertEquals(expected, single("Warlock", level, "pact"));
    }

    @Test
    @DisplayName("Paladin 1 has not gained Spellcasting, so Paladin 1 / Sorcerer 3 uses the Sorcerer table")
    void paladinOneDoesNotCount() {
        assertEquals(expected(PHB_FULL[3]), multi(
                "Paladin", new SpellSlotCalculator.ClassEntry(1, "half"),
                "Sorcerer", new SpellSlotCalculator.ClassEntry(3, "full")));
    }

    @Test
    @DisplayName("Paladin 2 / Sorcerer 3 uses the multiclass table at caster level 4")
    void paladinSorcerer() {
        assertEquals(expected(PHB_FULL[4]), multi(
                "Paladin", new SpellSlotCalculator.ClassEntry(2, "half"),
                "Sorcerer", new SpellSlotCalculator.ClassEntry(3, "full")));
    }

    @Test
    @DisplayName("Pact Magic is not Spellcasting: Paladin 5 / Warlock 2 uses the Paladin table plus pact slots")
    void paladinWarlock() {
        Map<String, Integer> expected = expected(PHB_PALADIN_RANGER[5]);
        expected.put("pact_1", 2);
        assertEquals(expected, multi(
                "Paladin", new SpellSlotCalculator.ClassEntry(5, "half"),
                "Warlock", new SpellSlotCalculator.ClassEntry(2, "pact")));
    }

    @Test
    @DisplayName("Eldritch Knight 3 / Wizard 2 uses the multiclass table at caster level 3")
    void eldritchKnightWizard() {
        assertEquals(expected(PHB_FULL[3]), multi(
                "Eldritch Knight", new SpellSlotCalculator.ClassEntry(3, "third"),
                "Wizard", new SpellSlotCalculator.ClassEntry(2, "full")));
    }

    @Test
    @DisplayName("Artificer 1 / Wizard 1 rounds the Artificer up: caster level 2")
    void artificerWizard() {
        assertEquals(expected(PHB_FULL[2]), multi(
                "Artificer", new SpellSlotCalculator.ClassEntry(1, "artificer"),
                "Wizard", new SpellSlotCalculator.ClassEntry(1, "full")));
    }

    private static Map<String, Integer> single(String name, int level, String casterType) {
        return SpellSlotCalculator.calculateSlots(TABLES, Map.of(name, new SpellSlotCalculator.ClassEntry(level, casterType)));
    }

    private static Map<String, Integer> multi(String nameA, SpellSlotCalculator.ClassEntry a,
                                              String nameB, SpellSlotCalculator.ClassEntry b) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
        entries.put(nameA, a);
        entries.put(nameB, b);
        return SpellSlotCalculator.calculateSlots(TABLES, entries);
    }

    private static Map<String, Integer> expected(int[] row) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) slots.put(String.valueOf(i + 1), row[i]);
        return slots;
    }
}
