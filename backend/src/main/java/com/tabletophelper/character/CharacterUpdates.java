package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Changes to a character's typed sheet state that levelling up and down and applying choices share: features, Hit
 * Dice, class entries, proficiencies and the level history (D168). The state records are immutable, so each change
 * builds a new list or map and sets it.
 */
final class CharacterUpdates {

    private CharacterUpdates() {
    }

    // ── Features ─────────────────────────────────────────────────

    static void appendFeatures(PlayerCharacter character, List<LevelUpCalculator.FeatureEntry> newFeatures) {
        List<Feature> features = copy(character.getFeatures());
        for (LevelUpCalculator.FeatureEntry f : newFeatures) {
            features.add(new Feature(f.name(), f.description(), f.source()));
        }
        character.setFeatures(features);
    }

    /** Removes each feature with the same name and source as one gained. */
    static void removeFeatures(PlayerCharacter character, List<Feature> featuresGained) {
        Set<String> keysToRemove = new HashSet<>();
        for (Feature f : featuresGained) {
            keysToRemove.add(f.name() + "|" + f.source());
        }
        List<Feature> features = copy(character.getFeatures());
        features.removeIf(f -> keysToRemove.contains(f.name() + "|" + f.source()));
        character.setFeatures(features);
    }

    static void removeFeatureByName(PlayerCharacter character, String name) {
        List<Feature> features = copy(character.getFeatures());
        features.removeIf(f -> name.equals(f.name()));
        character.setFeatures(features);
    }

    // ── Hit Dice ─────────────────────────────────────────────────

    /**
     * Adds Hit Dice of a class, or takes them away for a negative delta, to the total and the remaining alike. A
     * class left with none is removed.
     */
    static void updateHitDice(PlayerCharacter character, String className, int dieSize, int delta) {
        Map<String, HitDicePool> hitDice = character.getHitDice() != null
                ? new LinkedHashMap<>(character.getHitDice()) : new LinkedHashMap<>();
        HitDicePool existing = hitDice.get(className);
        int total = (existing != null ? existing.total() : 0) + delta;
        int remaining = (existing != null ? existing.remaining() : 0) + delta;
        if (total <= 0) {
            hitDice.remove(className);
        } else {
            hitDice.put(className, new HitDicePool(dieSize, total, Math.max(0, remaining)));
        }
        character.setHitDice(hitDice);
    }

    // ── Class entries ────────────────────────────────────────────

