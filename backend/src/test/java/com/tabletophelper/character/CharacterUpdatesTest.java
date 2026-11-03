package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The typed sheet changes that level-up, level-down and choices share. These were CharacterJsonHelperTest's cases
 * 7.1 to 7.16, kept with their numbers; 7.7 and 7.8, the "5d10" hit dice text, are now the sheet's to format
 * (StatsTab.test.tsx), since the server no longer stores it.
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
            history.add(new LevelHistoryEntry(level, classId, "Fighter", level, level == 1 ? 12 : 8, List.of(),
                    LevelChoices.NONE));
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

    // --- 7.4 updateHitDice: increment on level up ---

    @Test
    @DisplayName("7.4 updateHitDice increments count on level up")
    void updateHitDiceIncrement() {
        PlayerCharacter c = baseCharacter();
        c.setHitDice(Map.of("Fighter", new HitDicePool(10, 3, 3)));

        CharacterUpdates.updateHitDice(c, "Fighter", 10, 1);

        assertEquals(new HitDicePool(10, 4, 4), c.getHitDice().get("Fighter"));
    }

    // --- 7.5 updateHitDice: decrement on level down ---

    @Test
    @DisplayName("7.5 updateHitDice decrements count on level down")
    void updateHitDiceDecrement() {
        PlayerCharacter c = baseCharacter();
        c.setHitDice(Map.of("Fighter", new HitDicePool(10, 4, 4)));

        CharacterUpdates.updateHitDice(c, "Fighter", 10, -1);

        assertEquals(3, c.getHitDice().get("Fighter").total());
        assertEquals(3, c.getHitDice().get("Fighter").remaining());
    }

    // --- 7.6 updateHitDice: remove class entry at count 0 ---

    @Test
    @DisplayName("7.6 updateHitDice removes class entry when count reaches 0")
    void updateHitDiceRemoveAtZero() {
        PlayerCharacter c = baseCharacter();
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
        hitDice.put("Fighter", new HitDicePool(10, 3, 3));
        hitDice.put("Wizard", new HitDicePool(6, 1, 1));
        c.setHitDice(hitDice);

        CharacterUpdates.updateHitDice(c, "Wizard", 6, -1);

        assertNull(c.getHitDice().get("Wizard"));
        assertEquals(3, c.getHitDice().get("Fighter").total());
    }

    @Test
    @DisplayName("updateHitDice never leaves fewer than 0 remaining, and starts a class the character didn't have")
    void updateHitDiceClampsRemainingAndAddsAClass() {
        PlayerCharacter c = baseCharacter();
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
        hitDice.put("Fighter", new HitDicePool(10, 3, 0));
        c.setHitDice(hitDice);

        CharacterUpdates.updateHitDice(c, "Fighter", 10, -1);
        CharacterUpdates.updateHitDice(c, "Wizard", 6, 1);

        assertEquals(new HitDicePool(10, 2, 0), c.getHitDice().get("Fighter"));
        assertEquals(new HitDicePool(6, 1, 1), c.getHitDice().get("Wizard"));
        assertEquals(List.of("Fighter", "Wizard"), new ArrayList<>(c.getHitDice().keySet()));
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

    // --- 7.13 appendLevelHistory: adds correct entry ---

    @Test
    @DisplayName("7.13 appendLevelHistory adds correct entry")
    void appendLevelHistory() {
        PlayerCharacter c = baseCharacter();
        UUID classId = UUID.randomUUID();
        c.setLevelHistory(List.of(
                new LevelHistoryEntry(1, classId, "Fighter", 1, 12,
                        List.of(new Feature("Second Wind", "Heal", "Fighter")), LevelChoices.NONE),
                new LevelHistoryEntry(2, classId, "Fighter", 2, 8,
                        List.of(new Feature("Action Surge", "Extra action", "Fighter")), LevelChoices.NONE)));

        CharacterUpdates.appendLevelHistory(c, 3, classId, "Fighter", 3, 8, List.of(
                new LevelUpCalculator.FeatureEntry("Martial Archetype", "Choose a subclass", "Fighter")));

        List<LevelHistoryEntry> history = c.getLevelHistory();
        assertEquals(3, history.size());
        LevelHistoryEntry entry = history.get(2);
        assertEquals(3, entry.characterLevel());
        assertEquals(classId, entry.classId());
        assertEquals(3, entry.classLevel());
        assertEquals(8, entry.hpGained());
        assertEquals("Martial Archetype", entry.featuresGained().get(0).name());
        assertEquals(LevelChoices.NONE, entry.choices());
    }

    // --- 7.14 recordAsiInHistory: ASI choice recorded ---

    @Test
    @DisplayName("7.14 recordAsiInHistory records ASI choice on correct entry")
    void recordAsiInHistory() {
        PlayerCharacter c = baseCharacter();
        c.setLevel(4);
        c.setLevelHistory(fighterHistory(UUID.randomUUID(), 4));

        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("ability");
        ApplyChoicesRequest.AbilityIncrease inc = new ApplyChoicesRequest.AbilityIncrease();
        inc.setAbility("strength");
        inc.setBonus(2);
        asi.setIncreases(List.of(inc));

        CharacterUpdates.recordAsiInHistory(c, asi);

        AbilityScoreImprovement recorded = c.getLevelHistory().get(3).choices().asi();
        assertNotNull(recorded);
        assertEquals("ability", recorded.type());
        assertEquals(List.of(new AbilityBonus("strength", 2)), recorded.increases());
        assertNull(c.getLevelHistory().get(2).choices().asi());
    }

    // --- 7.15 recordFeatInHistory: feat choice recorded ---

    @Test
    @DisplayName("7.15 recordFeatInHistory records feat choice with applied effects")
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

        CharacterUpdates.recordFeatInHistory(c, asi, "Alert", applied);

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
    @DisplayName("The next ASI goes on the first ASI level without one, or else on the last level")
    void findNextAsiEntry() {
        UUID classId = UUID.randomUUID();
        List<LevelHistoryEntry> history = fighterHistory(classId, 6);
        assertSame(history.get(3), CharacterUpdates.findNextAsiEntry(history));

        AbilityScoreImprovement taken = new AbilityScoreImprovement("ability",
                List.of(new AbilityBonus("strength", 2)), null, null, null, null);
        history.set(3, history.get(3).withChoices(new LevelChoices(taken)));
        // Fighters also improve at level 6
        assertSame(history.get(5), CharacterUpdates.findNextAsiEntry(history));

        history.set(5, history.get(5).withChoices(new LevelChoices(taken)));
        assertSame(history.get(5), CharacterUpdates.findNextAsiEntry(history));
        assertNull(CharacterUpdates.findNextAsiEntry(List.of()));
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
