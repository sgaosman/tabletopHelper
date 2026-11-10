package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.ClassTables.ClassTable;
import com.tabletophelper.rules.character.ClassTables.Preparing;
import com.tabletophelper.rules.character.ClassTables.Spellcasting;
import com.tabletophelper.rules.character.ClassTables.SubclassTable;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a class gains at each class level under the 2014 rules (D167): Ability Score Improvements, Expertise, Fighting
 * Styles and the styles to choose from, Metamagic options and those to choose from, and the cantrips, spells known, spells prepared, spellbook spells and highest
 * spell level that its class table and Spellcasting feature give. The numbers are the {@link ClassTables}' (R7, from
 * {@code data/content/class-tables.json}), which every method takes. Classes and subclasses are named as the class
 * data names them, such as "Fighter" and "Eldritch Knight", and the Artificer's numbers are Tasha's Cauldron of
 * Everything's. A class level runs from 0, before the character has any levels in the class, to 20.
 */
public final class ClassProgression {

    private ClassProgression() {}

    /**
     * Whether a class level brings an Ability Score Improvement: 4th, 8th, 12th, 16th and 19th level, and also 6th
     * and 14th for a fighter (PHB p.72) and 10th for a rogue (PHB p.94). A class the tables don't list has the
     * defaults.
     */
    public static boolean isAsiLevel(ClassTables tables, String className, int classLevel) {
        requireClassLevel(classLevel);
        List<Integer> levels = tables.classTable(className).map(ClassTable::asiLevels)
                .orElse(tables.defaults().asiLevels());
        return levels.contains(classLevel);
    }

    /**
     * How many proficiencies gain Expertise at a class level: two at Rogue 1st and 6th level (PHB p.94) and at Bard
     * 3rd and 10th (PHB p.51), and none otherwise.
     */
    public static int expertiseChoices(ClassTables tables, String className, int classLevel) {
        requireClassLevel(classLevel);
        return tables.classTable(className).map(table -> table.expertise().getOrDefault(classLevel, 0)).orElse(0);
    }

    /**
     * Whether a class level brings a Fighting Style: Fighter 1st level (PHB p.72), Paladin and Ranger 2nd (PHB p.82,
     * p.89), and the Champion's Additional Fighting Style at Fighter 10th (PHB p.72).
     */
    public static boolean isFightingStyleLevel(ClassTables tables, String className, String subclassName,
                                               int classLevel) {
        requireClassLevel(classLevel);
        boolean fromClass = tables.classTable(className)
                .map(table -> table.fightingStyleLevels().contains(classLevel)).orElse(false);
        boolean fromSubclass = tables.subclassTable(className, subclassName)
                .map(table -> table.fightingStyleLevels().contains(classLevel)).orElse(false);
        return fromClass || fromSubclass;
    }

    /**
     * The Fighting Styles a class chooses from: the fighter's (PHB p.72), which the Champion's Additional Fighting
     * Style chooses from too, the paladin's (PHB p.82) and the ranger's (PHB p.89), each followed by those Tasha's
     * Cauldron of Everything adds for it. Empty for a class without a Fighting Style.
     */
    public static List<String> fightingStyles(ClassTables tables, String className) {
        return tables.classTable(className).map(ClassTable::fightingStyles).orElse(List.of());
    }

    /**
     * How many Metamagic options a class level brings: two at Sorcerer 3rd level and one more at 10th and 17th (PHB
     * p.101–102), and none otherwise.
     */
    public static int metamagicChoices(ClassTables tables, String className, int classLevel) {
        requireClassLevel(classLevel);
        return tables.classTable(className).map(table -> table.metamagic().getOrDefault(classLevel, 0)).orElse(0);
    }

    /**
     * The Metamagic options a class chooses from: the sorcerer's eight in the Player's Handbook (PHB p.102). Empty for a
     * class without Metamagic.
     */
    public static List<String> metamagicOptions(ClassTables tables, String className) {
        return tables.classTable(className).map(ClassTable::metamagicOptions).orElse(List.of());
    }

    /**
     * The level of the spell a class level's Mystic Arcanum is: a 6th-level warlock spell at Warlock 11th level, 7th at
     * 13th, 8th at 15th and 9th at 17th (PHB p.108). 0 for a class level that brings none. Only warlock levels bring
     * one, whatever other classes a multiclass character has.
     */
    public static int mysticArcanumLevel(ClassTables tables, String className, int classLevel) {
        requireClassLevel(classLevel);
        return tables.classTable(className).map(table -> table.mysticArcanum().getOrDefault(classLevel, 0)).orElse(0);
    }

