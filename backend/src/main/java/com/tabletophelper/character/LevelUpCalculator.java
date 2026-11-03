package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.rules.character.ClassProgression;
import com.tabletophelper.rules.character.HitPoints;
import com.tabletophelper.shared.ConflictException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The levels a character is created with, or gains: the features each brings, from the class and subclass data, and
 * its Hit Die Result (D167). The rules themselves are rules.character's.
 */
public class LevelUpCalculator {

    private static final ObjectMapper mapper = new ObjectMapper();

    public record FeatureEntry(String name, String description, String source) {}

    /**
     * @param hitDieResult the die's maximum at character level 1 and its fixed average after (HitPoints)
     */
    public record LevelGain(
            int characterLevel,
            UUID classId,
            String className,
            int classLevel,
            int hitDieResult,
            List<FeatureEntry> featuresGained,
            boolean asiAvailable,
            boolean subclassAvailable
    ) {}

    /**
     * The features a class level brings, from the class's features and, once the subclass has been chosen, the
     * subclass's.
     *
     * @throws ConflictException when the class or subclass data can't be read (D155)
     */
    public static List<FeatureEntry> collectFeaturesForLevel(
            String classFeatureJson, String subclassFeatureJson,
            int classLevel, String className, String subclassName) {
        List<FeatureEntry> result = new ArrayList<>();
        if (classFeatureJson != null) {
            result.addAll(featuresAt(classFeatureJson, classLevel, className, "class " + className));
        }
        if (subclassFeatureJson != null && subclassName != null) {
            result.addAll(featuresAt(subclassFeatureJson, classLevel, subclassName, "subclass " + subclassName));
        }
        return result;
    }

    private static List<FeatureEntry> featuresAt(String featureJson, int classLevel, String source, String what) {
        List<Map<String, Object>> features;
        try {
            features = mapper.readValue(featureJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ConflictException("The features of the " + what + " can't be read");
        }
        List<FeatureEntry> result = new ArrayList<>();
        for (Map<String, Object> f : features) {
            int level = f.get("level") instanceof Number n ? n.intValue() : 0;
            if (level == classLevel) {
                result.add(new FeatureEntry((String) f.get("name"), (String) f.getOrDefault("description", ""),
                        source));
            }
        }
        return result;
    }

    public static List<LevelGain> buildProgression(
            int targetLevel, UUID classId, String className,
            int hitDice,
            String classFeatureJson, String subclassFeatureJson,
            String subclassName, int subclassLevel) {

        List<LevelGain> progression = new ArrayList<>();

        for (int lvl = 1; lvl <= targetLevel; lvl++) {
            String scFeatures = (subclassName != null && lvl >= subclassLevel)
                    ? subclassFeatureJson : null;
            String scName = (subclassName != null && lvl >= subclassLevel)
                    ? subclassName : null;

            List<FeatureEntry> features = collectFeaturesForLevel(
                    classFeatureJson, scFeatures, lvl, className, scName);

            progression.add(new LevelGain(
                    lvl, classId, className, lvl,
                    HitPoints.fixedHitDieResult(lvl, hitDice), features,
                    className != null && ClassProgression.isAsiLevel(className, lvl), lvl == subclassLevel));
        }

        return progression;
    }

    public record ClassInput(UUID classId, String className, int classLevel,
                                int hitDice, String classFeatureJson, String subclassFeatureJson,
                                String subclassName, int subclassLevel) {}

    /**
     * Each class's levels in the order given, so only the first class's first level is character level 1, with the
     * die's maximum; every other class's first level has the average (PHB p.163).
     */
    public static List<LevelGain> buildMulticlassProgression(List<ClassInput> classInputs) {
        List<LevelGain> progression = new ArrayList<>();
        int characterLevel = 0;

        for (ClassInput ci : classInputs) {
            for (int clsLvl = 1; clsLvl <= ci.classLevel(); clsLvl++) {
                characterLevel++;

                String scFeatures = (ci.subclassName() != null && clsLvl >= ci.subclassLevel())
                        ? ci.subclassFeatureJson() : null;
                String scName = (ci.subclassName() != null && clsLvl >= ci.subclassLevel())
                        ? ci.subclassName() : null;

                List<FeatureEntry> features = collectFeaturesForLevel(
                        ci.classFeatureJson(), scFeatures, clsLvl, ci.className(), scName);

                progression.add(new LevelGain(
                        characterLevel, ci.classId(), ci.className(), clsLvl,
                        HitPoints.fixedHitDieResult(characterLevel, ci.hitDice()), features,
                        ClassProgression.isAsiLevel(ci.className(), clsLvl), clsLvl == ci.subclassLevel()));
            }
        }

        return progression;
    }

    public static List<FeatureEntry> allFeatures(List<LevelGain> progression) {
        return progression.stream()
                .flatMap(g -> g.featuresGained().stream())
                .collect(Collectors.toList());
    }

    /**
     * The level history a new character starts with: one entry for each level with its Hit Die Result and no
     * choices yet. What each level added to the hit point maximum is filled in once the character is worked out.
     */
    public static List<LevelHistoryEntry> levelHistory(List<LevelGain> progression) {
        List<LevelHistoryEntry> history = new ArrayList<>();
        for (LevelGain gain : progression) {
            history.add(new LevelHistoryEntry(gain.characterLevel(), gain.classId(), gain.className(),
                    gain.classLevel(), 0, gain.hitDieResult(), features(gain.featuresGained()), LevelChoices.NONE,
                    null, null));
        }
        return history;
    }

    /**
     * The features a new character starts with: its levels' class and subclass features. A creation request can't
     * add any, so a feat comes only through a Pending Choice or a DM Adjustment (D158, D159).
     */
    public static List<Feature> classFeatures(List<LevelGain> progression) {
        return features(allFeatures(progression));
    }

    private static List<Feature> features(List<FeatureEntry> entries) {
        return entries.stream().map(f -> new Feature(f.name(), f.description(), f.source())).toList();
    }
}
