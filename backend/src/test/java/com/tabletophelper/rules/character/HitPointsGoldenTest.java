package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.HitPoints.Level;
import com.tabletophelper.rules.character.HitPoints.PerLevelBonus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hit point maximums typed out here from the 2014 PHB, independently of {@link HitPoints}: the hit die's maximum
 * at 1st level (p.12) and its fixed value, the average rounded up, at each level after that (p.15), with the 1st-level
 * hit points only at character level 1 for a multiclass character (p.163); the Constitution modifier at every level,
 * as though a new modifier had applied from 1st level (p.177), with at least 1 hit point a level (p.15); and the
 * bonuses from Tough (p.170), the Hill Dwarf's Dwarven Toughness (p.20) and the Draconic Bloodline's Draconic
 * Resilience (p.102).
 */
class HitPointsGoldenTest {

    // The bonuses as the data's effects give them: Tough's 2 a level (PHB p.170; feat-effects.json), the Hill Dwarf's
    // 1 a level (p.20; race-effects.json) and the Draconic Bloodline's 1 a sorcerer level (p.102; subclass-effects.json)
    private static final PerLevelBonus TOUGH = PerLevelBonus.everyLevel(2);
    private static final PerLevelBonus DWARVEN_TOUGHNESS = PerLevelBonus.everyLevel(1);
    private static final PerLevelBonus DRACONIC_RESILIENCE = PerLevelBonus.perClassLevel("Sorcerer", 1);

    @ParameterizedTest(name = "d{0}: {1} at character level 1, {2} after")
    @CsvSource({"6, 6, 4", "8, 8, 5", "10, 10, 6", "12, 12, 7"})
    @DisplayName("A level's fixed hit die result is the die's maximum at character level 1 and its average, rounded up, after that")
    void fixedHitDieResults(int hitDie, int atFirstLevel, int afterFirstLevel) {
        assertEquals(atFirstLevel, HitPoints.fixedHitDieResult(1, hitDie));
        for (int characterLevel = 2; characterLevel <= 20; characterLevel++) {
            assertEquals(afterFirstLevel, HitPoints.fixedHitDieResult(characterLevel, hitDie), "level " + characterLevel);
        }
    }

    @Test
    @DisplayName("A multiclass dip takes the new class's average: the maximum die comes only at character level 1")
    void multiclassDipTakesTheAverage() {
        // Fighter 1 then Wizard 1, CON 14 (+2): 10 + 2, then the wizard level at the average 4 + 2, not 6 + 2
        assertEquals(4, HitPoints.fixedHitDieResult(2, 6));
        assertEquals(18, HitPoints.maximum(levels("Fighter", 10, 1, "Wizard", 6, 1), 2, List.of(), 0));

        // Wizard 1 then Fighter 1: 6 + 2, then the fighter level at the average 6 + 2, not 10 + 2
        assertEquals(6, HitPoints.fixedHitDieResult(2, 10));
        assertEquals(16, HitPoints.maximum(levels("Wizard", 6, 1, "Fighter", 10, 1), 2, List.of(), 0));

        // Paladin 5 then Sorcerer 1, CON 12 (+1): 10 + 1, four levels of 6 + 1, then 4 + 1 for the sorcerer level
        assertEquals(44, HitPoints.maximum(levels("Paladin", 10, 5, "Sorcerer", 6, 1), 1, List.of(), 0));
    }

    @Test
    @DisplayName("Tough taken at 4th level adds twice the level at once, then 2 more at every level after")
    void toughTakenAtFourthLevel() {
        // A fighter with CON 14 (+2): 12 at 1st level and 8 a level after that. Tough at 4th level adds 8, and each
        // later level 2 more on top of the usual 8
        int[] expected = {12, 20, 28, 44, 54, 64, 74, 84};
        for (int level = 1; level <= 8; level++) {
            List<PerLevelBonus> bonuses = level >= 4 ? List.of(TOUGH) : List.of();
            assertEquals(expected[level - 1],
                    HitPoints.maximum(levels("Fighter", 10, level), 2, bonuses, 0), "level " + level);
        }
    }

    @Test
    @DisplayName("A Hill Dwarf gains 1 more hit point at every level")
    void hillDwarf() {
        // A cleric with CON 14 (+2): 8 + 2 + 1 at 1st level, then 5 + 2 + 1 a level
        int[] expected = {11, 19, 27, 35, 43};
        for (int level = 1; level <= 5; level++) {
            assertEquals(expected[level - 1], HitPoints.maximum(levels("Cleric", 8, level), 2,
                    List.of(DWARVEN_TOUGHNESS), 0), "level " + level);
        }
    }

    @Test
    @DisplayName("Draconic Resilience adds 1 hit point for every sorcerer level, and none for levels in other classes")
    void draconicBloodline() {
        // A sorcerer with CON 12 (+1): 6 + 1 + 1 at 1st level, then 4 + 1 + 1 a level
        int[] expected = {8, 14, 20, 26, 32};
        for (int level = 1; level <= 5; level++) {
            assertEquals(expected[level - 1], HitPoints.maximum(levels("Sorcerer", 6, level), 1,
                    List.of(DRACONIC_RESILIENCE), 0), "level " + level);
        }

        // Fighter 2 then Sorcerer 1: 10 + 1, 6 + 1, then 4 + 1 + 1 for the one sorcerer level
        assertEquals(24, HitPoints.maximum(levels("Fighter", 10, 2, "Sorcerer", 6, 1), 1,
                List.of(DRACONIC_RESILIENCE), 0));
    }

