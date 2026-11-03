package com.tabletophelper.rules.character;

import com.tabletophelper.rules.SpellSlotTables;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a class gains at each class level under the 2014 rules (D167): Ability Score Improvements, Expertise, Fighting
 * Styles, and the cantrips, spells known, spells prepared and highest spell level that its class table and
 * Spellcasting feature give. Classes and subclasses are named as the class data names them, such as "Fighter" and
 * "Eldritch Knight", and the Artificer's numbers are Tasha's Cauldron of Everything's. A class level runs from 0,
 * before the character has any levels in the class, to 20. Page references are to the start of the class's or
 * subclass's entry, or of the fighter's class features. The spell tables move to data files in R7.
 */
public final class ClassProgression {

    private static final int MAX_CLASS_LEVEL = 20;

    private static final Set<Integer> ASI_LEVELS = Set.of(4, 8, 12, 16, 19);
    private static final Set<Integer> FIGHTER_ASI_LEVELS = Set.of(4, 6, 8, 12, 14, 16, 19);
    private static final Set<Integer> ROGUE_ASI_LEVELS = Set.of(4, 8, 10, 12, 16, 19);

    // Cantrips Known and Spells Known columns, by class level from 0
    private static final int[] ARTIFICER_CANTRIPS = {0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] BARD_CANTRIPS      = {0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] CLERIC_CANTRIPS    = {0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
    private static final int[] DRUID_CANTRIPS     = {0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] SORCERER_CANTRIPS  = {0, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6};
    private static final int[] WARLOCK_CANTRIPS   = {0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    private static final int[] WIZARD_CANTRIPS    = {0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
    private static final int[] EK_CANTRIPS        = {0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    private static final int[] AT_CANTRIPS        = {0, 0, 0, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};

    private static final int[] BARD_SPELLS     = {0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 15, 16, 18, 19, 19, 20, 22, 22, 22};
    private static final int[] RANGER_SPELLS   = {0, 0, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11};
    private static final int[] SORCERER_SPELLS = {0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 13, 13, 14, 14, 15, 15, 15, 15};
    private static final int[] WARLOCK_SPELLS  = {0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15};
    // The Eldritch Knight and the Arcane Trickster know the same number of spells
    private static final int[] THIRD_CASTER_SPELLS = {0, 0, 0, 3, 4, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11, 11, 12, 13};

    /** How a class prepares spells: not at all, or its spellcasting modifier plus its level or half its level. */
    private enum Preparing { NONE, PLUS_LEVEL, PLUS_HALF_LEVEL }

    /**
     * A class's Spellcasting: its caster type as SpellSlotTables names it, its Cantrips Known column (null for none),
     * its Spells Known column (null for a class that prepares its spells) and how it prepares them.
     */
    private record Spellcasting(String casterType, int[] cantrips, int[] spellsKnown, Preparing preparing) {}

    private static final Map<String, Spellcasting> CLASS_SPELLCASTING = Map.of(
            "Artificer", new Spellcasting("artificer", ARTIFICER_CANTRIPS, null, Preparing.PLUS_HALF_LEVEL),
            "Bard", new Spellcasting("full", BARD_CANTRIPS, BARD_SPELLS, Preparing.NONE),
            "Cleric", new Spellcasting("full", CLERIC_CANTRIPS, null, Preparing.PLUS_LEVEL),
            "Druid", new Spellcasting("full", DRUID_CANTRIPS, null, Preparing.PLUS_LEVEL),
            "Paladin", new Spellcasting("half", null, null, Preparing.PLUS_HALF_LEVEL),
            "Ranger", new Spellcasting("half", null, RANGER_SPELLS, Preparing.NONE),
            "Sorcerer", new Spellcasting("full", SORCERER_CANTRIPS, SORCERER_SPELLS, Preparing.NONE),
            "Warlock", new Spellcasting("pact", WARLOCK_CANTRIPS, WARLOCK_SPELLS, Preparing.NONE),
            "Wizard", new Spellcasting("full", WIZARD_CANTRIPS, null, Preparing.PLUS_LEVEL));

    // The subclasses that give a fighter or rogue Spellcasting, by fighter or rogue level
    private static final Spellcasting ELDRITCH_KNIGHT =
            new Spellcasting("third", EK_CANTRIPS, THIRD_CASTER_SPELLS, Preparing.NONE);
    private static final Spellcasting ARCANE_TRICKSTER =
            new Spellcasting("third", AT_CANTRIPS, THIRD_CASTER_SPELLS, Preparing.NONE);

    private ClassProgression() {}

    /**
     * Whether a class level brings an Ability Score Improvement: 4th, 8th, 12th, 16th and 19th level, and also 6th
     * and 14th for a fighter (PHB p.72) and 10th for a rogue (PHB p.94).
     */
    public static boolean isAsiLevel(String className, int classLevel) {
        requireClassLevel(classLevel);
        if ("Fighter".equals(className)) return FIGHTER_ASI_LEVELS.contains(classLevel);
        if ("Rogue".equals(className)) return ROGUE_ASI_LEVELS.contains(classLevel);
        return ASI_LEVELS.contains(classLevel);
    }

    /**
     * How many proficiencies gain Expertise at a class level: two at Rogue 1st and 6th level (PHB p.94) and at Bard
     * 3rd and 10th (PHB p.51), and none otherwise.
     */
    public static int expertiseChoices(String className, int classLevel) {
        requireClassLevel(classLevel);
        if ("Rogue".equals(className)) return classLevel == 1 || classLevel == 6 ? 2 : 0;
        if ("Bard".equals(className)) return classLevel == 3 || classLevel == 10 ? 2 : 0;
        return 0;
    }

    /**
     * Whether a class level brings a Fighting Style: Fighter 1st level (PHB p.72), Paladin and Ranger 2nd (PHB p.82,
     * p.89), and the Champion's Additional Fighting Style at Fighter 10th (PHB p.72).
     */
    public static boolean isFightingStyleLevel(String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        if ("Fighter".equals(className)) {
            return classLevel == 1 || classLevel == 10 && "Champion".equals(subclassName);
        }
        if ("Paladin".equals(className) || "Ranger".equals(className)) return classLevel == 2;
        return false;
    }

    /**
     * Cantrips known at a class level, from the class's table, or for a fighter or rogue the Eldritch Knight's or
     * Arcane Trickster's (PHB p.74, p.97). 0 for a class without cantrips.
     */
    public static int cantripsKnown(String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        Spellcasting spellcasting = spellcasting(className, subclassName);
        return spellcasting != null && spellcasting.cantrips() != null ? spellcasting.cantrips()[classLevel] : 0;
    }

    /**
     * Spells known at a class level, for a class that knows a set number of them: a bard, ranger, sorcerer or
     * warlock, or an Eldritch Knight or Arcane Trickster. Empty for a class that prepares its spells or has none.
     */
    public static OptionalInt spellsKnown(String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        Spellcasting spellcasting = spellcasting(className, subclassName);
        return spellcasting != null && spellcasting.spellsKnown() != null
                ? OptionalInt.of(spellcasting.spellsKnown()[classLevel]) : OptionalInt.empty();
    }

    /**
     * Spells a class that prepares them can prepare at a class level, from each class's Spellcasting feature: the
     * spellcasting modifier plus the class level for a cleric, druid or wizard, or plus half the class level, rounded
     * down, for a paladin or artificer, and at least one. 0 while the class has no spell slots, as at Paladin 1st
     * level. Empty for a class that doesn't prepare spells.
     */
    public static OptionalInt preparedSpells(String className, int classLevel, int spellcastingModifier) {
        requireClassLevel(classLevel);
        Spellcasting spellcasting = spellcasting(className, null);
        if (spellcasting == null || spellcasting.preparing() == Preparing.NONE) return OptionalInt.empty();
        if (highestSlot(spellcasting, classLevel) == 0) return OptionalInt.of(0);
        int levels = spellcasting.preparing() == Preparing.PLUS_LEVEL ? classLevel : classLevel / 2;
        return OptionalInt.of(Math.max(1, spellcastingModifier + levels));
    }

    /**
     * The highest level of spell a class can learn or prepare at a class level: the highest slot on its own table,
     * whatever slots a multiclass character has (PHB p.164), and for a warlock its Pact Magic slot level, Mystic
     * Arcanum aside. 0 for a class without spell slots.
     */
    public static int highestSpellLevel(String className, String subclassName, int classLevel) {
        requireClassLevel(classLevel);
        Spellcasting spellcasting = spellcasting(className, subclassName);
        return spellcasting != null ? highestSlot(spellcasting, classLevel) : 0;
    }

    private static Spellcasting spellcasting(String className, String subclassName) {
        if ("Fighter".equals(className)) return "Eldritch Knight".equals(subclassName) ? ELDRITCH_KNIGHT : null;
        if ("Rogue".equals(className)) return "Arcane Trickster".equals(subclassName) ? ARCANE_TRICKSTER : null;
        return className != null ? CLASS_SPELLCASTING.get(className) : null;
    }

    // Pact Magic rows list a warlock's slots under their slot level, so either way it is the last level with a slot
    private static int highestSlot(Spellcasting spellcasting, int classLevel) {
        int[] slots = "pact".equals(spellcasting.casterType())
                ? SpellSlotTables.pactMagicRow(classLevel)
                : SpellSlotTables.ownTable(spellcasting.casterType(), classLevel);
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] > 0) return i + 1;
        }
        return 0;
    }

    private static void requireClassLevel(int classLevel) {
        if (classLevel < 0 || classLevel > MAX_CLASS_LEVEL) {
            throw new IllegalArgumentException("A class level is 0 to " + MAX_CLASS_LEVEL + ", not " + classLevel);
        }
    }
}
