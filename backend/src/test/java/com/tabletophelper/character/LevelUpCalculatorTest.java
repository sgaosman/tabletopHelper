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

    @Test
    @DisplayName("HP with d6 hit die: Wizard level 1 max 6 + CON, level 2 average 4 + CON")
    void d6HitDieHpCalculation() {
        // Level 1 Wizard with CON 12 (+1): max d6 (6) + 1 = 7
        assertEquals(7, LevelUpCalculator.calculateHpGain(1, 6, 1));
        // Level 2: average d6 (6/2+1=4) + 1 = 5
        assertEquals(5, LevelUpCalculator.calculateHpGain(2, 6, 1));
    }

    @Test
    @DisplayName("HP with d12 hit die: Barbarian level 1 max 12 + CON, level 2 average 7 + CON")
    void d12HitDieHpCalculation() {
        // Level 1 Barbarian with CON 16 (+3): max d12 (12) + 3 = 15
        assertEquals(15, LevelUpCalculator.calculateHpGain(1, 12, 3));
        // Level 2: average d12 (12/2+1=7) + 3 = 10
        assertEquals(10, LevelUpCalculator.calculateHpGain(2, 12, 3));
    }

    @Test
    @DisplayName("Multiclass progression uses new class hit die for HP")
    void multiclassProgressionUsesNewClassHitDie() {
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();
        int conMod = 2; // CON 14

        List<LevelUpCalculator.ClassInput> classInputs = List.of(
                new LevelUpCalculator.ClassInput(fighterId, "Fighter", 3, 10,
                        null, null, null, 99),
                new LevelUpCalculator.ClassInput(wizardId, "Wizard", 1, 6,
                        null, null, null, 99)
        );

        List<LevelUpCalculator.LevelGain> progression =
                LevelUpCalculator.buildMulticlassProgression(classInputs, conMod);
        assertEquals(4, progression.size());

        // Fighter level 1 (character level 1): max d10 + CON = 12
        assertEquals(12, progression.get(0).hpGained());
        assertEquals("Fighter", progression.get(0).className());

        // Fighter levels 2-3: avg d10 (6) + CON (2) = 8
        assertEquals(8, progression.get(1).hpGained());
        assertEquals(8, progression.get(2).hpGained());

        // Wizard level 1 (character level 4): avg d6 (4) + CON (2) = 6
        // Not max die because this is not the first class in the list
        assertEquals(6, progression.get(3).hpGained());
        assertEquals("Wizard", progression.get(3).className());
    }

    @Test
    @DisplayName("collectFeaturesForLevel filters by exact level match")
    void collectFeaturesFiltersByLevel() {
        String classFeatures = "[{\"name\":\"Extra Attack\",\"description\":\"Two attacks\",\"level\":5},"
                + "{\"name\":\"Indomitable\",\"description\":\"Reroll save\",\"level\":9}]";
        List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(
                classFeatures, null, 3, "Fighter", null);
        assertTrue(features.isEmpty(), "Level 3 should have no features from level 5 or 9");
    }

    @Test
    @DisplayName("collectFeaturesForLevel includes both class and subclass features at same level")
    void collectClassAndSubclassFeaturesAtSameLevel() {
        String classFeatures = "[{\"name\":\"Channel Divinity\",\"description\":\"Use channel divinity\",\"level\":2}]";
        String subclassFeatures = "[{\"name\":\"Preserve Life\",\"description\":\"Restore HP\",\"level\":2}]";
        List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(
                classFeatures, subclassFeatures, 2, "Cleric", "Life Domain");
        assertEquals(2, features.size());

        List<String> names = features.stream().map(LevelUpCalculator.FeatureEntry::name).toList();
        assertTrue(names.contains("Channel Divinity"));
        assertTrue(names.contains("Preserve Life"));

        // Verify sources are tagged correctly
        assertEquals("Cleric", features.stream()
                .filter(f -> f.name().equals("Channel Divinity")).findFirst().get().source());
        assertEquals("Life Domain", features.stream()
                .filter(f -> f.name().equals("Preserve Life")).findFirst().get().source());
    }

    @Test
    @DisplayName("buildProgression total levels matches input")
    void buildProgressionTotalLevelsMatchInput() {
        UUID classId = UUID.randomUUID();
        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(
                5, classId, "Fighter", 10, 2,
                null, null, null, 99);
        assertEquals(5, progression.size());

        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, progression.get(i).characterLevel());
            assertEquals(i + 1, progression.get(i).classLevel());
            assertEquals("Fighter", progression.get(i).className());
        }
    }

    @Test
    @DisplayName("buildProgression with existing HP: level 4 gain adds to existing base")
    void buildProgressionWithExistingHp() {
        UUID classId = UUID.randomUUID();
        int conMod = 2; // CON 14

        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(
                4, classId, "Fighter", 10, conMod,
                null, null, null, 99);

        // Levels 1-3 give: (10+2) + (6+2) + (6+2) = 12 + 8 + 8 = 28
        int hpAfter3 = progression.subList(0, 3).stream()
                .mapToInt(LevelUpCalculator.LevelGain::hpGained).sum();
        assertEquals(28, hpAfter3);

        // Level 4 adds: avg d10 (6) + CON mod (2) = 8
        assertEquals(8, progression.get(3).hpGained());

        // Total HP = 28 + 8 = 36
        assertEquals(36, LevelUpCalculator.totalHp(progression));
    }
}
