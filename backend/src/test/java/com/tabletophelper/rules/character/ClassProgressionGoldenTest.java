package com.tabletophelper.rules.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every class at levels 1–20: its Ability Score Improvements, Expertise and Fighting Styles, the cantrips and spells
 * it knows, the spells it can prepare, and the highest level of spell it can learn or prepare. The expected values
 * are typed out here from the class tables and class features in chapter 3 of the 2014 PHB (the Artificer's from
 * Tasha's Cauldron of Everything), independently of {@link ClassProgression}.
 */
class ClassProgressionGoldenTest {

    // One value per class level, 1st to 20th
    //                                                1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20
    private static final int[] NOTHING            = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    // Cantrips Known columns. The Bard, Druid and Warlock tables list the same numbers, as do the Cleric's and Wizard's
    private static final int[] BARD_CANTRIPS      = { 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] CLERIC_CANTRIPS    = { 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
    private static final int[] SORCERER_CANTRIPS  = { 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6};
    private static final int[] ARTIFICER_CANTRIPS = { 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4};
    // The Eldritch Knight's and Arcane Trickster's Spellcasting tables (PHB p.74, p.97), by fighter or rogue level
    private static final int[] EK_CANTRIPS        = { 0, 0, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    private static final int[] AT_CANTRIPS        = { 0, 0, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};

    // Spells Known columns
    private static final int[] BARD_SPELLS        = { 4, 5, 6, 7, 8, 9,10,11,12,14,15,15,16,18,19,19,20,22,22,22};
    private static final int[] RANGER_SPELLS      = { 0, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9,10,10,11,11};
    private static final int[] SORCERER_SPELLS    = { 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,12,13,13,14,14,15,15,15,15};
    private static final int[] WARLOCK_SPELLS     = { 2, 3, 4, 5, 6, 7, 8, 9,10,10,11,11,12,12,13,13,14,14,15,15};
    // The Eldritch Knight and Arcane Trickster tables list the same numbers
    private static final int[] EK_AT_SPELLS       = { 0, 0, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 9,10,10,11,11,11,12,13};

    // The highest spell level with a slot in each table's slot columns; the Warlock's is its Slot Level column
    private static final int[] FULL_CASTER_LEVEL  = { 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 9, 9};
    private static final int[] HALF_CASTER_LEVEL  = { 0, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5};
    private static final int[] ARTIFICER_LEVEL    = { 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5};
    private static final int[] THIRD_CASTER_LEVEL = { 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4};
    private static final int[] WARLOCK_LEVEL      = { 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

    // Spells prepared with a spellcasting modifier of +3, from each class's Spellcasting feature: the modifier plus
    // the class level for a cleric, druid or wizard, and plus half the level, rounded down, for a paladin or an
    // artificer. A paladin has no Spellcasting until 2nd level.
    private static final int[] FULL_PREPARED      = { 4, 5, 6, 7, 8, 9,10,11,12,13,14,15,16,17,18,19,20,21,22,23};
    private static final int[] PALADIN_PREPARED   = { 0, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9,10,10,11,11,12,12,13};
    private static final int[] ARTIFICER_PREPARED = { 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9,10,10,11,11,12,12,13};

    private static final int MODIFIER = 3;

    // Ability Score Improvement levels
    private static final Set<Integer> STANDARD_ASI = Set.of(4, 8, 12, 16, 19);
    private static final Set<Integer> FIGHTER_ASI = Set.of(4, 6, 8, 12, 14, 16, 19);
    private static final Set<Integer> ROGUE_ASI = Set.of(4, 8, 10, 12, 16, 19);

    /**
     * One class's expected progression. Expertise maps a class level to the number of proficiencies chosen there;
     * spellsKnown is null for a class that prepares its spells or has none, and prepared null for one that doesn't
     * prepare them.
     */
    record ClassTable(String label, String className, String subclass, Set<Integer> asi,
                      Map<Integer, Integer> expertise, Set<Integer> fightingStyles, int[] cantrips,
                      int[] spellsKnown, int[] prepared, int[] highestSpellLevel) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<ClassTable> everyClass() {
        return Stream.of(
                // TCE p.9
                new ClassTable("Artificer", "Artificer", null, STANDARD_ASI, Map.of(), Set.of(),
                        ARTIFICER_CANTRIPS, null, ARTIFICER_PREPARED, ARTIFICER_LEVEL),
                // PHB p.46
                new ClassTable("Barbarian", "Barbarian", "Path of the Berserker", STANDARD_ASI, Map.of(), Set.of(),
                        NOTHING, null, null, NOTHING),
                // PHB p.51; Expertise at 3rd and 10th level
                new ClassTable("Bard", "Bard", "College of Lore", STANDARD_ASI, Map.of(3, 2, 10, 2), Set.of(),
                        BARD_CANTRIPS, BARD_SPELLS, null, FULL_CASTER_LEVEL),
                // PHB p.56
                new ClassTable("Cleric", "Cleric", "Life Domain", STANDARD_ASI, Map.of(), Set.of(),
                        CLERIC_CANTRIPS, null, FULL_PREPARED, FULL_CASTER_LEVEL),
                // PHB p.64
                new ClassTable("Druid", "Druid", "Circle of the Land", STANDARD_ASI, Map.of(), Set.of(),
                        BARD_CANTRIPS, null, FULL_PREPARED, FULL_CASTER_LEVEL),
                // PHB p.70; Fighting Style at 1st level and the extra ASIs at 6th and 14th (p.72)
                new ClassTable("Fighter", "Fighter", "Battle Master", FIGHTER_ASI, Map.of(), Set.of(1),
                        NOTHING, null, null, NOTHING),
                // The Champion's Additional Fighting Style at 10th level (PHB p.72)
                new ClassTable("Champion", "Fighter", "Champion", FIGHTER_ASI, Map.of(), Set.of(1, 10),
                        NOTHING, null, null, NOTHING),
                // PHB p.74
                new ClassTable("Eldritch Knight", "Fighter", "Eldritch Knight", FIGHTER_ASI, Map.of(), Set.of(1),
                        EK_CANTRIPS, EK_AT_SPELLS, null, THIRD_CASTER_LEVEL),
                // PHB p.76
                new ClassTable("Monk", "Monk", "Way of the Open Hand", STANDARD_ASI, Map.of(), Set.of(),
                        NOTHING, null, null, NOTHING),
                // PHB p.82; Fighting Style and Spellcasting at 2nd level
                new ClassTable("Paladin", "Paladin", "Oath of Devotion", STANDARD_ASI, Map.of(), Set.of(2),
                        NOTHING, null, PALADIN_PREPARED, HALF_CASTER_LEVEL),
                // PHB p.89; Fighting Style and Spellcasting at 2nd level
                new ClassTable("Ranger", "Ranger", "Hunter", STANDARD_ASI, Map.of(), Set.of(2),
                        NOTHING, RANGER_SPELLS, null, HALF_CASTER_LEVEL),
                // PHB p.94; Expertise at 1st and 6th level and the extra ASI at 10th
                new ClassTable("Rogue", "Rogue", "Thief", ROGUE_ASI, Map.of(1, 2, 6, 2), Set.of(),
                        NOTHING, null, null, NOTHING),
                // PHB p.97
                new ClassTable("Arcane Trickster", "Rogue", "Arcane Trickster", ROGUE_ASI, Map.of(1, 2, 6, 2), Set.of(),
                        AT_CANTRIPS, EK_AT_SPELLS, null, THIRD_CASTER_LEVEL),
                // PHB p.99
                new ClassTable("Sorcerer", "Sorcerer", "Draconic Bloodline", STANDARD_ASI, Map.of(), Set.of(),
                        SORCERER_CANTRIPS, SORCERER_SPELLS, null, FULL_CASTER_LEVEL),
                // PHB p.105
                new ClassTable("Warlock", "Warlock", "The Fiend", STANDARD_ASI, Map.of(), Set.of(),
                        BARD_CANTRIPS, WARLOCK_SPELLS, null, WARLOCK_LEVEL),
                // PHB p.112
                new ClassTable("Wizard", "Wizard", "School of Evocation", STANDARD_ASI, Map.of(), Set.of(),
                        CLERIC_CANTRIPS, null, FULL_PREPARED, FULL_CASTER_LEVEL)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Ability Score Improvements: 4th, 8th, 12th, 16th and 19th level, plus 6th and 14th for a fighter and 10th for a rogue")
    void abilityScoreImprovements(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(table.asi().contains(level),
                ClassProgression.isAsiLevel(table.className(), level), table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Expertise: two proficiencies at Rogue 1st and 6th level and Bard 3rd and 10th")
    void expertise(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(table.expertise().getOrDefault(level, 0),
                ClassProgression.expertiseChoices(table.className(), level), table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Fighting Styles: Fighter 1st level, Paladin and Ranger 2nd, and a Champion's second at 10th")
    void fightingStyles(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(table.fightingStyles().contains(level),
                ClassProgression.isFightingStyleLevel(table.className(), table.subclass(), level),
                table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Cantrips known match each class table")
    void cantripsKnown(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(table.cantrips()[level - 1],
                ClassProgression.cantripsKnown(table.className(), table.subclass(), level), table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Spells known match each known caster's table; a class that prepares its spells, or has none, has no such limit")
    void spellsKnown(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(
                table.spellsKnown() == null ? OptionalInt.empty() : OptionalInt.of(table.spellsKnown()[level - 1]),
                ClassProgression.spellsKnown(table.className(), table.subclass(), level), table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Spells prepared with a +3 modifier; a class that doesn't prepare spells has no such limit")
    void spellsPrepared(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(
                table.prepared() == null ? OptionalInt.empty() : OptionalInt.of(table.prepared()[level - 1]),
                ClassProgression.preparedSpells(table.className(), level, MODIFIER), table + " " + level)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("The highest spell level each class can learn or prepare is its own table's highest slot")
    void highestSpellLevel(ClassTable table) {
        assertAll(everyLevel(level -> assertEquals(table.highestSpellLevel()[level - 1],
                ClassProgression.highestSpellLevel(table.className(), table.subclass(), level),
                table + " " + level)));
    }

    @Test
    @DisplayName("A class prepares at least one spell however low its modifier, once it has Spellcasting")
    void preparedSpellsAreAtLeastOne() {
        assertEquals(OptionalInt.of(1), ClassProgression.preparedSpells("Cleric", 1, -2));
        assertEquals(OptionalInt.of(1), ClassProgression.preparedSpells("Wizard", 2, -3));
        assertEquals(OptionalInt.of(1), ClassProgression.preparedSpells("Druid", 1, -1));
        assertEquals(OptionalInt.of(1), ClassProgression.preparedSpells("Paladin", 2, -1));
        assertEquals(OptionalInt.of(1), ClassProgression.preparedSpells("Artificer", 1, -1));
        // A 1st-level paladin has no Spellcasting yet
        assertEquals(OptionalInt.of(0), ClassProgression.preparedSpells("Paladin", 1, 3));
    }

    @Test
    @DisplayName("A fighter or rogue of another subclass casts nothing, and only the Champion gains a second Fighting Style")
    void otherSubclassesGainNoSpellsOrStyles() {
        assertEquals(0, ClassProgression.cantripsKnown("Fighter", "Battle Master", 10));
        assertEquals(OptionalInt.empty(), ClassProgression.spellsKnown("Rogue", "Thief", 10));
        assertEquals(0, ClassProgression.highestSpellLevel("Rogue", null, 20));
        assertFalse(ClassProgression.isFightingStyleLevel("Fighter", null, 10));
        assertFalse(ClassProgression.isFightingStyleLevel("Paladin", "Champion", 10));
    }

    @Test
    @DisplayName("Fighting Styles: the fighter's six, the paladin's four and the ranger's four from the PHB, then Tasha's for each")
    void fightingStylesByClass() {
        assertEquals(List.of("Archery", "Defense", "Dueling", "Great Weapon Fighting", "Protection",
                "Two-Weapon Fighting", "Blind Fighting", "Interception", "Superior Technique", "Thrown Weapon Fighting",
                "Unarmed Fighting"), ClassProgression.fightingStyles("Fighter"));
        assertEquals(List.of("Defense", "Dueling", "Great Weapon Fighting", "Protection", "Blessed Warrior",
                "Blind Fighting", "Interception"), ClassProgression.fightingStyles("Paladin"));
        assertEquals(List.of("Archery", "Defense", "Dueling", "Two-Weapon Fighting", "Blind Fighting",
                "Druidic Warrior", "Thrown Weapon Fighting"), ClassProgression.fightingStyles("Ranger"));
        assertEquals(List.of(), ClassProgression.fightingStyles("Wizard"));
        assertEquals(List.of(), ClassProgression.fightingStyles(null));
    }

    @Test
    @DisplayName("A wizard's spellbook gains six spells at 1st level and two at each level after (PHB p.114); no other class has one")
    void spellbookSpells() {
        assertEquals(6, ClassProgression.spellbookSpells("Wizard", 1));
        IntStream.rangeClosed(2, 20).forEach(level ->
                assertEquals(2, ClassProgression.spellbookSpells("Wizard", level), "Wizard " + level));
        assertEquals(0, ClassProgression.spellbookSpells("Wizard", 0));
        assertEquals(0, ClassProgression.spellbookSpells("Sorcerer", 3));
        assertEquals(0, ClassProgression.spellbookSpells("Fighter", 3));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.spellbookSpells("Wizard", 21));
    }

    @Test
    @DisplayName("Class level 0, before a character has any levels in the class, gains nothing")
    void classLevelZeroGainsNothing() {
        assertFalse(ClassProgression.isAsiLevel("Fighter", 0));
        assertEquals(0, ClassProgression.expertiseChoices("Rogue", 0));
        assertFalse(ClassProgression.isFightingStyleLevel("Fighter", null, 0));
        assertEquals(0, ClassProgression.cantripsKnown("Sorcerer", null, 0));
        assertEquals(OptionalInt.of(0), ClassProgression.spellsKnown("Sorcerer", null, 0));
        assertEquals(OptionalInt.of(0), ClassProgression.preparedSpells("Cleric", 0, 3));
        assertEquals(0, ClassProgression.highestSpellLevel("Wizard", null, 0));
    }

    @ParameterizedTest(name = "Class level {0}")
    @ValueSource(ints = {-1, 21})
    @DisplayName("A class level outside 0–20 is refused")
    void classLevelOutOfRange(int level) {
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.isAsiLevel("Fighter", level));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.expertiseChoices("Rogue", level));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.isFightingStyleLevel("Fighter", null, level));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.cantripsKnown("Wizard", null, level));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.spellsKnown("Bard", null, level));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.preparedSpells("Cleric", level, 3));
        assertThrows(IllegalArgumentException.class, () -> ClassProgression.highestSpellLevel("Wizard", null, level));
    }

    private static Stream<Executable> everyLevel(IntConsumer check) {
        return IntStream.rangeClosed(1, 20).mapToObj(level -> () -> check.accept(level));
    }
}