    /**
     * The spells a wizard adds to its spellbook for free at a wizard level: six 1st-level spells at 1st level and two
     * at each level after, each of a level it has spell slots for (PHB p.114). 0 for every other class.
     */
    public static int spellbookSpells(ClassTables tables, String className, int classLevel) {
        requireClassLevel(classLevel);
        Optional<ClassTables.Spellbook> spellbook = tables.classTable(className).map(ClassTable::spellbook);
        if (spellbook.isEmpty() || classLevel == 0) return 0;
        return classLevel == 1 ? spellbook.get().firstLevel() : spellbook.get().laterLevels();
    }

    /**
     * Cantrips known at a class level, from the class's table, or for a fighter or rogue the Eldritch Knight's or
     * Arcane Trickster's (PHB p.74, p.97). 0 for a class without cantrips.
     */
    public static int cantripsKnown(ClassTables tables, String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        return spellcasting(tables, className, subclassName)
                .filter(spellcasting -> !spellcasting.cantripsKnown().isEmpty())
                .map(spellcasting -> spellcasting.cantripsKnown().get(classLevel))
                .orElse(0);
    }

    /**
     * Spells known at a class level, for a class that knows a set number of them: a bard, ranger, sorcerer or
     * warlock, or an Eldritch Knight or Arcane Trickster. Empty for a class that prepares its spells or has none.
     */
    public static OptionalInt spellsKnown(ClassTables tables, String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        return spellcasting(tables, className, subclassName)
                .filter(spellcasting -> !spellcasting.spellsKnown().isEmpty())
                .map(spellcasting -> OptionalInt.of(spellcasting.spellsKnown().get(classLevel)))
                .orElse(OptionalInt.empty());
    }

    /**
     * Spells a class that prepares them can prepare at a class level, from each class's Spellcasting feature: the
     * spellcasting modifier plus the class level for a cleric, druid or wizard, or plus half the class level, rounded
     * down, for a paladin or artificer, and at least one. 0 while the class has no spell slots, as at Paladin 1st
     * level. Empty for a class that doesn't prepare spells.
     */
    public static OptionalInt preparedSpells(ClassTables tables, String className, int classLevel,
                                             int spellcastingModifier) {
        requireClassLevel(classLevel);
        Optional<Spellcasting> found = spellcasting(tables, className, null);
        if (found.isEmpty() || found.get().preparing() == Preparing.NONE) return OptionalInt.empty();
        Spellcasting spellcasting = found.get();
        if (highestSlot(tables, spellcasting, classLevel) == 0) return OptionalInt.of(0);
        int levels = spellcasting.preparing() == Preparing.LEVEL ? classLevel : classLevel / 2;
        return OptionalInt.of(Math.max(1, spellcastingModifier + levels));
    }

    /**
     * The highest level of spell a class can learn or prepare at a class level: the highest slot on its own table,
     * whatever slots a multiclass character has (PHB p.164), and for a warlock its Pact Magic slot level, Mystic
     * Arcanum aside. 0 for a class without spell slots.
     */
    public static int highestSpellLevel(ClassTables tables, String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        return spellcasting(tables, className, subclassName)
                .map(spellcasting -> highestSlot(tables, spellcasting, classLevel)).orElse(0);
    }

    /**
     * The spell list a class learns and prepares from, as the spells table names it: the class's own, or the wizard's
     * for an Eldritch Knight or Arcane Trickster (PHB p.75, p.98). Empty for a class whose tables give it no
     * Spellcasting.
     */
    public static Optional<String> spellList(ClassTables tables, String className, String subclassName) {
        return spellcasting(tables, className, subclassName).map(Spellcasting::spellList);
    }

    /**
     * The caster type a subclass gives a class without Spellcasting, as "third" for an Eldritch Knight or Arcane
     * Trickster (PHB p.75, p.98); empty for a subclass whose tables give it no Spellcasting of its own.
     */
    public static Optional<String> subclassCasterType(ClassTables tables, String className, String subclassName) {
        return tables.subclassTable(className, subclassName).map(SubclassTable::spellcasting)
                .map(Spellcasting::casterType);
    }

    // A subclass's Spellcasting, as an Eldritch Knight's for a fighter, or else the class's own
    private static Optional<Spellcasting> spellcasting(ClassTables tables, String className, String subclassName) {
        Optional<Spellcasting> subclass = tables.subclassTable(className, subclassName)
                .map(SubclassTable::spellcasting);
        return subclass.isPresent() ? subclass : tables.classTable(className).map(ClassTable::spellcasting);
    }

    // Pact Magic rows list a warlock's slots under their slot level, so either way it is the last level with a slot
    private static int highestSlot(ClassTables tables, Spellcasting spellcasting, int classLevel) {
        int[] slots = tables.slotRow(spellcasting.casterType(), classLevel);
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] > 0) return i + 1;
        }
        return 0;
    }

    private static void requireClassLevel(int classLevel) {
        if (classLevel < 0 || classLevel > ClassTables.MAX_CLASS_LEVEL) {
            throw new IllegalArgumentException(
                    "A class level is 0 to " + ClassTables.MAX_CLASS_LEVEL + ", not " + classLevel);
        }
    }
}
