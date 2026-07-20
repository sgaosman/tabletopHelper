package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.HitDiceEntry;
import com.tabletophelper.character.dto.LevelHistoryEntry;
import com.tabletophelper.character.dto.LevelHistoryEntry.FeatureRecord;
import com.tabletophelper.character.dto.MulticlassEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CharacterJsonHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CharacterJsonHelper helper;

    @BeforeEach
    void setUp() {
        helper = new CharacterJsonHelper(objectMapper);
    }

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
                .features(null)
                .armorProficiencies(null)
                .weaponProficiencies(null)
                .toolProficiencies(null)
                .multiclassEntries(null)
                .hitDiceMap(null)
                .levelHistory(null)
                .build();
    }

    // --- 7.1 appendFeatures: adds features to existing array ---

    @Test
    @DisplayName("7.1 appendFeatures adds features to existing array")
    void appendFeaturesToExisting() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setFeatures("[{\"name\":\"Second Wind\",\"description\":\"d10+level heal\",\"source\":\"Fighter\"}]");

        List<LevelUpCalculator.FeatureEntry> newFeatures = List.of(
                new LevelUpCalculator.FeatureEntry("Action Surge", "Extra action", "Fighter")
        );

        helper.appendFeatures(c, newFeatures);

        List<FeatureRecord> features = objectMapper.readValue(c.getFeatures(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, FeatureRecord.class));
        assertEquals(2, features.size());
        assertEquals("Second Wind", features.get(0).name());
        assertEquals("Action Surge", features.get(1).name());
    }

    // --- 7.2 appendFeatures: handles null/empty existing features ---

    @Test
    @DisplayName("7.2 appendFeatures handles null existing features")
    void appendFeaturesToNull() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setFeatures(null);

        List<LevelUpCalculator.FeatureEntry> newFeatures = List.of(
                new LevelUpCalculator.FeatureEntry("Second Wind", "d10+level heal", "Fighter")
        );

        helper.appendFeatures(c, newFeatures);

        List<FeatureRecord> features = objectMapper.readValue(c.getFeatures(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, FeatureRecord.class));
        assertEquals(1, features.size());
        assertEquals("Second Wind", features.get(0).name());
    }

    // --- 7.3 removeFeatures: removes matching features by name/source ---

    @Test
    @DisplayName("7.3 removeFeatures removes matching features by name and source")
    void removeFeaturesMatching() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setFeatures("[{\"name\":\"Second Wind\",\"description\":\"d10+level heal\",\"source\":\"Fighter\"},"
                + "{\"name\":\"Action Surge\",\"description\":\"Extra action\",\"source\":\"Fighter\"}]");

        List<FeatureRecord> toRemove = List.of(
                new FeatureRecord("Action Surge", "Extra action", "Fighter")
        );

        helper.removeFeatures(c, toRemove);

        List<FeatureRecord> features = objectMapper.readValue(c.getFeatures(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, FeatureRecord.class));
        assertEquals(1, features.size());
        assertEquals("Second Wind", features.get(0).name());
    }

    // --- 7.4 updateHitDiceMap: increment on level up ---

    @Test
    @DisplayName("7.4 updateHitDiceMap increments count on level up")
    void updateHitDiceMapIncrement() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setHitDiceMap("{\"Fighter\":{\"total\":3,\"remaining\":3,\"faces\":10}}");

        helper.updateHitDiceMap(c, "Fighter", 10, 1);

        Map<String, HitDiceEntry> hdMap = objectMapper.readValue(c.getHitDiceMap(),
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, HitDiceEntry.class));
        assertEquals(4, hdMap.get("Fighter").total());
        assertEquals(4, hdMap.get("Fighter").remaining());
        assertEquals(10, hdMap.get("Fighter").faces());
    }

    // --- 7.5 updateHitDiceMap: decrement on level down ---

    @Test
    @DisplayName("7.5 updateHitDiceMap decrements count on level down")
    void updateHitDiceMapDecrement() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setHitDiceMap("{\"Fighter\":{\"total\":4,\"remaining\":4,\"faces\":10}}");

        helper.updateHitDiceMap(c, "Fighter", 10, -1);

        Map<String, HitDiceEntry> hdMap = objectMapper.readValue(c.getHitDiceMap(),
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, HitDiceEntry.class));
        assertEquals(3, hdMap.get("Fighter").total());
        assertEquals(3, hdMap.get("Fighter").remaining());
    }

    // --- 7.6 updateHitDiceMap: remove class entry at count 0 ---

    @Test
    @DisplayName("7.6 updateHitDiceMap removes class entry when count reaches 0")
    void updateHitDiceMapRemoveAtZero() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setHitDiceMap("{\"Fighter\":{\"total\":3,\"remaining\":3,\"faces\":10},\"Wizard\":{\"total\":1,\"remaining\":1,\"faces\":6}}");

        helper.updateHitDiceMap(c, "Wizard", 6, -1);

        Map<String, HitDiceEntry> hdMap = objectMapper.readValue(c.getHitDiceMap(),
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, HitDiceEntry.class));
        assertNull(hdMap.get("Wizard"));
        assertNotNull(hdMap.get("Fighter"));
        assertEquals(3, hdMap.get("Fighter").total());
    }

    // --- 7.7 buildHitDiceTotal: multi-class format ---

    @Test
    @DisplayName("7.7 buildHitDiceTotal formats multi-class hit dice")
    void buildHitDiceTotalMulticlass() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setHitDiceMap("{\"Fighter\":{\"total\":2,\"remaining\":2,\"faces\":10},\"Wizard\":{\"total\":3,\"remaining\":3,\"faces\":6}}");

        String result = helper.buildHitDiceTotal(c);

        // Should contain both entries joined by " + "
        assertTrue(result.contains("d10"));
        assertTrue(result.contains("d6"));
        assertTrue(result.contains(" + "));
    }

    // --- 7.8 buildHitDiceTotal: single class format ---

    @Test
    @DisplayName("7.8 buildHitDiceTotal formats single class hit dice")
    void buildHitDiceTotalSingleClass() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setHitDiceMap("{\"Fighter\":{\"total\":5,\"remaining\":5,\"faces\":10}}");

        String result = helper.buildHitDiceTotal(c);

        assertEquals("5d10", result);
    }

    // --- 7.9 updateMulticlassEntries: add new class entry ---

    @Test
    @DisplayName("7.9 updateMulticlassEntries adds new class entry")
    void updateMulticlassEntriesAddNew() throws Exception {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();

        // Start with Fighter only
        String existingEntries = objectMapper.writeValueAsString(List.of(
                new MulticlassEntry(fighterId.toString(), "Fighter", null, null, 3)));
        c.setMulticlassEntries(existingEntries);

        CharacterClass wizardClass = new CharacterClass();
        wizardClass.setId(wizardId);
        wizardClass.setName("Wizard");

        Map<String, Integer> classLevels = new LinkedHashMap<>();
        classLevels.put(fighterId.toString(), 3);
        classLevels.put(wizardId.toString(), 1);

        helper.updateMulticlassEntries(c, wizardId, wizardClass, null, classLevels, true);

        List<MulticlassEntry> entries = objectMapper.readValue(c.getMulticlassEntries(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MulticlassEntry.class));
        assertEquals(2, entries.size());
        assertEquals("Fighter", entries.get(0).className());
        assertEquals("Wizard", entries.get(1).className());
        assertEquals(1, entries.get(1).level());
    }

    // --- 7.10 updateMulticlassEntries: increment existing class level ---

    @Test
    @DisplayName("7.10 updateMulticlassEntries increments existing class level")
    void updateMulticlassEntriesIncrement() throws Exception {
        PlayerCharacter c = baseCharacter();
        UUID wizardId = UUID.randomUUID();

        String existingEntries = objectMapper.writeValueAsString(List.of(
                new MulticlassEntry(wizardId.toString(), "Wizard", null, null, 1)));
        c.setMulticlassEntries(existingEntries);

        CharacterClass wizardClass = new CharacterClass();
        wizardClass.setId(wizardId);
        wizardClass.setName("Wizard");

        Map<String, Integer> classLevels = Map.of(wizardId.toString(), 2);

        helper.updateMulticlassEntries(c, wizardId, wizardClass, null, classLevels, false);

        List<MulticlassEntry> entries = objectMapper.readValue(c.getMulticlassEntries(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MulticlassEntry.class));
        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).level());
    }

    // --- 7.11 rebuildMulticlassEntries: rebuild after level down ---

    @Test
    @DisplayName("7.11 rebuildMulticlassEntries rebuilds with updated levels")
    void rebuildMulticlassEntries() throws Exception {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();

        String existingEntries = objectMapper.writeValueAsString(List.of(
                new MulticlassEntry(wizardId.toString(), "Wizard", null, null, 3),
                new MulticlassEntry(fighterId.toString(), "Fighter", null, null, 2)));
        c.setMulticlassEntries(existingEntries);

        Map<String, Integer> classLevels = new LinkedHashMap<>();
        classLevels.put(wizardId.toString(), 3);
        classLevels.put(fighterId.toString(), 1);

        helper.rebuildMulticlassEntries(c, classLevels);

        List<MulticlassEntry> entries = objectMapper.readValue(c.getMulticlassEntries(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MulticlassEntry.class));
        assertEquals(2, entries.size());
        assertEquals(3, entries.stream().filter(e -> e.className().equals("Wizard")).findFirst().get().level());
        assertEquals(1, entries.stream().filter(e -> e.className().equals("Fighter")).findFirst().get().level());
    }

    // --- 7.12 mergeJsonArray: merge without duplicates ---

    @Test
    @DisplayName("7.12 mergeJsonArray merges without duplicates")
    void mergeJsonArrayNoDuplicates() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setWeaponProficiencies("[\"Longsword\",\"Shortsword\"]");

        helper.mergeJsonArray(c, "weapon", List.of("Shortsword", "Longbow"));

        List<String> result = objectMapper.readValue(c.getWeaponProficiencies(), new TypeReference<>() {});
        assertEquals(3, result.size());
        assertTrue(result.contains("Longsword"));
        assertTrue(result.contains("Shortsword"));
        assertTrue(result.contains("Longbow"));
    }

    // --- 7.13 appendLevelHistory: adds correct entry ---

    @Test
    @DisplayName("7.13 appendLevelHistory adds correct entry")
    void appendLevelHistory() throws Exception {
        PlayerCharacter c = baseCharacter();
        UUID classId = UUID.randomUUID();

        // Start with history for levels 1 and 2
        List<LevelHistoryEntry> existing = List.of(
                new LevelHistoryEntry(1, classId.toString(), "Fighter", 1, 12,
                        List.of(new FeatureRecord("Second Wind", "Heal", "Fighter")), new LinkedHashMap<>()),
                new LevelHistoryEntry(2, classId.toString(), "Fighter", 2, 8,
                        List.of(new FeatureRecord("Action Surge", "Extra action", "Fighter")), new LinkedHashMap<>())
        );
        c.setLevelHistory(objectMapper.writeValueAsString(existing));

        List<LevelUpCalculator.FeatureEntry> features = List.of(
                new LevelUpCalculator.FeatureEntry("Martial Archetype", "Choose a subclass", "Fighter")
        );

        helper.appendLevelHistory(c, 3, classId.toString(), "Fighter", 3, 8, features);

        List<LevelHistoryEntry> history = objectMapper.readValue(c.getLevelHistory(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, LevelHistoryEntry.class));
        assertEquals(3, history.size());
        LevelHistoryEntry entry = history.get(2);
        assertEquals(3, entry.characterLevel());
        assertEquals(3, entry.classLevel());
        assertEquals(8, entry.hpGained());
        assertEquals("Martial Archetype", entry.featuresGained().get(0).name());
    }

    // --- 7.14 recordAsiInHistory: ASI choice recorded ---

    @Test
    @DisplayName("7.14 recordAsiInHistory records ASI choice on correct entry")
    void recordAsiInHistory() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setLevel(4);
        UUID classId = UUID.randomUUID();

        // History with ASI-eligible level (Fighter level 4)
        List<LevelHistoryEntry> history = new ArrayList<>(List.of(
                new LevelHistoryEntry(1, classId.toString(), "Fighter", 1, 12, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(2, classId.toString(), "Fighter", 2, 8, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(3, classId.toString(), "Fighter", 3, 8, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(4, classId.toString(), "Fighter", 4, 8, List.of(), new LinkedHashMap<>())
        ));
        c.setLevelHistory(objectMapper.writeValueAsString(history));

        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("ability");
        ApplyChoicesRequest.AbilityIncrease inc = new ApplyChoicesRequest.AbilityIncrease();
        inc.setAbility("strength");
        inc.setBonus(2);
        asi.setIncreases(List.of(inc));

        helper.recordAsiInHistory(c, asi);

        List<LevelHistoryEntry> updatedHistory = objectMapper.readValue(c.getLevelHistory(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, LevelHistoryEntry.class));
        LevelHistoryEntry level4 = updatedHistory.get(3);
        assertNotNull(level4.choices());
        assertTrue(level4.choices().containsKey("asi"));
    }

    // --- 7.15 recordFeatInHistory: feat choice recorded ---

    @Test
    @DisplayName("7.15 recordFeatInHistory records feat choice with applied effects")
    void recordFeatInHistory() throws Exception {
        PlayerCharacter c = baseCharacter();
        c.setLevel(4);
        UUID classId = UUID.randomUUID();

        List<LevelHistoryEntry> history = new ArrayList<>(List.of(
                new LevelHistoryEntry(1, classId.toString(), "Fighter", 1, 12, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(2, classId.toString(), "Fighter", 2, 8, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(3, classId.toString(), "Fighter", 3, 8, List.of(), new LinkedHashMap<>()),
                new LevelHistoryEntry(4, classId.toString(), "Fighter", 4, 8, List.of(), new LinkedHashMap<>())
        ));
        c.setLevelHistory(objectMapper.writeValueAsString(history));

        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("feat");
        asi.setFeatName("Alert");
        asi.setFeatId(UUID.randomUUID());

        FeatEffectResolver.AppliedEffects applied = new FeatEffectResolver.AppliedEffects(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                0, 5, 0, 0, 0, null, List.of(), List.of()
        );

        helper.recordFeatInHistory(c, asi, "Alert", applied);

        List<LevelHistoryEntry> updatedHistory = objectMapper.readValue(c.getLevelHistory(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, LevelHistoryEntry.class));
        LevelHistoryEntry level4 = updatedHistory.get(3);
        assertNotNull(level4.choices());
        assertTrue(level4.choices().containsKey("asi"));
        @SuppressWarnings("unchecked")
        Map<String, Object> asiRecord = (Map<String, Object>) level4.choices().get("asi");
        assertEquals("feat", asiRecord.get("type"));
        assertEquals("Alert", asiRecord.get("featName"));
    }

    // --- 7.16 updateMulticlassEntrySubclass: sets subclass ---

    @Test
    @DisplayName("7.16 updateMulticlassEntrySubclass sets subclass on matching entry")
    void updateMulticlassEntrySubclass() throws Exception {
        PlayerCharacter c = baseCharacter();
        UUID fighterId = UUID.randomUUID();
        UUID championId = UUID.randomUUID();

        String existingEntries = objectMapper.writeValueAsString(List.of(
                new MulticlassEntry(fighterId.toString(), "Fighter", null, null, 3)));
        c.setMulticlassEntries(existingEntries);

        Subclass champion = Subclass.builder()
                .id(championId)
                .name("Champion")
                .build();

        helper.updateMulticlassEntrySubclass(c, fighterId, champion);

        List<MulticlassEntry> entries = objectMapper.readValue(c.getMulticlassEntries(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MulticlassEntry.class));
        assertEquals(1, entries.size());
        assertEquals(championId.toString(), entries.get(0).subclassId());
        assertEquals("Champion", entries.get(0).subclassName());
        assertEquals(3, entries.get(0).level());
    }
}