    /** Sets the class's level from the class levels, adding an entry for a class the character didn't have. */
    static void updateClassEntry(PlayerCharacter character, UUID classId, CharacterClass characterClass,
                                 Subclass subclass, Map<UUID, Integer> classLevels) {
        List<ClassEntry> entries = copy(character.getMulticlassEntries());
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            ClassEntry entry = entries.get(i);
            if (classId.equals(entry.classId())) {
                entries.set(i, entry.withLevel(classLevels.get(classId)));
                found = true;
                break;
            }
        }
        if (!found) {
            entries.add(new ClassEntry(classId, characterClass.getName(),
                    subclass != null ? subclass.getId() : null, subclass != null ? subclass.getName() : null,
                    classLevels.get(classId)));
        }
        character.setMulticlassEntries(entries);
    }

    static void setClassEntrySubclass(PlayerCharacter character, UUID classId, Subclass subclass) {
        if (classId == null || subclass == null) {
            return;
        }
        List<ClassEntry> entries = copy(character.getMulticlassEntries());
        for (int i = 0; i < entries.size(); i++) {
            ClassEntry entry = entries.get(i);
            if (classId.equals(entry.classId())) {
                entries.set(i, entry.withSubclass(subclass.getId(), subclass.getName()));
                break;
            }
        }
        character.setMulticlassEntries(entries);
    }

    /** The subclass the character has in a class, or null. */
    static UUID subclassIdOf(PlayerCharacter character, UUID classId) {
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            if (classId.equals(entry.classId()) && entry.subclassId() != null) {
                return entry.subclassId();
            }
        }
        return null;
    }

    /** Keeps the entries of the classes in the class levels, each at its level there. */
    static void rebuildClassEntries(PlayerCharacter character, Map<UUID, Integer> classLevels) {
        List<ClassEntry> entries = new ArrayList<>();
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            if (classLevels.containsKey(entry.classId())) {
                entries.add(entry.withLevel(classLevels.get(entry.classId())));
            }
        }
        character.setMulticlassEntries(entries);
    }

    // ── Proficiencies ────────────────────────────────────────────

    /** The list with each new item added once, after those it already has. */
    static List<String> merged(List<String> current, Collection<String> newItems) {
        List<String> list = copy(current);
        for (String item : newItems) {
            if (!list.contains(item)) {
                list.add(item);
            }
        }
        return list;
    }

    // ── Level history ────────────────────────────────────────────

    static void appendLevelHistory(PlayerCharacter character, int characterLevel, UUID classId, String className,
                                   int classLevel, int hpGained, List<LevelUpCalculator.FeatureEntry> features) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        List<Feature> featuresGained = features.stream()
                .map(f -> new Feature(f.name(), f.description(), f.source()))
                .toList();
        history.add(new LevelHistoryEntry(characterLevel, classId, className, classLevel, hpGained, featuresGained,
                LevelChoices.NONE));
        character.setLevelHistory(history);
    }

    /**
     * The entry an Ability Score Improvement is recorded on: the first at an ASI level of its class with none
     * recorded yet, or else the last entry; null for no history.
     */
    static LevelHistoryEntry findNextAsiEntry(List<LevelHistoryEntry> history) {
        for (LevelHistoryEntry entry : history) {
            if (LevelUpCalculator.isAsiLevel(entry.className(), entry.classLevel())
                    && (entry.choices() == null || entry.choices().asi() == null)) {
                return entry;
            }
        }
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    static void recordAsiInHistory(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) {
        List<AbilityBonus> increases = asi.getIncreases() != null
                ? asi.getIncreases().stream().map(i -> new AbilityBonus(i.getAbility(), i.getBonus())).toList()
                : null;
        recordInHistory(character, new AbilityScoreImprovement(asi.getType(), increases, asi.getFeatName(), null,
                asi.getFeatAbility(), null));
    }

    static void recordFeatInHistory(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi, String featName,
                                    FeatEffectResolver.AppliedEffects applied) {
        recordInHistory(character, new AbilityScoreImprovement("feat", null, featName, asi.getFeatId(), null,
                recordedEffects(applied, featName)));
    }

    /** What the history records of a feat's effects: those it had, without its optional features. */
    static AppliedFeatEffects recordedEffects(FeatEffectResolver.AppliedEffects applied, String featName) {
        return new AppliedFeatEffects(
                applied.abilityIncreases().isEmpty() ? null : applied.abilityIncreases(),
                nonEmpty(applied.resistancesAdded()),
                nonEmpty(applied.armorProficienciesAdded()),
                nonEmpty(applied.weaponProficienciesAdded()),
                nonEmpty(applied.toolProficienciesAdded()),
                nonEmpty(applied.skillProficienciesAdded()),
                nonEmpty(applied.languageProficienciesAdded()),
                nonEmpty(applied.savingThrowProficienciesAdded()),
                nonEmpty(applied.expertiseAdded()),
                nonZero(applied.speedBonus()),
                nonZero(applied.initiativeBonus()),
                nonZero(applied.hpPerLevel()),
                nonZero(applied.passivePerceptionBonus()),
                nonZero(applied.passiveInvestigationBonus()),
                applied.resource(),
                nonEmpty(applied.spellsAdded()),
                featName);
    }

    private static void recordInHistory(PlayerCharacter character, AbilityScoreImprovement improvement) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        LevelHistoryEntry target = findNextAsiEntry(history);
        if (target != null) {
            LevelChoices choices = target.choices() != null ? target.choices() : LevelChoices.NONE;
            history.set(history.indexOf(target), target.withChoices(choices.withAsi(improvement)));
            character.setLevelHistory(history);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    static <T> List<T> copy(List<T> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }

    private static <T> List<T> nonEmpty(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    private static Integer nonZero(int value) {
        return value != 0 ? value : null;
    }
}
