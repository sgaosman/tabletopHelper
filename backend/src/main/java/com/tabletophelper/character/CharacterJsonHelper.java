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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterJsonHelper {

    private static final TypeReference<List<FeatureRecord>> FEATURE_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<MulticlassEntry>> MC_ENTRY_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, HitDiceEntry>> HD_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<LevelHistoryEntry>> HISTORY_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public List<FeatureRecord> parseFeaturesList(String featuresJson) throws Exception {
        if (featuresJson == null || featuresJson.isBlank()) return new ArrayList<>();
        return new ArrayList<>(objectMapper.readValue(featuresJson, FEATURE_LIST_TYPE));
    }

    public void appendFeatures(PlayerCharacter character, List<LevelUpCalculator.FeatureEntry> newFeatures) {
        try {
            List<FeatureRecord> features = parseFeaturesList(character.getFeatures());
            for (LevelUpCalculator.FeatureEntry f : newFeatures) {
                features.add(new FeatureRecord(f.name(), f.description(), f.source()));
            }
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception e) {
            log.error("Failed to append features for character {}", character.getId(), e);
        }
    }

    public void removeFeatures(PlayerCharacter character, List<FeatureRecord> featuresGained) {
        try {
            List<FeatureRecord> features = parseFeaturesList(character.getFeatures());
            Set<String> keysToRemove = new HashSet<>();
            for (FeatureRecord f : featuresGained) {
                keysToRemove.add(f.name() + "|" + f.source());
            }
            features.removeIf(f -> keysToRemove.contains(f.name() + "|" + f.source()));
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception e) {
            log.error("Failed to remove features for character {}", character.getId(), e);
        }
    }

    public void removeFeatureByName(PlayerCharacter character, String name) {
        try {
            List<FeatureRecord> features = parseFeaturesList(character.getFeatures());
            features.removeIf(f -> name.equals(f.name()));
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception e) {
            log.error("Failed to remove feature '{}' for character {}", name, character.getId(), e);
        }
    }

    public void updateHitDiceMap(PlayerCharacter character, String className, int faces, int delta) {
        try {
            Map<String, HitDiceEntry> hdMap = character.getHitDiceMap() != null
                    ? new LinkedHashMap<>(objectMapper.readValue(character.getHitDiceMap(), HD_MAP_TYPE))
                    : new LinkedHashMap<>();

            HitDiceEntry existing = hdMap.get(className);
            int total = existing != null ? existing.total() : 0;
            int remaining = existing != null ? existing.remaining() : 0;
            total += delta;
            remaining += delta;
            if (total <= 0) {
                hdMap.remove(className);
            } else {
                hdMap.put(className, new HitDiceEntry(total, Math.max(0, remaining), faces));
            }
            character.setHitDiceMap(objectMapper.writeValueAsString(hdMap));
        } catch (Exception e) {
            log.error("Failed to update hit dice map for character {}", character.getId(), e);
        }
    }

    public String buildHitDiceTotal(PlayerCharacter character) {
        try {
            Map<String, HitDiceEntry> hdMap = character.getHitDiceMap() != null
                    ? objectMapper.readValue(character.getHitDiceMap(), HD_MAP_TYPE)
                    : Map.of();
            return hdMap.values().stream()
                    .map(e -> e.total() + "d" + e.faces())
                    .reduce((a, b) -> a + " + " + b)
                    .orElse("0");
        } catch (Exception e) {
            return "0";
        }
    }

    public void updateMulticlassEntries(PlayerCharacter character, UUID classId, CharacterClass cc,
                                         Subclass sc, Map<String, Integer> classLevels, boolean isNewMulticlass) {
        try {
            List<MulticlassEntry> entries = character.getMulticlassEntries() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getMulticlassEntries(), MC_ENTRY_LIST_TYPE))
                    : new ArrayList<>();

            boolean found = false;
            for (int i = 0; i < entries.size(); i++) {
                MulticlassEntry entry = entries.get(i);
                if (classId.toString().equals(entry.classId())) {
                    entries.set(i, new MulticlassEntry(
                            entry.classId(), entry.className(),
                            entry.subclassId(), entry.subclassName(),
                            classLevels.get(classId.toString())));
                    found = true;
                    break;
                }
            }

            if (!found) {
                entries.add(new MulticlassEntry(
                        classId.toString(), cc.getName(),
                        sc != null ? sc.getId().toString() : null,
                        sc != null ? sc.getName() : null,
                        classLevels.get(classId.toString())));
            }

            character.setMulticlassEntries(objectMapper.writeValueAsString(entries));
        } catch (Exception e) {
            log.error("Failed to update multiclass entries for character {}", character.getId(), e);
        }
    }

    public void updateMulticlassEntrySubclass(PlayerCharacter character, UUID classId, Subclass sc) {
        if (classId == null || sc == null) return;
        try {
            List<MulticlassEntry> entries = character.getMulticlassEntries() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getMulticlassEntries(), MC_ENTRY_LIST_TYPE))
                    : new ArrayList<>();

            for (int i = 0; i < entries.size(); i++) {
                MulticlassEntry entry = entries.get(i);
                if (classId.toString().equals(entry.classId())) {
                    entries.set(i, new MulticlassEntry(
                            entry.classId(), entry.className(),
                            sc.getId().toString(), sc.getName(),
                            entry.level()));
                    break;
                }
            }

            character.setMulticlassEntries(objectMapper.writeValueAsString(entries));
        } catch (Exception e) {
            log.error("Failed to update multiclass entry subclass for character {}", character.getId(), e);
        }
    }

    public UUID getSubclassIdFromMulticlassEntries(PlayerCharacter character, UUID classId) {
        try {
            if (character.getMulticlassEntries() == null) return null;
            List<MulticlassEntry> entries = objectMapper.readValue(character.getMulticlassEntries(), MC_ENTRY_LIST_TYPE);
            for (MulticlassEntry entry : entries) {
                if (classId.toString().equals(entry.classId()) && entry.subclassId() != null) {
                    return UUID.fromString(entry.subclassId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse multiclass entries for subclass lookup on character {}", character.getId(), e);
        }
        return null;
    }

    public void rebuildMulticlassEntries(PlayerCharacter character, Map<String, Integer> classLevels) {
        try {
            List<MulticlassEntry> oldEntries = character.getMulticlassEntries() != null
                    ? objectMapper.readValue(character.getMulticlassEntries(), MC_ENTRY_LIST_TYPE)
                    : List.of();

            List<MulticlassEntry> newEntries = new ArrayList<>();
            for (MulticlassEntry entry : oldEntries) {
                if (classLevels.containsKey(entry.classId())) {
                    newEntries.add(new MulticlassEntry(
                            entry.classId(), entry.className(),
                            entry.subclassId(), entry.subclassName(),
                            classLevels.get(entry.classId())));
                }
            }

            character.setMulticlassEntries(objectMapper.writeValueAsString(newEntries));
        } catch (Exception e) {
            log.error("Failed to rebuild multiclass entries for character {}", character.getId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public void mergeJsonArray(PlayerCharacter character, String type, List<String> newItems) {
        try {
            String existing = switch (type) {
                case "armor" -> character.getArmorProficiencies();
                case "weapon" -> character.getWeaponProficiencies();
                case "tool" -> character.getToolProficiencies();
                default -> null;
            };

            List<String> list = existing != null
                    ? new ArrayList<>(objectMapper.readValue(existing, new TypeReference<List<String>>() {}))
                    : new ArrayList<>();
            for (String item : newItems) {
                if (!list.contains(item)) list.add(item);
            }
            String json = objectMapper.writeValueAsString(list);

            switch (type) {
                case "armor" -> character.setArmorProficiencies(json);
                case "weapon" -> character.setWeaponProficiencies(json);
                case "tool" -> character.setToolProficiencies(json);
            }
        } catch (Exception e) {
            log.warn("Failed to merge {} proficiencies for character {}", type, character.getId(), e);
        }
    }

    public void appendLevelHistory(PlayerCharacter character, int charLevel, String classId,
                                    String className, int classLevel, int hpGained,
                                    List<LevelUpCalculator.FeatureEntry> features) {
        try {
            List<LevelHistoryEntry> history = character.getLevelHistory() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getLevelHistory(), HISTORY_LIST_TYPE))
                    : new ArrayList<>();

            List<FeatureRecord> featureRecords = features.stream()
                    .map(f -> new FeatureRecord(f.name(), f.description(), f.source()))
                    .toList();

            history.add(new LevelHistoryEntry(charLevel, classId, className, classLevel, hpGained,
                    featureRecords, new LinkedHashMap<>()));
            character.setLevelHistory(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.error("Failed to append level history for character {} at level {}", character.getId(), charLevel, e);
            throw new IllegalStateException("Failed to append level history: " + e.getMessage(), e);
        }
    }

    LevelHistoryEntry findNextAsiEntry(List<LevelHistoryEntry> history) {
        for (LevelHistoryEntry entry : history) {
            if (LevelUpCalculator.isAsiLevel(entry.className(), entry.classLevel())) {
                Map<String, Object> choices = entry.choices() != null ? entry.choices() : Map.of();
                if (!choices.containsKey("asi")) {
                    return entry;
                }
            }
        }
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public void recordAsiInHistory(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) {
        try {
            List<LevelHistoryEntry> history = character.getLevelHistory() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getLevelHistory(), HISTORY_LIST_TYPE))
                    : new ArrayList<>();

            LevelHistoryEntry targetEntry = findNextAsiEntry(history);
            if (targetEntry != null) {
                int idx = history.indexOf(targetEntry);
                Map<String, Object> choices = targetEntry.choices() != null
                        ? new LinkedHashMap<>(targetEntry.choices())
                        : new LinkedHashMap<>();

                Map<String, Object> asiRecord = new LinkedHashMap<>();
                asiRecord.put("type", asi.getType());
                if (asi.getIncreases() != null) {
                    asiRecord.put("increases", asi.getIncreases().stream()
                            .map(i -> Map.of("ability", (Object) i.getAbility(), "bonus", (Object) i.getBonus()))
                            .toList());
                }
                if (asi.getFeatName() != null) asiRecord.put("featName", asi.getFeatName());
                if (asi.getFeatAbility() != null) asiRecord.put("featAbility", asi.getFeatAbility());

                choices.put("asi", asiRecord);
                history.set(idx, new LevelHistoryEntry(
                        targetEntry.characterLevel(), targetEntry.classId(), targetEntry.className(),
                        targetEntry.classLevel(), targetEntry.hpGained(), targetEntry.featuresGained(), choices));
                character.setLevelHistory(objectMapper.writeValueAsString(history));
            }
        } catch (Exception e) {
            log.error("Failed to record ASI in history for character {}", character.getId(), e);
        }
    }

    public void recordFeatInHistory(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi,
                                     String featName, FeatEffectResolver.AppliedEffects applied) {
        try {
            List<LevelHistoryEntry> history = character.getLevelHistory() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getLevelHistory(), HISTORY_LIST_TYPE))
                    : new ArrayList<>();

            LevelHistoryEntry targetEntry = findNextAsiEntry(history);
            if (targetEntry != null) {
                int idx = history.indexOf(targetEntry);
                Map<String, Object> choices = targetEntry.choices() != null
                        ? new LinkedHashMap<>(targetEntry.choices())
                        : new LinkedHashMap<>();

                Map<String, Object> asiRecord = new LinkedHashMap<>();
                asiRecord.put("type", "feat");
                asiRecord.put("featName", featName);
                asiRecord.put("featId", asi.getFeatId().toString());

                Map<String, Object> appliedRecord = new LinkedHashMap<>();
                if (!applied.abilityIncreases().isEmpty()) appliedRecord.put("abilityIncreases", applied.abilityIncreases());
                if (!applied.resistancesAdded().isEmpty()) appliedRecord.put("resistancesAdded", applied.resistancesAdded());
                if (!applied.armorProficienciesAdded().isEmpty()) appliedRecord.put("armorProficienciesAdded", applied.armorProficienciesAdded());
                if (!applied.weaponProficienciesAdded().isEmpty()) appliedRecord.put("weaponProficienciesAdded", applied.weaponProficienciesAdded());
                if (!applied.toolProficienciesAdded().isEmpty()) appliedRecord.put("toolProficienciesAdded", applied.toolProficienciesAdded());
                if (!applied.skillProficienciesAdded().isEmpty()) appliedRecord.put("skillProficienciesAdded", applied.skillProficienciesAdded());
                if (!applied.languageProficienciesAdded().isEmpty()) appliedRecord.put("languageProficienciesAdded", applied.languageProficienciesAdded());
                if (!applied.savingThrowProficienciesAdded().isEmpty()) appliedRecord.put("savingThrowProficienciesAdded", applied.savingThrowProficienciesAdded());
                if (!applied.expertiseAdded().isEmpty()) appliedRecord.put("expertiseAdded", applied.expertiseAdded());
                if (applied.speedBonus() != 0) appliedRecord.put("speedBonus", applied.speedBonus());
                if (applied.initiativeBonus() != 0) appliedRecord.put("initiativeBonus", applied.initiativeBonus());
                if (applied.hpPerLevel() != 0) appliedRecord.put("hpPerLevel", applied.hpPerLevel());
                if (applied.passivePerceptionBonus() != 0) appliedRecord.put("passivePerceptionBonus", applied.passivePerceptionBonus());
                if (applied.passiveInvestigationBonus() != 0) appliedRecord.put("passiveInvestigationBonus", applied.passiveInvestigationBonus());
                if (applied.resource() != null) appliedRecord.put("resource", applied.resource());
                if (!applied.spellsAdded().isEmpty()) appliedRecord.put("spellsAdded", applied.spellsAdded());
                appliedRecord.put("featName", featName);

                asiRecord.put("appliedEffects", appliedRecord);

                choices.put("asi", asiRecord);
                history.set(idx, new LevelHistoryEntry(
                        targetEntry.characterLevel(), targetEntry.classId(), targetEntry.className(),
                        targetEntry.classLevel(), targetEntry.hpGained(), targetEntry.featuresGained(), choices));
                character.setLevelHistory(objectMapper.writeValueAsString(history));
            }
        } catch (Exception e) {
            log.error("Failed to record feat in history for character {}", character.getId(), e);
        }
    }
}
