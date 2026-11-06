package com.tabletophelper.rules;

import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.TestClassTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every class at levels 1–20, from its class row and subclass through {@link SpellSlotCalculator#casterType}
 * to {@link SpellSlotCalculator#calculateSlots}. The expected slots are typed out here from the PHB 2014 class
 * tables (the Artificer's from Tasha's Cauldron of Everything), independently of the class tables in data/content/class-tables.json.
 */
class SpellSlotsEveryClassGoldenTest {

    private static final ClassTables TABLES = TestClassTables.load();

    // Slots by spell level (1st first), one row per class level; row 0 unused.
    // Bard, Cleric, Druid, Sorcerer and Wizard share this table.
    private static final int[][] FULL_CASTER = {
            {},
            {2},                            // 1
            {3},                            // 2
            {4, 2},                         // 3
            {4, 3},                         // 4
            {4, 3, 2},                      // 5
            {4, 3, 3},                      // 6
            {4, 3, 3, 1},                   // 7
            {4, 3, 3, 2},                   // 8
            {4, 3, 3, 3, 1},                // 9
            {4, 3, 3, 3, 2},                // 10
            {4, 3, 3, 3, 2, 1},             // 11
            {4, 3, 3, 3, 2, 1},             // 12
            {4, 3, 3, 3, 2, 1, 1},          // 13
            {4, 3, 3, 3, 2, 1, 1},          // 14
            {4, 3, 3, 3, 2, 1, 1, 1},       // 15
            {4, 3, 3, 3, 2, 1, 1, 1},       // 16
            {4, 3, 3, 3, 2, 1, 1, 1, 1},    // 17
            {4, 3, 3, 3, 3, 1, 1, 1, 1},    // 18
            {4, 3, 3, 3, 3, 2, 1, 1, 1},    // 19
            {4, 3, 3, 3, 3, 2, 2, 1, 1},    // 20
    };

    private static final int[][] PALADIN_RANGER = {
            {},
            {},                             // 1
            {2},                            // 2
            {3},                            // 3
            {3},                            // 4
            {4, 2},                         // 5
            {4, 2},                         // 6
            {4, 3},                         // 7
            {4, 3},                         // 8
            {4, 3, 2},                      // 9
            {4, 3, 2},                      // 10
            {4, 3, 3},                      // 11
            {4, 3, 3},                      // 12
            {4, 3, 3, 1},                   // 13
            {4, 3, 3, 1},                   // 14
            {4, 3, 3, 2},                   // 15
            {4, 3, 3, 2},                   // 16
            {4, 3, 3, 3, 1},                // 17
            {4, 3, 3, 3, 1},                // 18
            {4, 3, 3, 3, 2},                // 19
            {4, 3, 3, 3, 2},                // 20
    };

    private static final int[][] ARTIFICER = {
            {},
            {2},                            // 1
            {2},                            // 2
            {3},                            // 3
            {3},                            // 4
            {4, 2},                         // 5
            {4, 2},                         // 6
            {4, 3},                         // 7
            {4, 3},                         // 8
            {4, 3, 2},                      // 9
            {4, 3, 2},                      // 10
            {4, 3, 3},                      // 11
            {4, 3, 3},                      // 12
            {4, 3, 3, 1},                   // 13
            {4, 3, 3, 1},                   // 14
            {4, 3, 3, 2},                   // 15
            {4, 3, 3, 2},                   // 16
            {4, 3, 3, 3, 1},                // 17
            {4, 3, 3, 3, 1},                // 18
            {4, 3, 3, 3, 2},                // 19
            {4, 3, 3, 3, 2},                // 20
    };

    // Fighter and Rogue levels; the subclass, and with it Spellcasting, arrives at 3rd level
    private static final int[][] ELDRITCH_KNIGHT_ARCANE_TRICKSTER = {
            {},
            {},                             // 1
            {},                             // 2
            {2},                            // 3
            {3},                            // 4
            {3},                            // 5
            {3},                            // 6
            {4, 2},                         // 7
            {4, 2},                         // 8
            {4, 2},                         // 9
            {4, 3},                         // 10
            {4, 3},                         // 11
            {4, 3},                         // 12
            {4, 3, 2},                      // 13
            {4, 3, 2},                      // 14
            {4, 3, 2},                      // 15
            {4, 3, 3},                      // 16
            {4, 3, 3},                      // 17
            {4, 3, 3},                      // 18
            {4, 3, 3, 1},                   // 19
            {4, 3, 3, 1},                   // 20
    };

    // Warlock Pact Magic: {slot count, slot level}, one row per Warlock level
    private static final int[][] WARLOCK = {
            {},
            {1, 1},                         // 1
            {2, 1},                         // 2
            {2, 2},                         // 3
            {2, 2},                         // 4
            {2, 3},                         // 5
            {2, 3},                         // 6
            {2, 4},                         // 7
            {2, 4},                         // 8
            {2, 5},                         // 9
            {2, 5},                         // 10
            {3, 5},                         // 11
            {3, 5},                         // 12
            {3, 5},                         // 13
            {3, 5},                         // 14
            {3, 5},                         // 15
            {3, 5},                         // 16
            {4, 5},                         // 17
            {4, 5},                         // 18
            {4, 5},                         // 19
            {4, 5},                         // 20
    };

    // Flags as CharacterClassLoader sets them: a class with a spellcasting ability is a spellcaster, and only
    // the Warlock has Pact Magic. Fighter and Rogue cast only through their third-caster subclasses.
    static Stream<Arguments> everyClass() {
        return Stream.of(
                //           label               class        spellcaster pact  subclass
                Arguments.of("Artificer",        "Artificer", true,  false, null,               spellcasting(ARTIFICER)),
                Arguments.of("Barbarian",        "Barbarian", false, false, null,               noSlots()),
                Arguments.of("Bard",             "Bard",      true,  false, null,               spellcasting(FULL_CASTER)),
                Arguments.of("Cleric",           "Cleric",    true,  false, null,               spellcasting(FULL_CASTER)),
                Arguments.of("Druid",            "Druid",     true,  false, null,               spellcasting(FULL_CASTER)),
                Arguments.of("Fighter",          "Fighter",   false, false, "Champion",         noSlots()),
                Arguments.of("Eldritch Knight",  "Fighter",   false, false, "Eldritch Knight",
                        spellcasting(ELDRITCH_KNIGHT_ARCANE_TRICKSTER)),
                Arguments.of("Monk",             "Monk",      false, false, null,               noSlots()),
                Arguments.of("Paladin",          "Paladin",   true,  false, null,               spellcasting(PALADIN_RANGER)),
                Arguments.of("Ranger",           "Ranger",    true,  false, null,               spellcasting(PALADIN_RANGER)),
                Arguments.of("Rogue",            "Rogue",     false, false, "Thief",            noSlots()),
                Arguments.of("Arcane Trickster", "Rogue",     false, false, "Arcane Trickster",
                        spellcasting(ELDRITCH_KNIGHT_ARCANE_TRICKSTER)),
                Arguments.of("Sorcerer",         "Sorcerer",  true,  false, null,               spellcasting(FULL_CASTER)),
                Arguments.of("Warlock",          "Warlock",   true,  true,  null,               pactMagic(WARLOCK)),
                Arguments.of("Wizard",           "Wizard",    true,  false, null,               spellcasting(FULL_CASTER))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Each class's spell slots match its class table at levels 1–20")
    void slotsAtEveryLevel(String label, String className, boolean isSpellcaster, boolean isPactMagic,
                           String subclassName, List<Map<String, Integer>> phbSlots) {
        String casterType = SpellSlotCalculator.casterType(className, isSpellcaster, isPactMagic, subclassName);
        Stream<Executable> levels = IntStream.rangeClosed(1, 20).mapToObj(level -> () ->
                assertEquals(phbSlots.get(level), slotsAt(className, casterType, level), label + " " + level));
        assertAll(levels);
    }

    // CharacterService gives the calculator no entry for a class whose caster type is "none"
    private static Map<String, Integer> slotsAt(String className, String casterType, int level) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
        if (!"none".equals(casterType)) {
            entries.put(className, new SpellSlotCalculator.ClassEntry(level, casterType));
        }
        return SpellSlotCalculator.calculateSlots(TABLES, entries);
    }

    // Expected slots indexed by class level, keyed as the calculator keys them: "1".."9" and "pact_N"

    private static List<Map<String, Integer>> spellcasting(int[][] table) {
        List<Map<String, Integer>> byLevel = new ArrayList<>();
        for (int[] row : table) {
            Map<String, Integer> slots = new LinkedHashMap<>();
            for (int i = 0; i < row.length; i++) slots.put(String.valueOf(i + 1), row[i]);
            byLevel.add(slots);
        }
        return byLevel;
    }

    private static List<Map<String, Integer>> pactMagic(int[][] table) {
        List<Map<String, Integer>> byLevel = new ArrayList<>();
        for (int[] row : table) {
            byLevel.add(row.length == 0 ? Map.of() : Map.of("pact_" + row[1], row[0]));
        }
        return byLevel;
    }

    private static List<Map<String, Integer>> noSlots() {
        return spellcasting(new int[21][0]);
    }
}
