package com.tabletophelper.rules.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.ClassTablesFile;
import com.tabletophelper.rules.character.ClassTables.Preparing;
import com.tabletophelper.rules.character.ClassTables.Spellcasting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * data/content/class-tables.json holds exactly the tables that were Java until R7 workstream E (rules/SpellSlotTables
 * and ClassProgression's columns), typed out here from the PHB's and Tasha's class tables: before the Java went, a
 * comparison of every answer of the old and new code, 17,312 ClassProgression answers and 8,602 slot rows and slot
 * calculations, found no difference. ClassProgressionGoldenTest, SpellSlotsEveryClassGoldenTest and
 * PendingChoicesTest check the rules over these tables.
 */
class ClassTablesFileTest {

    private static final ClassTables TABLES = TestClassTables.load();

    private static final int[][] FULL = {{}, {2}, {3}, {4, 2}, {4, 3}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 2},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1}, {4, 3, 3, 3, 2, 1, 1},
            {4, 3, 3, 3, 2, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 2, 1, 1, 1, 1},
            {4, 3, 3, 3, 3, 1, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 1, 1, 1}, {4, 3, 3, 3, 3, 2, 2, 1, 1}};
    private static final int[][] HALF = {{}, {}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2}, {4, 3, 2},
            {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2}, {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2}};
    private static final int[][] ARTIFICER = {{}, {2}, {2}, {3}, {3}, {4, 2}, {4, 2}, {4, 3}, {4, 3}, {4, 3, 2},
            {4, 3, 2}, {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}, {4, 3, 3, 2}, {4, 3, 3, 2}, {4, 3, 3, 3, 1},
            {4, 3, 3, 3, 1}, {4, 3, 3, 3, 2}, {4, 3, 3, 3, 2}};
    private static final int[][] THIRD = {{}, {}, {}, {2}, {3}, {3}, {3}, {4, 2}, {4, 2}, {4, 2}, {4, 3}, {4, 3},
            {4, 3}, {4, 3, 2}, {4, 3, 2}, {4, 3, 2}, {4, 3, 3}, {4, 3, 3}, {4, 3, 3}, {4, 3, 3, 1}, {4, 3, 3, 1}};
    private static final int[][] PACT = {{}, {1}, {2}, {0, 2}, {0, 2}, {0, 0, 2}, {0, 0, 2}, {0, 0, 0, 2},
            {0, 0, 0, 2}, {0, 0, 0, 0, 2}, {0, 0, 0, 0, 2}, {0, 0, 0, 0, 3}, {0, 0, 0, 0, 3}, {0, 0, 0, 0, 3},
            {0, 0, 0, 0, 3}, {0, 0, 0, 0, 3}, {0, 0, 0, 0, 3}, {0, 0, 0, 0, 4}, {0, 0, 0, 0, 4}, {0, 0, 0, 0, 4},
            {0, 0, 0, 0, 4}};

    private static List<Integer> column(int... values) {
        return Arrays.stream(values).boxed().toList();
    }

    @Test
    @DisplayName("Each caster type's slot table is the PHB's (Tasha's for the Artificer), and the multiclass table is the full caster's")
    void slotTables() {
        Map<String, int[][]> expected = Map.of("full", FULL, "half", HALF, "artificer", ARTIFICER, "third", THIRD,
                "pact", PACT);
        expected.forEach((type, rows) -> {
            for (int level = 0; level <= 20; level++) {
                int[] want = level == 0 ? new int[0] : rows[level];
                assertThat(TABLES.slotRow(type, level)).as(type + " " + level).containsExactly(want);
            }
        });
        for (int level = 1; level <= 20; level++) {
            assertThat(TABLES.multiclassRow(level)).as("multiclass " + level).containsExactly(FULL[level]);
            assertThat(TABLES.pactMagicRow(level)).as("pact " + level).containsExactly(PACT[level]);
        }
        assertThat(TABLES.slotRow("none", 5)).isEmpty();
        assertThat(TABLES.slotRow("full", 21)).isEmpty();
    }

    @Test
    @DisplayName("The class loader's slot progression leaves out the levels and slots without any")
    void slotProgression() {
        Map<String, Map<String, Integer>> half = TABLES.slotProgression("half");

        assertThat(half).doesNotContainKey("1");
        assertThat(half.get("2")).containsExactly(Map.entry("1", 2));
        assertThat(TABLES.slotProgression("pact").get("3")).containsExactly(Map.entry("2", 2));
        assertThat(TABLES.slotProgression("none")).isNull();
    }

    @Test
    @DisplayName("Each class's Cantrips Known and Spells Known columns, how it prepares spells, and the Eldritch Knight's and Arcane Trickster's")
    void spellcastingColumns() {
        assertSpellcasting("Artificer", "artificer", column(0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4,
                4, 4), List.of(), Preparing.HALF_LEVEL);
        assertSpellcasting("Bard", "full", column(0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
                column(0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 15, 16, 18, 19, 19, 20, 22, 22, 22), Preparing.NONE);
        assertSpellcasting("Cleric", "full", column(0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5),
                List.of(), Preparing.LEVEL);
        assertSpellcasting("Druid", "full", column(0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
                List.of(), Preparing.LEVEL);
        assertSpellcasting("Paladin", "half", List.of(), List.of(), Preparing.HALF_LEVEL);
        assertSpellcasting("Ranger", "half", List.of(),
                column(0, 0, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11), Preparing.NONE);
        assertSpellcasting("Sorcerer", "full", column(0, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6),
                column(0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 13, 13, 14, 14, 15, 15, 15, 15), Preparing.NONE);
        assertSpellcasting("Warlock", "pact", column(0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
                column(0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15), Preparing.NONE);
        assertSpellcasting("Wizard", "full", column(0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5),
                List.of(), Preparing.LEVEL);
        for (String className : List.of("Barbarian", "Fighter", "Monk", "Rogue")) {
            assertThat(TABLES.classTable(className).orElseThrow().spellcasting()).as(className).isNull();
        }

        List<Integer> thirdCasterSpells = column(0, 0, 0, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11, 11, 12, 13);
        assertThat(TABLES.subclassTable("Fighter", "Eldritch Knight").orElseThrow().spellcasting()).isEqualTo(
                new Spellcasting("third", column(0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
                        thirdCasterSpells, Preparing.NONE));
        assertThat(TABLES.subclassTable("Rogue", "Arcane Trickster").orElseThrow().spellcasting()).isEqualTo(
                new Spellcasting("third", column(0, 0, 0, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
                        thirdCasterSpells, Preparing.NONE));
        assertThat(TABLES.subclassTable("Fighter", "Arcane Trickster")).isEmpty();
    }

    private static void assertSpellcasting(String className, String casterType, List<Integer> cantrips,
                                           List<Integer> spellsKnown, Preparing preparing) {
        assertThat(TABLES.classTable(className).orElseThrow().spellcasting()).as(className)
                .isEqualTo(new Spellcasting(casterType, cantrips, spellsKnown, preparing));
    }

    @Test
    @DisplayName("Subclass, ASI, Expertise and Fighting Style levels, the Fighting Styles and the Wizard's spellbook")
    void classFeatureLevels() {
        Map<String, Integer> subclassLevels = Map.ofEntries(Map.entry("Cleric", 1), Map.entry("Sorcerer", 1),
                Map.entry("Warlock", 1), Map.entry("Druid", 2), Map.entry("Wizard", 2), Map.entry("Artificer", 3),
                Map.entry("Barbarian", 3), Map.entry("Bard", 3), Map.entry("Fighter", 3), Map.entry("Monk", 3),
                Map.entry("Paladin", 3), Map.entry("Ranger", 3), Map.entry("Rogue", 3));
        assertThat(TABLES.classes().keySet()).isEqualTo(subclassLevels.keySet());
        subclassLevels.forEach((className, level) ->
                assertThat(TABLES.subclassLevel(className)).as(className).isEqualTo(level));
        assertThat(TABLES.subclassLevel("Mystic")).isEqualTo(3);

        TABLES.classes().forEach((className, table) -> {
            List<Integer> asi = switch (className) {
                case "Fighter" -> List.of(4, 6, 8, 12, 14, 16, 19);
                case "Rogue" -> List.of(4, 8, 10, 12, 16, 19);
                default -> List.of(4, 8, 12, 16, 19);
            };
            assertThat(table.asiLevels()).as(className).isEqualTo(asi);
        });
        assertThat(TABLES.defaults().asiLevels()).isEqualTo(List.of(4, 8, 12, 16, 19));

        assertThat(TABLES.classTable("Rogue").orElseThrow().expertise()).isEqualTo(Map.of(1, 2, 6, 2));
        assertThat(TABLES.classTable("Bard").orElseThrow().expertise()).isEqualTo(Map.of(3, 2, 10, 2));
        assertThat(TABLES.classTable("Fighter").orElseThrow().fightingStyleLevels()).isEqualTo(List.of(1));
        assertThat(TABLES.classTable("Paladin").orElseThrow().fightingStyleLevels()).isEqualTo(List.of(2));
        assertThat(TABLES.classTable("Ranger").orElseThrow().fightingStyleLevels()).isEqualTo(List.of(2));
        assertThat(TABLES.subclassTable("Fighter", "Champion").orElseThrow().fightingStyleLevels())
                .isEqualTo(List.of(10));
        assertThat(TABLES.classTable("Wizard").orElseThrow().spellbook())
                .isEqualTo(new ClassTables.Spellbook(6, 2));
    }

    @Test
    @DisplayName("The file is read strictly: an unknown property or a column of the wrong length fails, naming the file")
    void readsStrictly() {
        String good = "{\"spellSlots\": {\"full\": %s, \"half\": %s, \"artificer\": %s, \"third\": %s, \"pact\": %s},"
                + " \"defaults\": {\"subclassLevel\": 3, \"asiLevels\": [4]}, \"classes\": {%s}}";
        String rows = "[" + String.join(",", java.util.Collections.nCopies(21, "[]")) + "]";
        String fine = String.format(good, rows, rows, rows, rows, rows, "");

        assertThat(read(fine).classes()).isEmpty();
        assertThatThrownBy(() -> read(String.format(good, rows, rows, rows, rows, rows,
                "\"Bard\": {\"subclassLevel\": 3, \"spells\": []}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(ClassTablesFile.PATH);
        assertThatThrownBy(() -> read(String.format(good, rows, rows, rows, rows, rows,
                "\"Bard\": {\"subclassLevel\": 3, \"spellcasting\": {\"casterType\": \"full\", \"cantripsKnown\": [0, 2]}}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cantripsKnown");
        assertThatThrownBy(() -> read(String.format(good, "[[]]", rows, rows, rows, rows, "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("full slot table");
    }

    private static ClassTables read(String json) {
        return ClassTablesFile.read(new ObjectMapper(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
