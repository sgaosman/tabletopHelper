package com.tabletophelper.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LevelUpCalculatorTest {

    @Test
    @DisplayName("Level 1 HP gain = full hit dice + CON mod")
    void level1HpGainIsMaxDie() {
        assertEquals(12, LevelUpCalculator.calculateHpGain(1, 10, 2));
        assertEquals(14, LevelUpCalculator.calculateHpGain(1, 12, 2));
        assertEquals(6, LevelUpCalculator.calculateHpGain(1, 8, -2));
    }

    @Test
    @DisplayName("Level 2+ HP gain = average die + CON mod")
    void level2PlusHpGainIsAverage() {
        assertEquals(8, LevelUpCalculator.calculateHpGain(2, 10, 2));
        assertEquals(9, LevelUpCalculator.calculateHpGain(5, 12, 2));
        assertEquals(3, LevelUpCalculator.calculateHpGain(3, 8, -2));
    }

    @Test
    @DisplayName("Standard ASI levels are 4, 8, 12, 16, 19")
    void standardAsiLevels() {
        for (int level : List.of(4, 8, 12, 16, 19)) {
            assertTrue(LevelUpCalculator.isAsiLevel("Wizard", level), "Wizard should have ASI at level " + level);
            assertTrue(LevelUpCalculator.isAsiLevel("Cleric", level), "Cleric should have ASI at level " + level);
        }
        for (int level : List.of(1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15, 17, 18, 20)) {
            assertFalse(LevelUpCalculator.isAsiLevel("Wizard", level), "Wizard should NOT have ASI at level " + level);
        }
    }

    @Test
    @DisplayName("Fighter gets extra ASI at levels 6 and 14")
    void fighterExtraAsiLevels() {
        assertTrue(LevelUpCalculator.isAsiLevel("Fighter", 6));
        assertTrue(LevelUpCalculator.isAsiLevel("Fighter", 14));
        assertFalse(LevelUpCalculator.isAsiLevel("Fighter", 10));
    }

    @Test
    @DisplayName("Rogue gets extra ASI at level 10")
    void rogueExtraAsiLevels() {
        assertTrue(LevelUpCalculator.isAsiLevel("Rogue", 10));
        assertFalse(LevelUpCalculator.isAsiLevel("Rogue", 6));
        assertFalse(LevelUpCalculator.isAsiLevel("Rogue", 14));
    }

    @Test
    @DisplayName("Build single-class progression yields correct total HP")
    void singleClassProgressionHp() {
        UUID classId = UUID.randomUUID();
        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(
                5, classId, "Fighter", 10, 2,
                null, null, null, 99
        );
        assertEquals(5, progression.size());

        // Level 1: 10 + 2 = 12
        assertEquals(12, progression.get(0).hpGained());
        // Levels 2-5: (10/2 + 1) + 2 = 8 each
        for (int i = 1; i < 5; i++) {
            assertEquals(8, progression.get(i).hpGained());
        }

        int totalHp = LevelUpCalculator.totalHp(progression);
        assertEquals(12 + 4 * 8, totalHp);
    }

    @Test
    @DisplayName("Negative CON modifier still applies")
    void negativeConModifier() {
        assertEquals(6, LevelUpCalculator.calculateHpGain(1, 8, -2));
        assertEquals(3, LevelUpCalculator.calculateHpGain(2, 8, -2));
    }

    @Test
    @DisplayName("collectFeaturesForLevel parses class features JSON")
    void collectFeaturesForLevel() {
        String classFeatures = "[{\"name\":\"Second Wind\",\"description\":\"Heal\",\"level\":1},{\"name\":\"Action Surge\",\"description\":\"Extra action\",\"level\":2}]";
        List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(
                classFeatures, null, 1, "Fighter", null);
        assertEquals(1, features.size());
        assertEquals("Second Wind", features.get(0).name());
        assertEquals("Fighter", features.get(0).source());
    }

    @Test
    @DisplayName("collectFeaturesForLevel includes subclass features")
    void collectSubclassFeatures() {
        String subFeatures = "[{\"name\":\"War Magic\",\"description\":\"Bonus to init\",\"level\":2}]";
        List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(
                null, subFeatures, 2, "Wizard", "War Magic");
        assertEquals(1, features.size());
        assertEquals("War Magic", features.get(0).name());
        assertEquals("War Magic", features.get(0).source());
    }

    @Test
    @DisplayName("collectFeaturesForLevel handles null JSON gracefully")
    void collectFeaturesNullJson() {
        List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(
                null, null, 1, "Fighter", null);
        assertTrue(features.isEmpty());
    }
}
