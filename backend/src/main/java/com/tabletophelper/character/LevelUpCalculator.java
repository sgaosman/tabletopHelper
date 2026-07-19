package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

public class LevelUpCalculator {

    private static final ObjectMapper mapper = new ObjectMapper();

    public record FeatureEntry(String name, String description, String source) {}

    public record LevelGain(
            int characterLevel,
            String classId,
            String className,
            int classLevel,
            int hpGained,
            List<FeatureEntry> featuresGained,
            boolean asiAvailable,
            boolean subclassAvailable
    ) {}

    private static final Set<Integer> STANDARD_ASI_LEVELS = Set.of(4, 8, 12, 16, 19);
    private static final Set<Integer> FIGHTER_ASI_LEVELS = Set.of(4, 6, 8, 12, 14, 16, 19);
    private static final Set<Integer> ROGUE_ASI_LEVELS = Set.of(4, 8, 10, 12, 16, 19);

    public static int calculateHpGain(int classLevel, int hitDice, int conMod) {
        if (classLevel == 1) return hitDice + conMod;
        return (hitDice / 2 + 1) + conMod;
    }

    public static boolean isAsiLevel(String className, int classLevel) {
        return switch (className) {
            case "Fighter" -> FIGHTER_ASI_LEVELS.contains(classLevel);
            case "Rogue" -> ROGUE_ASI_LEVELS.contains(classLevel);
            default -> STANDARD_ASI_LEVELS.contains(classLevel);
        };
    }

    public static List<FeatureEntry> collectFeaturesForLevel(
            String classFeatureJson, String subclassFeatureJson,
            int classLevel, String className, String subclassName) {
        List<FeatureEntry> result = new ArrayList<>();

        if (classFeatureJson != null) {
            try {
                List<Map<String, Object>> features = mapper.readValue(
                        classFeatureJson, new TypeReference<>() {});
                for (Map<String, Object> f : features) {
                    int level = f.get("level") instanceof Number n ? n.intValue() : 0;
                    if (level == classLevel) {
                        result.add(new FeatureEntry(
                                (String) f.get("name"),
                                (String) f.getOrDefault("description", ""),
                                className
                        ));
                    }
                }
            } catch (Exception ignored) {}
        }

        if (subclassFeatureJson != null && subclassName != null) {
            try {
                List<Map<String, Object>> features = mapper.readValue(
                        subclassFeatureJson, new TypeReference<>() {});
                for (Map<String, Object> f : features) {
                    int level = f.get("level") instanceof Number n ? n.intValue() : 0;
                    if (level == classLevel) {
                        result.add(new FeatureEntry(
                                (String) f.get("name"),
                                (String) f.getOrDefault("description", ""),
                                subclassName
                        ));
                    }
                }
            } catch (Exception ignored) {}
        }

        return result;
    }

    public static List<LevelGain> buildProgression(
            int targetLevel, UUID classId, String className,
            int hitDice, int conMod,
            String classFeatureJson, String subclassFeatureJson,
            String subclassName, int subclassLevel) {

        String classIdStr = classId != null ? classId.toString() : null;
        List<LevelGain> progression = new ArrayList<>();

        for (int lvl = 1; lvl <= targetLevel; lvl++) {
            int hpGained = calculateHpGain(lvl, hitDice, conMod);

            String scFeatures = (subclassName != null && lvl >= subclassLevel)
                    ? subclassFeatureJson : null;
            String scName = (subclassName != null && lvl >= subclassLevel)
                    ? subclassName : null;

            List<FeatureEntry> features = collectFeaturesForLevel(
                    classFeatureJson, scFeatures, lvl, className, scName);

            boolean asiAvailable = isAsiLevel(className, lvl);
            boolean subclassAvail = (lvl == subclassLevel);

            progression.add(new LevelGain(
                    lvl, classIdStr, className, lvl,
                    hpGained, features, asiAvailable, subclassAvail));
        }

        return progression;
    }

    public static int totalHp(List<LevelGain> progression) {
        return progression.stream().mapToInt(LevelGain::hpGained).sum();
    }

    public static List<FeatureEntry> allFeatures(List<LevelGain> progression) {
        return progression.stream()
                .flatMap(g -> g.featuresGained().stream())
                .collect(Collectors.toList());
    }

    public static String serializeLevelHistory(List<LevelGain> progression) {
        try {
            List<Map<String, Object>> history = new ArrayList<>();
            for (LevelGain gain : progression) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("characterLevel", gain.characterLevel());
                entry.put("classId", gain.classId());
                entry.put("className", gain.className());
                entry.put("classLevel", gain.classLevel());
                entry.put("hpGained", gain.hpGained());
                List<Map<String, String>> feats = gain.featuresGained().stream()
                        .map(f -> Map.of("name", f.name(), "description", f.description(), "source", f.source()))
                        .toList();
                entry.put("featuresGained", feats);
                entry.put("choices", Map.of());
                history.add(entry);
            }
            return mapper.writeValueAsString(history);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String serializeFeatures(List<FeatureEntry> features) {
        try {
            List<Map<String, String>> list = features.stream()
                    .map(f -> Map.of("name", f.name(), "description", f.description(), "source", f.source()))
                    .toList();
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String mergeFeatures(String existingFeaturesJson, List<FeatureEntry> classFeatures) {
        try {
            List<Map<String, Object>> merged = new ArrayList<>();

            for (FeatureEntry f : classFeatures) {
                merged.add(Map.of("name", f.name(), "description", f.description(), "source", f.source()));
            }

            if (existingFeaturesJson != null && !existingFeaturesJson.isBlank()) {
                List<Map<String, Object>> existing = mapper.readValue(
                        existingFeaturesJson, new TypeReference<>() {});
                for (Map<String, Object> e : existing) {
                    String source = (String) e.getOrDefault("source", "");
                    if (!source.isEmpty() && (source.startsWith("Background") || source.startsWith("Feat") || source.startsWith("Race"))) {
                        merged.add(e);
                    }
                }
            }

            return mapper.writeValueAsString(merged);
        } catch (Exception e) {
            return serializeFeatures(classFeatures);
        }
    }
}
