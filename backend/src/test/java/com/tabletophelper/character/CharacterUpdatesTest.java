package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The typed sheet changes that level-up, level-down and choices share. These were CharacterJsonHelperTest's cases
 * 7.1 to 7.16, kept with their numbers; 7.7 and 7.8, the "5d10" hit dice text, are now the sheet's to format
 * (StatsTab.test.tsx), since the server no longer stores it. In R6 workstream C the Hit Dice became a Derived Stat, so
 * 7.4 to 7.6 and the case after them, on updateHitDice, are CharacterStatsTest's Hit Dice cases; 7.14 and 7.15 record
 * on the level that owes the improvement, which owedAsiLevel finds in place of findNextAsiEntry.
 */
class CharacterUpdatesTest {

    private PlayerCharacter baseCharacter() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Test");

        return PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Thorin")
                .level(3)
                .strength(16)
                .dexterity(12)
                .constitution(14)
                .intelligence(10)
                .wisdom(10)
                .charisma(10)
                .hpMax(28)
                .hpCurrent(28)
                .hpTemp(0)
                .armourClass(16)
                .speed(30)
                .proficiencyBonus(2)
                .build();
    }

    private static List<LevelHistoryEntry> fighterHistory(UUID classId, int levels) {
        List<LevelHistoryEntry> history = new ArrayList<>();
        for (int level = 1; level <= levels; level++) {
            history.add(new LevelHistoryEntry(level, classId, "Fighter", level, level == 1 ? 12 : 8,
                    level == 1 ? 10 : 6, List.of(), LevelChoices.NONE, null, null));
        }
        return history;
    }

    // --- 7.1 appendFeatures: adds features to existing array ---

    @Test
    @DisplayName("7.1 appendFeatures adds features to existing array")
    void appendFeaturesToExisting() {
        PlayerCharacter c = baseCharacter();
        c.setFeatures(List.of(new Feature("Second Wind", "d10+level heal", "Fighter")));

        CharacterUpdates.appendFeatures(c, List.of(
                new LevelUpCalculator.FeatureEntry("Action Surge", "Extra action", "Fighter")));

        assertEquals(2, c.getFeatures().size());
        assertEquals("Second Wind", c.getFeatures().get(0).name());
        assertEquals("Action Surge", c.getFeatures().get(1).name());
    }

    // --- 7.2 appendFeatures: handles null/empty existing features ---

    @Test
    @DisplayName("7.2 appendFeatures handles null existing features")
    void appendFeaturesToNull() {
        PlayerCharacter c = baseCharacter();
        c.setFeatures(null);

        CharacterUpdates.appendFeatures(c, List.of(
                new LevelUpCalculator.FeatureEntry("Second Wind", "d10+level heal", "Fighter")));

        assertEquals(1, c.getFeatures().size());
        assertEquals("Second Wind", c.getFeatures().get(0).name());
    }

    // --- 7.3 removeFeatures: removes matching features by name/source ---

    @Test
    @DisplayName("7.3 removeFeatures removes matching features by name and source")
    void removeFeaturesMatching() {
        PlayerCharacter c = baseCharacter();
        c.setFeatures(List.of(new Feature("Second Wind", "d10+level heal", "Fighter"),
                new Feature("Action Surge", "Extra action", "Fighter")));

        CharacterUpdates.removeFeatures(c, List.of(new Feature("Action Surge", "Extra action", "Fighter")));

        assertEquals(1, c.getFeatures().size());
        assertEquals("Second Wind", c.getFeatures().get(0).name());
    }

    @Test
    @DisplayName("removeFeatures takes away one copy for each feature gained, the latest, so earlier levels keep theirs")
    void removeFeaturesTakesOneCopy() {
        PlayerCharacter c = baseCharacter();
        Feature asi = new Feature("Ability Score Improvement", "Level 4", "Fighter");
        Feature laterAsi = new Feature("Ability Score Improvement", "Level 6", "Fighter");
        c.setFeatures(List.of(asi, new Feature("Extra Attack", null, "Fighter"), laterAsi));

        CharacterUpdates.removeFeatures(c, List.of(new Feature("Ability Score Improvement", "Level 6", "Fighter")));

        assertEquals(List.of(asi, new Feature("Extra Attack", null, "Fighter")), c.getFeatures());
    }

    // --- 7.9 updateClassEntry: add new class entry ---

    @Test
    @DisplayName("7.9 updateClassEntry adds new class entry")
    void updateClassEntryAddNew() {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();
        c.setMulticlassEntries(List.of(new ClassEntry(fighterId, "Fighter", null, null, 3)));

        CharacterClass wizardClass = new CharacterClass();
        wizardClass.setId(wizardId);
        wizardClass.setName("Wizard");

        Map<UUID, Integer> classLevels = new LinkedHashMap<>();
        classLevels.put(fighterId, 3);
        classLevels.put(wizardId, 1);

        CharacterUpdates.updateClassEntry(c, wizardId, wizardClass, null, classLevels);

        List<ClassEntry> entries = c.getMulticlassEntries();
        assertEquals(2, entries.size());
        assertEquals("Fighter", entries.get(0).className());
        assertEquals("Wizard", entries.get(1).className());
        assertEquals(1, entries.get(1).level());
    }

    // --- 7.10 updateClassEntry: increment existing class level ---

    @Test
    @DisplayName("7.10 updateClassEntry increments existing class level")
    void updateClassEntryIncrement() {
        PlayerCharacter c = baseCharacter();
        UUID wizardId = UUID.randomUUID();
        c.setMulticlassEntries(List.of(new ClassEntry(wizardId, "Wizard", null, null, 1)));

        CharacterClass wizardClass = new CharacterClass();
        wizardClass.setId(wizardId);
        wizardClass.setName("Wizard");

        CharacterUpdates.updateClassEntry(c, wizardId, wizardClass, null, Map.of(wizardId, 2));

        assertEquals(1, c.getMulticlassEntries().size());
        assertEquals(2, c.getMulticlassEntries().get(0).level());
    }

    // --- 7.11 rebuildClassEntries: rebuild after level down ---

    @Test
    @DisplayName("7.11 rebuildClassEntries rebuilds with updated levels")
    void rebuildClassEntries() {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();
        c.setMulticlassEntries(List.of(new ClassEntry(wizardId, "Wizard", null, null, 3),
                new ClassEntry(fighterId, "Fighter", null, null, 2)));

        Map<UUID, Integer> classLevels = new LinkedHashMap<>();
        classLevels.put(wizardId, 3);
        classLevels.put(fighterId, 1);

        CharacterUpdates.rebuildClassEntries(c, classLevels);

        List<ClassEntry> entries = c.getMulticlassEntries();
        assertEquals(2, entries.size());
        assertEquals(3, entries.stream().filter(e -> e.className().equals("Wizard")).findFirst().get().level());
        assertEquals(1, entries.stream().filter(e -> e.className().equals("Fighter")).findFirst().get().level());
    }

    // --- 7.12 merged: merge without duplicates ---

    @Test
    @DisplayName("7.12 merged merges without duplicates")
    void mergedNoDuplicates() {
        PlayerCharacter c = baseCharacter();
        c.setWeaponProficiencies(List.of("Longsword", "Shortsword"));

        c.setWeaponProficiencies(CharacterUpdates.merged(c.getWeaponProficiencies(), List.of("Shortsword", "Longbow")));

        assertEquals(List.of("Longsword", "Shortsword", "Longbow"), c.getWeaponProficiencies());
    }

    @Test
    @DisplayName("added lists what merging would add, once each; removeProficiencies takes away only what a level added")
    void addedAndRemovedProficiencies() {
        PlayerCharacter c = baseCharacter();
        c.setArmorProficiencies(List.of("Light"));
        c.setToolProficiencies(null);

        assertEquals(List.of("Medium", "Shield"),
                CharacterUpdates.added(c.getArmorProficiencies(), List.of("Light", "Medium", "Shield", "Medium")));

        c.setArmorProficiencies(List.of("Light", "Medium", "Shield"));
        CharacterUpdates.removeProficiencies(c, new ProficienciesGained(List.of("Medium", "Shield"), null, List.of()));

        assertEquals(List.of("Light"), c.getArmorProficiencies());
        // A kind the level added none of is left as it was, null included
        assertNull(c.getToolProficiencies());
    }

    // --- 7.13 appendLevelHistory: adds correct entry ---

    @Test
    @DisplayName("7.13 appendLevelHistory adds correct entry")
    void appendLevelHistory() {
        PlayerCharacter c = baseCharacter();
        UUID classId = UUID.randomUUID();
        c.setLevelHistory(List.of(
                new LevelHistoryEntry(1, classId, "Fighter", 1, 12, 10,
                        List.of(new Feature("Second Wind", "Heal", "Fighter")), LevelChoices.NONE, null, null),
                new LevelHistoryEntry(2, classId, "Fighter", 2, 8, 6,
                        List.of(new Feature("Action Surge", "Extra action", "Fighter")), LevelChoices.NONE, null, null)));

        CharacterUpdates.appendLevelHistory(c, 3, classId, "Fighter", 3, 6, List.of(
                new LevelUpCalculator.FeatureEntry("Martial Archetype", "Choose a subclass", "Fighter")),
                new ProficienciesGained(List.of(), null, null), List.of());
        CharacterUpdates.recordHpGained(c, 8);

        List<LevelHistoryEntry> history = c.getLevelHistory();
        assertEquals(3, history.size());
        LevelHistoryEntry entry = history.get(2);
        assertEquals(3, entry.characterLevel());
        assertEquals(classId, entry.classId());
        assertEquals(3, entry.classLevel());
        assertEquals(8, entry.hpGained());
        assertEquals(6, entry.hitDieResult());
        assertEquals("Martial Archetype", entry.featuresGained().get(0).name());
        assertEquals(LevelChoices.NONE, entry.choices());
        // A level that added no proficiencies or ability increases records none
        assertNull(entry.proficienciesGained());
        assertNull(entry.abilityIncreases());
    }

    // --- 7.14 recordAsi: ASI choice recorded ---

    @Test
    @DisplayName("7.14 recordAsi records the ASI choice, with the amount applied, on the level that owes it")
    void recordAsi() {
        PlayerCharacter c = baseCharacter();
        c.setLevel(4);
        c.setLevelHistory(fighterHistory(UUID.randomUUID(), 4));

        int owed = CharacterUpdates.owedAsiLevel(c.getLevelHistory());
        CharacterUpdates.recordAsi(c, owed, new AbilityScoreImprovement("ability",
                List.of(new AbilityBonus("strength", 2)), null, null, null, null,
                List.of(new AbilityBonus("strength", 2))));

        assertEquals(3, owed);
        AbilityScoreImprovement recorded = c.getLevelHistory().get(3).choices().asi();
        assertNotNull(recorded);
        assertEquals("ability", recorded.type());
        assertEquals(List.of(new AbilityBonus("strength", 2)), recorded.increases());
        assertEquals(List.of(new AbilityBonus("strength", 2)), recorded.applied());
        assertNull(c.getLevelHistory().get(2).choices().asi());
    }

    // --- 7.15 recordFeatInHistory: feat choice recorded ---

    @Test
    @DisplayName("7.15 recordAsi records a feat choice with only the effects it had")
    void recordFeatInHistory() {
        PlayerCharacter c = baseCharacter();
        c.setLevel(4);
        c.setLevelHistory(fighterHistory(UUID.randomUUID(), 4));

        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("feat");
        asi.setFeatName("Alert");
        asi.setFeatId(UUID.randomUUID());

        FeatEffectResolver.AppliedEffects applied = new FeatEffectResolver.AppliedEffects(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 5, 0, 0, 0, null, List.of(), List.of()
        );

        CharacterUpdates.recordAsi(c, CharacterUpdates.owedAsiLevel(c.getLevelHistory()),
                new AbilityScoreImprovement("feat", null, "Alert", asi.getFeatId(), null,
                        CharacterUpdates.recordedEffects(applied, "Alert"), null));

        AbilityScoreImprovement recorded = c.getLevelHistory().get(3).choices().asi();
        assertNotNull(recorded);
        assertEquals("feat", recorded.type());
        assertEquals("Alert", recorded.featName());
        assertEquals(asi.getFeatId(), recorded.featId());
        // Only what the feat changed is recorded
        assertEquals(5, recorded.appliedEffects().initiativeBonus());
        assertNull(recorded.appliedEffects().speedBonus());
        assertNull(recorded.appliedEffects().resistancesAdded());
        assertEquals("Alert", recorded.appliedEffects().featName());
    }

    @Test
    @DisplayName("An ASI is owed by the latest ASI level without one, so one taken at 6th level isn't put on 4th; none owed is -1")
    void owedAsiLevel() {
        UUID classId = UUID.randomUUID();
        List<LevelHistoryEntry> history = fighterHistory(classId, 7);
        // Fighters also improve at level 6
        assertEquals(5, CharacterUpdates.owedAsiLevel(history));

        AbilityScoreImprovement taken = new AbilityScoreImprovement("ability",
                List.of(new AbilityBonus("strength", 2)), null, null, null, null, null);
        history.set(5, history.get(5).withChoices(LevelChoices.NONE.withAsi(taken)));
        assertEquals(3, CharacterUpdates.owedAsiLevel(history));

        history.set(3, history.get(3).withChoices(LevelChoices.NONE.withAsi(taken)));
        assertEquals(-1, CharacterUpdates.owedAsiLevel(history));
        assertEquals(-1, CharacterUpdates.owedAsiLevel(List.of()));
    }

    @Test
    @DisplayName("Expertise is owed by the latest level that gives it, and recordExpertise records the skills there")
    void owedExpertise() {
        UUID rogueId = UUID.randomUUID();
        List<LevelHistoryEntry> history = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            history.add(new LevelHistoryEntry(level, rogueId, "Rogue", level, 6, level == 1 ? 8 : 5, List.of(),
                    LevelChoices.NONE, null, null));
        }
        PlayerCharacter c = baseCharacter();
        c.setLevelHistory(history);

        int owed = CharacterUpdates.owedExpertiseLevel(history);
        CharacterUpdates.recordExpertise(c, owed, List.of("Stealth", "Perception"));

        assertEquals(5, owed);
        assertEquals(List.of("Stealth", "Perception"), c.getLevelHistory().get(5).choices().expertise());
        // Rogue 1st level gives Expertise too
        assertEquals(0, CharacterUpdates.owedExpertiseLevel(c.getLevelHistory()));
    }

    @Test
    @DisplayName("increaseAbility adds what fits under the maximum, 24 for Strength after Primal Champion; decreaseAbility takes off what it is given")
    void increaseAndDecreaseAbility() {
        PlayerCharacter c = baseCharacter();
        c.setStrength(19);

        assertEquals(1, CharacterUpdates.increaseAbility(c, "strength", 2));
        assertEquals(20, c.getStrength());

        c.setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Barbarian", null, null, 20)));
        assertEquals(4, CharacterUpdates.increaseAbility(c, "STR", 4));
        assertEquals(24, c.getStrength());

        CharacterUpdates.decreaseAbility(c, "Strength", 4);
        assertEquals(20, c.getStrength());
        assertThrows(ValidationException.class, () -> CharacterUpdates.increaseAbility(c, "luck", 1));
    }

    // --- 7.16 setClassEntrySubclass: sets subclass ---

    @Test
    @DisplayName("7.16 setClassEntrySubclass sets subclass on matching entry")
    void setClassEntrySubclass() {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID championId = UUID.randomUUID();
        c.setMulticlassEntries(List.of(new ClassEntry(fighterId, "Fighter", null, null, 3)));

        Subclass champion = Subclass.builder()
                .id(championId)
                .name("Champion")
                .build();

        CharacterUpdates.setClassEntrySubclass(c, fighterId, champion);

        List<ClassEntry> entries = c.getMulticlassEntries();
        assertEquals(1, entries.size());
        assertEquals(championId, entries.get(0).subclassId());
        assertEquals("Champion", entries.get(0).subclassName());
        assertEquals(3, entries.get(0).level());
        assertEquals(championId, CharacterUpdates.subclassIdOf(c, fighterId));
        assertNull(CharacterUpdates.subclassIdOf(c, UUID.randomUUID()));
    }
}
