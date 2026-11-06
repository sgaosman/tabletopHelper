package com.tabletophelper.character;

import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.TestClassTables;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Source;
import com.tabletophelper.character.state.PendingChoice.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Pending Choices each class level brings (D159), for every class at levels 1–20. The expected choices are typed
 * out here from the class tables and features in chapter 3 of the 2014 PHB (the Artificer's from Tasha's Cauldron of
 * Everything): the subclass at the class's subclass level, Fighting Styles, Ability Score Improvements, Expertise,
 * the rise in Cantrips Known and, for a class that knows a set number of spells, in Spells Known, with the swap of a
 * known spell from the class's second spell level on, and a wizard's six spellbook spells at 1st level and two after.
 * One part departs from the PHB, as D159 records: the PHB lets a known caster swap a spell at every level from its
 * second on, but here the swap is offered only at a level where Spells Known rise. Each choice is written as its
 * type, then ×count for more than one, then "swap" when it offers one; a class that prepares its spells has no SPELLS.
 * A level not listed owes nothing.
 */
class PendingChoicesTest {

    private static final ClassTables TABLES = TestClassTables.load();

    private static final List<String> SUBCLASSES = List.of("First Path", "Second Path");

    /** Every choice the class owes at each level from 1st to 20th, with no subclass chosen. */
    static Stream<Arguments> everyClass() {
        return Stream.of(
                Arguments.of("Artificer", 3, Map.ofEntries(
                        entry(1, List.of("CANTRIPS×2")), entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")),
                        entry(8, List.of("ASI")), entry(10, List.of("CANTRIPS")), entry(12, List.of("ASI")),
                        entry(14, List.of("CANTRIPS")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Barbarian", 3, Map.ofEntries(
                        entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")), entry(8, List.of("ASI")),
                        entry(12, List.of("ASI")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Bard", 3, Map.ofEntries(
                        entry(1, List.of("CANTRIPS×2", "SPELLS×4")), entry(2, List.of("SPELLS swap")),
                        entry(3, List.of("SUBCLASS", "EXPERTISE×2", "SPELLS swap")),
                        entry(4, List.of("ASI", "CANTRIPS", "SPELLS swap")), entry(5, List.of("SPELLS swap")),
                        entry(6, List.of("SPELLS swap")), entry(7, List.of("SPELLS swap")),
                        entry(8, List.of("ASI", "SPELLS swap")), entry(9, List.of("SPELLS swap")),
                        entry(10, List.of("EXPERTISE×2", "CANTRIPS", "SPELLS×2 swap")), entry(11, List.of("SPELLS swap")),
                        entry(12, List.of("ASI")), entry(13, List.of("SPELLS swap")), entry(14, List.of("SPELLS×2 swap")),
                        entry(15, List.of("SPELLS swap")), entry(16, List.of("ASI")), entry(17, List.of("SPELLS swap")),
                        entry(18, List.of("SPELLS×2 swap")), entry(19, List.of("ASI")))),
                Arguments.of("Cleric", 1, Map.ofEntries(
                        entry(1, List.of("SUBCLASS", "CANTRIPS×3")), entry(4, List.of("ASI", "CANTRIPS")),
                        entry(8, List.of("ASI")), entry(10, List.of("CANTRIPS")), entry(12, List.of("ASI")),
                        entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Druid", 2, Map.ofEntries(
                        entry(1, List.of("CANTRIPS×2")), entry(2, List.of("SUBCLASS")),
                        entry(4, List.of("ASI", "CANTRIPS")), entry(8, List.of("ASI")), entry(10, List.of("CANTRIPS")),
                        entry(12, List.of("ASI")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Fighter", 3, Map.ofEntries(
                        entry(1, List.of("FIGHTING_STYLE")), entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")),
                        entry(6, List.of("ASI")), entry(8, List.of("ASI")), entry(12, List.of("ASI")),
                        entry(14, List.of("ASI")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Monk", 3, Map.ofEntries(
                        entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")), entry(8, List.of("ASI")),
                        entry(12, List.of("ASI")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Paladin", 3, Map.ofEntries(
                        entry(2, List.of("FIGHTING_STYLE")), entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")),
                        entry(8, List.of("ASI")), entry(12, List.of("ASI")), entry(16, List.of("ASI")),
                        entry(19, List.of("ASI")))),
                Arguments.of("Ranger", 3, Map.ofEntries(
                        entry(2, List.of("FIGHTING_STYLE", "SPELLS×2")), entry(3, List.of("SUBCLASS", "SPELLS swap")),
                        entry(4, List.of("ASI")), entry(5, List.of("SPELLS swap")), entry(7, List.of("SPELLS swap")),
                        entry(8, List.of("ASI")), entry(9, List.of("SPELLS swap")), entry(11, List.of("SPELLS swap")),
                        entry(12, List.of("ASI")), entry(13, List.of("SPELLS swap")), entry(15, List.of("SPELLS swap")),
                        entry(16, List.of("ASI")), entry(17, List.of("SPELLS swap")),
                        entry(19, List.of("ASI", "SPELLS swap")))),
                Arguments.of("Rogue", 3, Map.ofEntries(
                        entry(1, List.of("EXPERTISE×2")), entry(3, List.of("SUBCLASS")), entry(4, List.of("ASI")),
                        entry(6, List.of("EXPERTISE×2")), entry(8, List.of("ASI")), entry(10, List.of("ASI")),
                        entry(12, List.of("ASI")), entry(16, List.of("ASI")), entry(19, List.of("ASI")))),
                Arguments.of("Sorcerer", 1, Map.ofEntries(
                        entry(1, List.of("SUBCLASS", "CANTRIPS×4", "SPELLS×2")), entry(2, List.of("SPELLS swap")),
                        entry(3, List.of("SPELLS swap")), entry(4, List.of("ASI", "CANTRIPS", "SPELLS swap")),
                        entry(5, List.of("SPELLS swap")), entry(6, List.of("SPELLS swap")),
                        entry(7, List.of("SPELLS swap")), entry(8, List.of("ASI", "SPELLS swap")),
                        entry(9, List.of("SPELLS swap")), entry(10, List.of("CANTRIPS", "SPELLS swap")),
                        entry(11, List.of("SPELLS swap")), entry(12, List.of("ASI")), entry(13, List.of("SPELLS swap")),
                        entry(15, List.of("SPELLS swap")), entry(16, List.of("ASI")), entry(17, List.of("SPELLS swap")),
                        entry(19, List.of("ASI")))),
                Arguments.of("Warlock", 1, Map.ofEntries(
                        entry(1, List.of("SUBCLASS", "CANTRIPS×2", "SPELLS×2")), entry(2, List.of("SPELLS swap")),
                        entry(3, List.of("SPELLS swap")), entry(4, List.of("ASI", "CANTRIPS", "SPELLS swap")),
                        entry(5, List.of("SPELLS swap")), entry(6, List.of("SPELLS swap")),
                        entry(7, List.of("SPELLS swap")), entry(8, List.of("ASI", "SPELLS swap")),
                        entry(9, List.of("SPELLS swap")), entry(10, List.of("CANTRIPS")), entry(11, List.of("SPELLS swap")),
                        entry(12, List.of("ASI")), entry(13, List.of("SPELLS swap")), entry(15, List.of("SPELLS swap")),
                        entry(16, List.of("ASI")), entry(17, List.of("SPELLS swap")),
                        entry(19, List.of("ASI", "SPELLS swap")))),
                Arguments.of("Wizard", 2, wizard()));
    }

    // Two spellbook spells at every wizard level after the 1st
    private static Map<Integer, List<String>> wizard() {
        Map<Integer, List<String>> owed = new HashMap<>();
        for (int level = 2; level <= 20; level++) {
            owed.put(level, new ArrayList<>(List.of("SPELLS×2")));
        }
        owed.put(1, List.of("CANTRIPS×3", "SPELLS×6"));
        owed.put(2, List.of("SUBCLASS", "SPELLS×2"));
        owed.put(4, List.of("ASI", "CANTRIPS", "SPELLS×2"));
        owed.put(10, List.of("CANTRIPS", "SPELLS×2"));
        for (int level : List.of(8, 12, 16, 19)) {
            owed.put(level, List.of("ASI", "SPELLS×2"));
        }
        return owed;
    }

    private static PendingChoices.ClassLevel level(String className, String subclass, int classLevel,
                                                   int subclassLevel) {
        return new PendingChoices.ClassLevel(UUID.randomUUID(), className, classLevel, subclass, subclassLevel,
                SUBCLASSES);
    }

    private static List<String> owed(PendingChoices.ClassLevel level) {
        return described(PendingChoices.owedAt(TABLES, level.classLevel(), level, Source.LEVEL_UP));
    }

    // Each choice as its type, ×count for more than one, and "swap" when it offers one
    private static List<String> described(List<PendingChoice> choices) {
        return choices.stream()
                .map(c -> c.type() + (c.count() > 1 ? "×" + c.count() : "")
                        + (Boolean.TRUE.equals(c.swap()) ? " swap" : ""))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyClass")
    @DisplayName("Each class level owes its PHB choices, in order: subclass, Fighting Style, ASI, Expertise, cantrips, spells; a known caster's swap comes only when Spells Known rise")
    void everyClassLevel(String className, int subclassLevel, Map<Integer, List<String>> expected) {
        for (int classLevel = 1; classLevel <= 20; classLevel++) {
            assertThat(owed(level(className, null, classLevel, subclassLevel)))
                    .as(className + " " + classLevel)
                    .isEqualTo(expected.getOrDefault(classLevel, List.of()));
        }
    }

    @Test
    @DisplayName("An Eldritch Knight and an Arcane Trickster learn the wizard's numbers of spells from 3rd level, and the Champion a second style at 10th")
    void subclassesWithChoices() {
        assertThat(owed(level("Fighter", "Eldritch Knight", 3, 3))).containsExactly("CANTRIPS×2", "SPELLS×3");
        assertThat(owed(level("Fighter", "Eldritch Knight", 4, 3))).containsExactly("ASI", "SPELLS swap");
        assertThat(owed(level("Fighter", "Eldritch Knight", 5, 3))).isEmpty();
        assertThat(owed(level("Fighter", "Eldritch Knight", 10, 3))).containsExactly("CANTRIPS", "SPELLS swap");
        assertThat(owed(level("Fighter", "Eldritch Knight", 20, 3))).containsExactly("SPELLS swap");
        assertThat(owed(level("Rogue", "Arcane Trickster", 3, 3))).containsExactly("CANTRIPS×3", "SPELLS×3");
        assertThat(owed(level("Rogue", "Arcane Trickster", 10, 3))).containsExactly("ASI", "CANTRIPS", "SPELLS swap");
        assertThat(owed(level("Fighter", "Champion", 10, 3))).containsExactly("FIGHTING_STYLE");
        assertThat(owed(level("Fighter", "Battle Master", 10, 3))).isEmpty();
        // A subclass chosen already isn't owed again
        assertThat(owed(level("Cleric", "Life Domain", 1, 1))).containsExactly("CANTRIPS×3");
    }

    @Test
    @DisplayName("A subclass chosen after its level brings only the choices it adds: an Eldritch Knight's spells, a Champion's style")
    void choicesASubclassBrings() {
        assertThat(described(PendingChoices.owedBySubclass(TABLES, 3, level("Fighter", "Eldritch Knight", 3, 3),
                Source.CREATION))).containsExactly("CANTRIPS×2", "SPELLS×3");
        assertThat(described(PendingChoices.owedBySubclass(TABLES, 4, level("Fighter", "Eldritch Knight", 4, 3),
                Source.CREATION))).containsExactly("SPELLS swap");
        assertThat(described(PendingChoices.owedBySubclass(TABLES, 10, level("Fighter", "Champion", 10, 3),
                Source.LEVEL_UP))).containsExactly("FIGHTING_STYLE");
        assertThat(PendingChoices.owedBySubclass(TABLES, 3, level("Fighter", "Champion", 3, 3), Source.LEVEL_UP)).isEmpty();
        // A cleric's domain changes none of the class's own choices
        assertThat(PendingChoices.owedBySubclass(TABLES, 4, level("Cleric", "Life Domain", 4, 1), Source.LEVEL_UP)).isEmpty();
    }

    @Test
    @DisplayName("Each choice records its level, class, options, highest spell level and source")
    void whatAChoiceRecords() {
        UUID sorcererId = UUID.randomUUID();
        List<PendingChoice> owed = PendingChoices.owedAt(TABLES, 7, new PendingChoices.ClassLevel(sorcererId, "Sorcerer", 5,
                null, 1, SUBCLASSES), Source.CREATION);

        assertThat(owed).singleElement().satisfies(spells -> {
            assertThat(spells.type()).isEqualTo(Type.SPELLS);
            assertThat(spells.source()).isEqualTo(Source.CREATION);
            assertThat(spells.characterLevel()).isEqualTo(7);
            assertThat(spells.classId()).isEqualTo(sorcererId);
            assertThat(spells.className()).isEqualTo("Sorcerer");
            assertThat(spells.classLevel()).isEqualTo(5);
            assertThat(spells.count()).isEqualTo(1);
            assertThat(spells.maxSpellLevel()).isEqualTo(3);
            assertThat(spells.options()).isNull();
            assertThat(spells.spellList()).isEqualTo("Sorcerer");
        });
        PendingChoice subclass = PendingChoices.owedAt(TABLES, 3, level("Fighter", null, 3, 3), Source.LEVEL_UP).getFirst();
        assertThat(subclass.options()).isEqualTo(SUBCLASSES);
        PendingChoice style = PendingChoices.owedAt(TABLES, 2, level("Paladin", null, 2, 3), Source.LEVEL_UP).getFirst();
        assertThat(style.options()).containsExactly("Defense", "Dueling", "Great Weapon Fighting", "Protection",
                "Blessed Warrior", "Blind Fighting", "Interception");
        PendingChoice cantrips = PendingChoices.owedAt(TABLES, 4, level("Wizard", null, 4, 2), Source.LEVEL_UP).get(1);
        assertThat(cantrips.maxSpellLevel()).isZero();
        assertThat(PendingChoices.owedAt(TABLES, 5, level("Warlock", null, 5, 1), Source.LEVEL_UP).getFirst().maxSpellLevel())
                .isEqualTo(3);
        assertThat(PendingChoices.owedAt(TABLES, 3, level("Wizard", null, 3, 2), Source.LEVEL_UP).getFirst().maxSpellLevel())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Cantrip and spell choices name the class's spell list, the wizard's for an Eldritch Knight or Arcane Trickster; other choices name none")
    void spellListOfAChoice() {
        List<PendingChoice> knight = PendingChoices.owedAt(TABLES, 3, level("Fighter", "Eldritch Knight", 3, 3),
                Source.LEVEL_UP);
        assertThat(knight).filteredOn(c -> c.type() == Type.CANTRIPS || c.type() == Type.SPELLS)
                .hasSize(2).allSatisfy(c -> assertThat(c.spellList()).isEqualTo("Wizard"));
        assertThat(PendingChoices.owedAt(TABLES, 3, level("Rogue", "Arcane Trickster", 3, 3), Source.LEVEL_UP))
                .filteredOn(c -> c.type() == Type.SPELLS)
                .singleElement().satisfies(c -> assertThat(c.spellList()).isEqualTo("Wizard"));
        List<PendingChoice> cleric = PendingChoices.owedAt(TABLES, 4, level("Cleric", "Life Domain", 4, 1),
                Source.LEVEL_UP);
        assertThat(cleric).filteredOn(c -> c.type() == Type.CANTRIPS)
                .singleElement().satisfies(c -> assertThat(c.spellList()).isEqualTo("Cleric"));
        assertThat(cleric).filteredOn(c -> c.type() == Type.ASI)
                .singleElement().satisfies(c -> assertThat(c.spellList()).isNull());
    }

    @Test
    @DisplayName("A class with no subclass to choose, a character without a class, or class level 0 owes nothing of the kind")
    void nothingOwed() {
        assertThat(owed(new PendingChoices.ClassLevel(UUID.randomUUID(), "Fighter", 3, null, 3, List.of())))
                .isEmpty();
        assertThat(PendingChoices.owedAt(TABLES, 1, new PendingChoices.ClassLevel(null, null, 1, null, 3, SUBCLASSES),
                Source.CREATION)).isEmpty();
        assertThat(PendingChoices.owedAt(TABLES, 1, level("Wizard", null, 0, 2), Source.CREATION)).isEmpty();
    }

    @Test
    @DisplayName("Choices are made a level at a time, oldest first, and a subclass first within a level")
    void order() {
        List<PendingChoice> choices = new ArrayList<>();
        choices.addAll(PendingChoices.owedAt(TABLES, 4, level("Wizard", null, 4, 2), Source.LEVEL_UP));
        choices.addAll(PendingChoices.owedAt(TABLES, 2, level("Wizard", null, 2, 2), Source.LEVEL_UP));
        choices.sort(PendingChoices.ORDER);

        assertThat(choices).extracting(PendingChoice::characterLevel, PendingChoice::type).containsExactly(
                tuple(2, Type.SUBCLASS),
                tuple(2, Type.SPELLS),
                tuple(4, Type.ASI),
                tuple(4, Type.CANTRIPS),
                tuple(4, Type.SPELLS));
    }
}
