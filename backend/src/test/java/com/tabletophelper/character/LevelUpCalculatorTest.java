package com.tabletophelper.character;

import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.TestClassTables;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The levels a character is created with. Since R6 workstream C each level records its Hit Die Result rather than the
 * hit points it gave, and the rules behind them are rules.character's: the HP gain cases became Hit Die Result cases
 * here, with the arithmetic in HitPointsGoldenTest and the hit point maximums in CharacterStatsTest, and the ASI level
 * cases are ClassProgressionGoldenTest's.
 */
class LevelUpCalculatorTest {

    private static final ClassTables TABLES = TestClassTables.load();

    @Test
    @DisplayName("Build single-class progression: the die's maximum at level 1 and its fixed average after")
    void singleClassProgressionHitDieResults() {
        UUID classId = UUID.randomUUID();
        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(TABLES, 
                5, classId, "Fighter", 10,
                null, null, null, 99
        );
        assertEquals(5, progression.size());

        assertEquals(10, progression.get(0).hitDieResult());
        for (int i = 1; i < 5; i++) {
            assertEquals(6, progression.get(i).hitDieResult());
        }
        // Fighters improve at 4th level
        assertTrue(progression.get(3).asiAvailable());
        assertFalse(progression.get(4).asiAvailable());
    }

    @Test
    @DisplayName("Hit Die Results with a d6 and a d12: 6 then 4, and 12 then 7")
    void d6AndD12HitDieResults() {
        List<LevelUpCalculator.LevelGain> wizard = LevelUpCalculator.buildProgression(TABLES, 
                2, UUID.randomUUID(), "Wizard", 6, null, null, null, 2);
        List<LevelUpCalculator.LevelGain> barbarian = LevelUpCalculator.buildProgression(TABLES, 
                2, UUID.randomUUID(), "Barbarian", 12, null, null, null, 3);

        assertEquals(List.of(6, 4), wizard.stream().map(LevelUpCalculator.LevelGain::hitDieResult).toList());
        assertEquals(List.of(12, 7), barbarian.stream().map(LevelUpCalculator.LevelGain::hitDieResult).toList());
        assertTrue(wizard.get(1).subclassAvailable());
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
    @DisplayName("Class features that can't be read are a 409, not a level without them (D155)")
    void unreadableFeaturesAreConflict() {
        ConflictException e = assertThrows(ConflictException.class, () -> LevelUpCalculator.collectFeaturesForLevel(
                "{not json", null, 1, "Fighter", null));
        assertEquals("The features of the class Fighter can't be read", e.getMessage());
    }

    @Test
    @DisplayName("Multiclass progression: only the first class's first level has the die's maximum (PHB p.163)")
    void multiclassProgressionUsesNewClassHitDie() {
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();

        List<LevelUpCalculator.ClassInput> classInputs = List.of(
                new LevelUpCalculator.ClassInput(fighterId, "Fighter", 3, 10,
                        null, null, null, 99),
                new LevelUpCalculator.ClassInput(wizardId, "Wizard", 1, 6,
                        null, null, null, 99)
        );

        List<LevelUpCalculator.LevelGain> progression =
                LevelUpCalculator.buildMulticlassProgression(TABLES, classInputs);
        assertEquals(4, progression.size());

        // Fighter level 1 (character level 1): the d10's maximum
        assertEquals(10, progression.get(0).hitDieResult());
        assertEquals("Fighter", progression.get(0).className());

        // Fighter levels 2-3: the d10's fixed value, 6
        assertEquals(6, progression.get(1).hitDieResult());
        assertEquals(6, progression.get(2).hitDieResult());

        // Wizard level 1 (character level 4): the d6's fixed value, not its maximum
        assertEquals(4, progression.get(3).hitDieResult());
        assertEquals("Wizard", progression.get(3).className());
        assertEquals(4, progression.get(3).characterLevel());
        assertEquals(1, progression.get(3).classLevel());
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
        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(TABLES, 
                5, classId, "Fighter", 10,
                null, null, null, 99);
        assertEquals(5, progression.size());

        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, progression.get(i).characterLevel());
            assertEquals(i + 1, progression.get(i).classLevel());
            assertEquals("Fighter", progression.get(i).className());
        }
    }

    @Test
    @DisplayName("levelHistory records each level's Hit Die Result with no choices, its hit points to be filled in")
    void levelHistoryRecordsHitDieResults() {
        UUID classId = UUID.randomUUID();
        List<LevelHistoryEntry> history = LevelUpCalculator.levelHistory(LevelUpCalculator.buildProgression(TABLES, 
                2, classId, "Fighter", 10, null, null, null, 3));

        assertEquals(List.of(10, 6), history.stream().map(LevelHistoryEntry::hitDieResult).toList());
        assertEquals(List.of(0, 0), history.stream().map(LevelHistoryEntry::hpGained).toList());
        assertEquals(LevelChoices.NONE, history.get(1).choices());
        assertEquals(classId, history.get(1).classId());
    }
}