    @Test
    @DisplayName("Per-level bonuses add together")
    void bonusesAddTogether() {
        // A Hill Dwarf draconic sorcerer 4 with Tough and CON 14 (+2): 8, then three levels of 4 + 2, so 26; plus 4
        // from Dwarven Toughness, 4 from Draconic Resilience and 8 from Tough
        assertEquals(42, HitPoints.maximum(levels("Sorcerer", 6, 4), 2,
                List.of(DWARVEN_TOUGHNESS, DRACONIC_RESILIENCE, TOUGH), 0));
    }

    @Test
    @DisplayName("A Constitution increase applies to every level, as though the new modifier applied from 1st level")
    void constitutionIncreaseAppliesToEveryLevel() {
        // PHB p.177's example: the modifier rises from +1 to +2 at 4th level, which adds 1 for each of the first
        // three levels, and 4th level gains its own hit points with the new modifier. A fighter: 11 + 7 + 7 = 25 at
        // 3rd level, then 12 + 8 + 8 + 8 = 36 at 4th, against 32 had the modifier stayed +1
        assertEquals(25, HitPoints.maximum(levels("Fighter", 10, 3), 1, List.of(), 0));
        assertEquals(36, HitPoints.maximum(levels("Fighter", 10, 4), 2, List.of(), 0));
        assertEquals(32, HitPoints.maximum(levels("Fighter", 10, 4), 1, List.of(), 0));

        // A wizard 8 whose CON goes from 15 (+2) to 16 (+3) gains 8: 8 + 7 × 6 = 50, then 9 + 7 × 7 = 58
        assertEquals(50, HitPoints.maximum(levels("Wizard", 6, 8), 2, List.of(), 0));
        assertEquals(58, HitPoints.maximum(levels("Wizard", 6, 8), 3, List.of(), 0));
    }

    @Test
    @DisplayName("Each level gives at least 1 hit point, however low the Constitution; per-level bonuses come on top")
    void atLeastOneHitPointPerLevel() {
        // A wizard with CON 3 (-4): 6 - 4 = 2 at 1st level, then 4 - 4 = 0 a level, which counts as 1
        assertEquals(2, HitPoints.maximum(levels("Wizard", 6, 1), -4, List.of(), 0));
        assertEquals(3, HitPoints.maximum(levels("Wizard", 6, 2), -4, List.of(), 0));
        assertEquals(4, HitPoints.maximum(levels("Wizard", 6, 3), -4, List.of(), 0));

        // CON 1 (-5): 6 - 5 = 1 at 1st level, and 4 - 5 counts as 1
        assertEquals(2, HitPoints.maximum(levels("Wizard", 6, 2), -5, List.of(), 0));

        // A Hill Dwarf with CON 3: the 1 from Dwarven Toughness comes on top of each level's minimum of 1
        assertEquals(5, HitPoints.maximum(levels("Wizard", 6, 2), -4, List.of(DWARVEN_TOUGHNESS), 0));
    }

    @Test
    @DisplayName("Flat adjustments are added last, and the maximum is never below 1")
    void flatAdjustments() {
        // A fighter 4 with CON 14: 36, plus or minus an adjustment such as a DM Adjustment
        assertEquals(41, HitPoints.maximum(levels("Fighter", 10, 4), 2, List.of(), 5));
        assertEquals(31, HitPoints.maximum(levels("Fighter", 10, 4), 2, List.of(), -5));
        assertEquals(1, HitPoints.maximum(levels("Fighter", 10, 4), 2, List.of(), -50));
    }

    @Test
    @DisplayName("Impossible levels and hit dice are refused")
    void impossibleInputs() {
        assertThrows(IllegalArgumentException.class, () -> HitPoints.fixedHitDieResult(0, 8));
        assertThrows(IllegalArgumentException.class, () -> HitPoints.fixedHitDieResult(21, 8));
        assertThrows(IllegalArgumentException.class, () -> HitPoints.fixedHitDieResult(2, 7));
        assertThrows(IllegalArgumentException.class, () -> HitPoints.maximum(List.of(), 2, List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> HitPoints.maximum(List.of(new Level("Fighter", 0)), 2, List.of(), 0));
    }

    // Levels in order, each class given as its name, its hit die and how many levels the character takes in it,
    // every hit die result the fixed one typed out above: the die's maximum at character level 1, its average after
    private static List<Level> levels(Object... classes) {
        List<Level> levels = new ArrayList<>();
        for (int i = 0; i < classes.length; i += 3) {
            String className = (String) classes[i];
            int hitDie = (int) classes[i + 1];
            int count = (int) classes[i + 2];
            for (int n = 0; n < count; n++) {
                int result = levels.isEmpty() ? hitDie : hitDie / 2 + 1;
                levels.add(new Level(className, result));
            }
        }
        return levels;
    }
}
