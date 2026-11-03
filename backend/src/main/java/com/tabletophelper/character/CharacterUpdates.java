package com.tabletophelper.character;

import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.AbilityScores;
import com.tabletophelper.shared.ValidationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Changes to a character's typed sheet state that levelling up and down and applying choices share: features, class
 * entries, proficiencies and the level history (D168). The state records are immutable, so each change builds a new
 * list and sets it. Hit Dice, like the other Derived Stats, are CharacterStats' to work out (D167).
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

    /**
     * Removes one feature for each one gained, with the same name and source: the latest, so a feature several levels
     * give, such as a fighter's Ability Score Improvement, keeps the earlier levels' copies.
     */
    static void removeFeatures(PlayerCharacter character, List<Feature> featuresGained) {
        List<Feature> features = copy(character.getFeatures());
        for (Feature gained : featuresGained) {
            for (int i = features.size() - 1; i >= 0; i--) {
                Feature f = features.get(i);
                if (Objects.equals(f.name(), gained.name()) && Objects.equals(f.source(), gained.source())) {
                    features.remove(i);
                    break;
                }
            }
        }
        character.setFeatures(features);
    }

    /** Removes the latest feature with the name, as a feat named only by its name added it. */
    static void removeFeatureByName(PlayerCharacter character, String name) {
        List<Feature> features = copy(character.getFeatures());
        for (int i = features.size() - 1; i >= 0; i--) {
            if (name.equals(features.get(i).name())) {
                features.remove(i);
                break;
            }
        }
        character.setFeatures(features);
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

    /** Takes the subclass away from the class's entry, as levelling down past the level that chose it does. */
    static void clearClassEntrySubclass(PlayerCharacter character, UUID classId) {
        List<ClassEntry> entries = copy(character.getMulticlassEntries());
        for (int i = 0; i < entries.size(); i++) {
            ClassEntry entry = entries.get(i);
            if (classId.equals(entry.classId())) {
                entries.set(i, entry.withSubclass(null, null));
                break;
            }
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

    // ── Ability scores ───────────────────────────────────────────

    /**
     * Raises an ability score by as much of the amount as fits under its maximum, 20, or 24 for Strength and
     * Constitution after Primal Champion (D167), and returns what it added. Hit points follow from the score:
     * CharacterStats works them out.
     *
     * @throws ValidationException for a name that isn't an ability
     */
    static int increaseAbility(PlayerCharacter character, String abilityName, int amount) {
        Ability ability = ability(abilityName);
        int score = score(character, ability);
        int applied = AbilityScores.increase(ability, score, amount, CharacterStats.primalChampion(character));
        setScore(character, ability, score + applied);
        return applied;
    }

    /** Lowers an ability score by exactly what an increase added, as levelling down does. */
    static void decreaseAbility(PlayerCharacter character, String abilityName, int amount) {
        Ability ability = ability(abilityName);
        setScore(character, ability, score(character, ability) - amount);
    }

    /**
     * The ability a name stands for, abbreviated or spelled out.
     *
     * @throws ValidationException for a name that isn't an ability
     */
    static Ability ability(String abilityName) {
        return Ability.parse(abilityName)
                .orElseThrow(() -> new ValidationException("'" + abilityName + "' is not an ability"));
    }

    static int score(PlayerCharacter character, Ability ability) {
        Integer score = switch (ability) {
            case STR -> character.getStrength();
            case DEX -> character.getDexterity();
            case CON -> character.getConstitution();
            case INT -> character.getIntelligence();
            case WIS -> character.getWisdom();
            case CHA -> character.getCharisma();
        };
        return score != null ? score : 10;
    }

    private static void setScore(PlayerCharacter character, Ability ability, int score) {
        switch (ability) {
            case STR -> character.setStrength(score);
            case DEX -> character.setDexterity(score);
            case CON -> character.setConstitution(score);
            case INT -> character.setIntelligence(score);
            case WIS -> character.setWisdom(score);
            case CHA -> character.setCharisma(score);
        }
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

    /** The new items the list doesn't have yet, each once: what merging them adds. */
    static List<String> added(List<String> current, Collection<String> newItems) {
        List<String> added = new ArrayList<>();
        for (String item : newItems) {
            if (!orEmpty(current).contains(item) && !added.contains(item)) {
                added.add(item);
            }
        }
        return added;
    }

    /** The list without the items a level gained, which levelling down takes away. */
    static List<String> without(List<String> current, Collection<String> toRemove) {
        List<String> list = copy(current);
        if (toRemove != null) {
            list.removeAll(toRemove);
        }
        return list;
    }

    /** Takes away the proficiencies a level added, leaving any kind it added none of as it is. */
    static void removeProficiencies(PlayerCharacter character, ProficienciesGained gained) {
        if (gained == null) {
            return;
        }
        if (gained.armor() != null && !gained.armor().isEmpty()) {
            character.setArmorProficiencies(without(character.getArmorProficiencies(), gained.armor()));
        }
        if (gained.weapons() != null && !gained.weapons().isEmpty()) {
            character.setWeaponProficiencies(without(character.getWeaponProficiencies(), gained.weapons()));
        }
        if (gained.tools() != null && !gained.tools().isEmpty()) {
            character.setToolProficiencies(without(character.getToolProficiencies(), gained.tools()));
        }
    }

    // ── Level history ────────────────────────────────────────────

    /**
     * Records a level as it is gained, with what it added to the hit point maximum filled in once the character is
     * worked out again.
     */
    static void appendLevelHistory(PlayerCharacter character, int characterLevel, UUID classId, String className,
                                   int classLevel, int hitDieResult, List<LevelUpCalculator.FeatureEntry> features,
                                   ProficienciesGained proficienciesGained, List<AbilityBonus> abilityIncreases) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        List<Feature> featuresGained = features.stream()
                .map(f -> new Feature(f.name(), f.description(), f.source()))
                .toList();
        history.add(new LevelHistoryEntry(characterLevel, classId, className, classLevel, 0, hitDieResult,
                featuresGained, LevelChoices.NONE,
                addedAny(proficienciesGained) ? proficienciesGained : null,
                abilityIncreases != null && !abilityIncreases.isEmpty() ? abilityIncreases : null));
        character.setLevelHistory(history);
    }

    /** Sets what the latest level added to the hit point maximum. */
    static void recordHpGained(PlayerCharacter character, int hpGained) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        if (!history.isEmpty()) {
            history.set(history.size() - 1, history.getLast().withHpGained(hpGained));
            character.setLevelHistory(history);
        }
    }

    /**
     * The index of the level history entry of a character level, which records the choices that level owes (D159);
     * -1 when the history has none.
     */
    static int historyIndex(PlayerCharacter character, int characterLevel) {
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).characterLevel() == characterLevel) {
                return i;
            }
        }
        return -1;
    }

    /** Changes the choices the level history entry at the index records. */
    static void recordChoices(PlayerCharacter character, int index, UnaryOperator<LevelChoices> change) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        LevelHistoryEntry target = history.get(index);
        LevelChoices choices = target.choices() != null ? target.choices() : LevelChoices.NONE;
        history.set(index, target.withChoices(change.apply(choices)));
        character.setLevelHistory(history);
    }

    /** Records an Ability Score Improvement, or the feat taken instead, on the level history entry at the index. */
    static void recordAsi(PlayerCharacter character, int index, AbilityScoreImprovement improvement) {
        recordChoices(character, index, choices -> choices.withAsi(improvement));
    }

    /**
     * Records skills given Expertise on the level history entry at the index, after any it records already, as when
     * creation gave the level part of its Expertise.
     */
    static void recordExpertise(PlayerCharacter character, int index, List<String> skills) {
        recordChoices(character, index, choices -> choices.withExpertise(List.copyOf(
                merged(choices.expertise(), skills))));
    }

    /**
     * Adds features to those the level history entry at the index records it gained, as a subclass chosen later or a
     * Fighting Style adds them, so levelling down past the level removes them.
     */
    static void appendFeaturesGained(PlayerCharacter character, int index, List<Feature> features) {
        if (features.isEmpty()) {
            return;
        }
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        LevelHistoryEntry target = history.get(index);
        List<Feature> gained = copy(target.featuresGained());
        gained.addAll(features);
        history.set(index, target.withFeaturesGained(gained));
        character.setLevelHistory(history);
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

    // ── Helpers ──────────────────────────────────────────────────

    private static boolean addedAny(ProficienciesGained gained) {
        return gained != null && (nonEmpty(gained.armor()) != null || nonEmpty(gained.weapons()) != null
                || nonEmpty(gained.tools()) != null);
    }

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
