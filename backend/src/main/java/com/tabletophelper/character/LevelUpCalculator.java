package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class LevelUpCalculator {

    private static final Logger log = LoggerFactory.getLogger(LevelUpCalculator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public record FeatureEntry(String name, String description, String source) {}

    public record LevelGain(
            int characterLevel,
            UUID classId,
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
            } catch (Exception e) {
                log.warn("Failed to parse class features JSON for {}: {}", className, e.getMessage());
            }
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
            } catch (Exception e) {
                log.warn("Failed to parse subclass features JSON for {}: {}", subclassName, e.getMessage());
            }
        }

        return result;
    }

    public static List<LevelGain> buildProgression(
            int targetLevel, UUID classId, String className,
            int hitDice, int conMod,
            String classFeatureJson, String subclassFeatureJson,
            String subclassName, int subclassLevel) {

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
                    lvl, classId, className, lvl,
                    hpGained, features, asiAvailable, subclassAvail));
        }

        return progression;
    }

    public record ClassInput(UUID classId, String className, int classLevel,
                                int hitDice, String classFeatureJson, String subclassFeatureJson,
                                String subclassName, int subclassLevel) {}

    public static List<LevelGain> buildMulticlassProgression(List<ClassInput> classInputs, int conMod) {
        List<LevelGain> progression = new ArrayList<>();
        int characterLevel = 0;

        for (ClassInput ci : classInputs) {
            for (int clsLvl = 1; clsLvl <= ci.classLevel(); clsLvl++) {
                characterLevel++;
                boolean isFirstClassLevel = (clsLvl == 1 && ci == classInputs.get(0));
                int hpGained = isFirstClassLevel
                        ? ci.hitDice() + conMod
                        : (ci.hitDice() / 2 + 1) + conMod;

                String scFeatures = (ci.subclassName() != null && clsLvl >= ci.subclassLevel())
                        ? ci.subclassFeatureJson() : null;
                String scName = (ci.subclassName() != null && clsLvl >= ci.subclassLevel())
                        ? ci.subclassName() : null;

                List<FeatureEntry> features = collectFeaturesForLevel(
                        ci.classFeatureJson(), scFeatures, clsLvl, ci.className(), scName);

                boolean asiAvailable = isAsiLevel(ci.className(), clsLvl);
                boolean subclassAvail = (clsLvl == ci.subclassLevel());

                progression.add(new LevelGain(
                        characterLevel, ci.classId(), ci.className(), clsLvl,
                        hpGained, features, asiAvailable, subclassAvail));
            }
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

    /** The level history a new character starts with: one entry for each level, with no choices yet. */
    public static List<LevelHistoryEntry> levelHistory(List<LevelGain> progression) {
        List<LevelHistoryEntry> history = new ArrayList<>();
        for (LevelGain gain : progression) {
            history.add(new LevelHistoryEntry(gain.characterLevel(), gain.classId(), gain.className(),
                    gain.classLevel(), gain.hpGained(), features(gain.featuresGained()), LevelChoices.NONE));
        }
        return history;
    }

    /**
     * The class features, followed by those of the given features a background, feat or race granted. Any other
     * given feature is left out, since the class features replace it.
     */
    public static List<Feature> mergeFeatures(List<Feature> existingFeatures, List<FeatureEntry> classFeatures) {
        List<Feature> merged = new ArrayList<>(features(classFeatures));
        if (existingFeatures != null) {
            for (Feature e : existingFeatures) {
                String source = e.source() != null ? e.source() : "";
                if (!source.isEmpty() && (source.startsWith("Background") || source.startsWith("Feat") || source.startsWith("Race"))) {
                    merged.add(e);
                }
            }
        }
        return merged;
    }

    private static List<Feature> features(List<FeatureEntry> entries) {
        return entries.stream().map(f -> new Feature(f.name(), f.description(), f.source())).toList();
    }
}
