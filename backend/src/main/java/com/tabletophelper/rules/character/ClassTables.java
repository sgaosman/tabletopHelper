package com.tabletophelper.rules.character;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The class tables of the 2014 rules (D167, D169), as {@code data/content/class-tables.json} holds them and the content
 * module loads them at every start: the spell slots of each caster type, and for each class its subclass level, its
 * Ability Score Improvement, Expertise and Fighting Style levels, the Fighting Styles it chooses from, its Cantrips
 * Known and Spells Known columns, how it prepares spells, the spell list it uses and its spellbook. The Eldritch
 * Knight's and Arcane Trickster's spellcasting, with the wizard's spell list, and the Champion's Additional Fighting
 * Style are their subclasses'. Every column runs by class level from 0, before the character has any levels in the
 * class, to 20, and every slot row lists the slots by spell level from 1st; Pact Magic rows hold the count at the
 * slot's level. Immutable. ClassProgression reads the rules from it.
 *
 * @param spellSlots the slot table of each caster type: full, half, artificer, third and pact
 * @param defaults   the subclass level and Ability Score Improvement levels of a class the tables don't list
 * @param classes    each class's table, by the name the class data gives it, such as "Fighter"
 * @param subclasses each subclass's own table, by name, such as "Eldritch Knight"
 */
public record ClassTables(Map<String, List<List<Integer>>> spellSlots, Defaults defaults,
                          Map<String, ClassTable> classes, Map<String, SubclassTable> subclasses) {

    /** The highest class level. */
    public static final int MAX_CLASS_LEVEL = 20;

    /** The caster types that have a slot table. */
    public static final Set<String> CASTER_TYPES = Set.of("full", "half", "artificer", "third", "pact");

    // The Multiclass Spellcaster table is the full caster's table, by combined caster level (PHB p.165)
    private static final String MULTICLASS_TABLE = "full";
    private static final String PACT_MAGIC = "pact";
    private static final int[] NO_SLOTS = new int[0];

    /** How a class prepares spells: not at all, or its spellcasting modifier plus its level or half its level. */
    public enum Preparing { NONE, LEVEL, HALF_LEVEL }

    /** The subclass level and Ability Score Improvement levels of a class the tables don't list. */
    public record Defaults(int subclassLevel, List<Integer> asiLevels) {
        public Defaults {
            requireClassLevel(subclassLevel, "defaults.subclassLevel");
            asiLevels = classLevels(asiLevels, "defaults.asiLevels");
        }
    }

    /**
     * One class's table.
     *
     * @param expertise           how many proficiencies gain Expertise, by class level
     * @param fightingStyleLevels the class levels that bring a Fighting Style
     * @param fightingStyles      the Fighting Styles the class chooses from, in order
     * @param spellcasting        its Spellcasting, or null for a class without
     * @param spellbook           its spellbook, or null for a class without
     * @param metamagic           how many Metamagic options the class learns, by class level (a sorcerer's, PHB
     *                            p.101–102); empty for a class without
     * @param metamagicOptions    the Metamagic options it chooses from, in order
     * @param mysticArcanum       the level of the spell each Mystic Arcanum is, by the class level that brings it (a
     *                            warlock's 6th at 11th level, 7th at 13th, 8th at 15th and 9th at 17th, PHB p.108);
     *                            empty for a class without
     */
    public record ClassTable(int subclassLevel, List<Integer> asiLevels, Map<Integer, Integer> expertise,
                             List<Integer> fightingStyleLevels, List<String> fightingStyles,
                             Spellcasting spellcasting, Spellbook spellbook, Map<Integer, Integer> metamagic,
                             List<String> metamagicOptions, Map<Integer, Integer> mysticArcanum) {
        public ClassTable {
            requireClassLevel(subclassLevel, "subclassLevel");
            asiLevels = classLevels(asiLevels, "asiLevels");
            expertise = expertise != null ? Map.copyOf(expertise) : Map.of();
            expertise.keySet().forEach(level -> requireClassLevel(level, "expertise"));
            fightingStyleLevels = classLevels(fightingStyleLevels, "fightingStyleLevels");
            fightingStyles = fightingStyles != null ? List.copyOf(fightingStyles) : List.of();
            metamagic = metamagic != null ? Map.copyOf(metamagic) : Map.of();
            metamagic.keySet().forEach(level -> requireClassLevel(level, "metamagic"));
            metamagicOptions = metamagicOptions != null ? List.copyOf(metamagicOptions) : List.of();
            mysticArcanum = mysticArcanum != null ? Map.copyOf(mysticArcanum) : Map.of();
            mysticArcanum.keySet().forEach(level -> requireClassLevel(level, "mysticArcanum"));
            mysticArcanum.values().forEach(spellLevel -> {
                if (spellLevel < 1 || spellLevel > 9) {
                    throw new IllegalArgumentException("A Mystic Arcanum's spell is of level 1 to 9, not "
                            + spellLevel);
                }
            });
        }
    }

    /**
     * A subclass's own table.
     *
     * @param className           the class it belongs to
     * @param fightingStyleLevels the class levels at which it brings a Fighting Style
     * @param spellcasting        the Spellcasting it gives a class without, or null
     */
    public record SubclassTable(String className, List<Integer> fightingStyleLevels, Spellcasting spellcasting) {
        public SubclassTable {
            Objects.requireNonNull(className, "a subclass names its class");
            fightingStyleLevels = classLevels(fightingStyleLevels, "fightingStyleLevels");
        }
    }

    /**
     * A class's or subclass's Spellcasting.
     *
     * @param casterType    the slot table it casts with
     * @param spellList     the class whose spell list it learns and prepares from, as the spells table names it: the
     *                      class's own, or "Wizard" for an Eldritch Knight or Arcane Trickster (PHB p.75, p.98)
     * @param cantripsKnown its Cantrips Known column, or empty for none
     * @param spellsKnown   its Spells Known column, or empty for a class that prepares its spells
     */
    public record Spellcasting(String casterType, String spellList, List<Integer> cantripsKnown,
                               List<Integer> spellsKnown, Preparing preparing) {
        public Spellcasting {
            if (!CASTER_TYPES.contains(casterType)) {
                throw new IllegalArgumentException("A caster type is one of " + CASTER_TYPES + ", not " + casterType);
            }
            if (spellList == null || spellList.isBlank()) {
                throw new IllegalArgumentException("A Spellcasting names the spell list it uses");
            }
            cantripsKnown = column(cantripsKnown, "cantripsKnown");
            spellsKnown = column(spellsKnown, "spellsKnown");
            preparing = preparing != null ? preparing : Preparing.NONE;
        }
    }

    /** A spellbook: the spells added for free at the class's 1st level, and at each level after (PHB p.114). */
    public record Spellbook(int firstLevel, int laterLevels) {}

    public ClassTables {
        Map<String, List<List<Integer>>> slots = new LinkedHashMap<>();
        for (String type : CASTER_TYPES) {
            List<List<Integer>> rows = spellSlots != null ? spellSlots.get(type) : null;
            if (rows == null || rows.size() != MAX_CLASS_LEVEL + 1) {
                throw new IllegalArgumentException("The " + type + " slot table has a row for each class level from 0 "
                        + "to " + MAX_CLASS_LEVEL);
            }
            List<List<Integer>> copied = new ArrayList<>();
            rows.forEach(row -> copied.add(List.copyOf(row)));
            slots.put(type, List.copyOf(copied));
        }
        if (spellSlots.size() != CASTER_TYPES.size()) {
            throw new IllegalArgumentException("The slot tables are " + CASTER_TYPES + ", not " + spellSlots.keySet());
        }
        spellSlots = Map.copyOf(slots);
        Objects.requireNonNull(defaults, "the tables have defaults");
        classes = classes != null ? Map.copyOf(classes) : Map.of();
        subclasses = subclasses != null ? Map.copyOf(subclasses) : Map.of();
        for (Map.Entry<String, SubclassTable> subclass : subclasses.entrySet()) {
            if (!classes.containsKey(subclass.getValue().className())) {
                throw new IllegalArgumentException("The subclass " + subclass.getKey() + " belongs to "
                        + subclass.getValue().className() + ", which the tables don't list");
            }
        }
    }

    // ── Spell slots ──────────────────────────────────────────────

    /** Slots for one class on its own table at its class level; empty for a caster type without one or no slots. */
    public int[] slotRow(String casterType, int classLevel) {
        List<List<Integer>> table = casterType != null ? spellSlots.get(casterType) : null;
        if (table == null || classLevel <= 0 || classLevel > MAX_CLASS_LEVEL) return NO_SLOTS;
        return table.get(classLevel).stream().mapToInt(Integer::intValue).toArray();
    }

    /** The Multiclass Spellcaster table's row for a combined caster level. */
    public int[] multiclassRow(int casterLevel) {
        return slotRow(MULTICLASS_TABLE, casterLevel);
    }

    /** Pact Magic slots at a warlock level, indexed by slot level. */
    public int[] pactMagicRow(int warlockLevel) {
        return slotRow(PACT_MAGIC, warlockLevel);
    }

    /**
     * A caster type's slots as the class data stores them: {"classLevel": {"spellLevel": count}}, leaving out levels
     * without slots. Null for a caster type without a table.
     */
    public Map<String, Map<String, Integer>> slotProgression(String casterType) {
        if (casterType == null || !spellSlots.containsKey(casterType)) return null;
        Map<String, Map<String, Integer>> progression = new LinkedHashMap<>();
        for (int level = 1; level <= MAX_CLASS_LEVEL; level++) {
            int[] row = slotRow(casterType, level);
            Map<String, Integer> slots = new LinkedHashMap<>();
            for (int i = 0; i < row.length; i++) {
                if (row[i] > 0) slots.put(String.valueOf(i + 1), row[i]);
            }
            if (!slots.isEmpty()) progression.put(String.valueOf(level), slots);
        }
        return progression;
    }

    // ── Classes and subclasses ───────────────────────────────────

    /** The class's table, if the tables list it. */
    public Optional<ClassTable> classTable(String className) {
        return className != null ? Optional.ofNullable(classes.get(className)) : Optional.empty();
    }

    /** The subclass's own table, if it has one and belongs to the class. */
    public Optional<SubclassTable> subclassTable(String className, String subclassName) {
        if (subclassName == null) return Optional.empty();
        return Optional.ofNullable(subclasses.get(subclassName))
                .filter(subclass -> subclass.className().equals(className));
    }

    /** The class level a class chooses its subclass at, or the default for a class the tables don't list. */
    public int subclassLevel(String className) {
        return classTable(className).map(ClassTable::subclassLevel).orElse(defaults.subclassLevel());
    }

    // ── Validation ───────────────────────────────────────────────

    private static List<Integer> classLevels(List<Integer> levels, String what) {
        if (levels == null) return List.of();
        levels.forEach(level -> requireClassLevel(level, what));
        return List.copyOf(levels);
    }

    // A column has a value for each class level from 0 to 20, or none at all
    private static List<Integer> column(List<Integer> column, String what) {
        if (column == null || column.isEmpty()) return List.of();
        if (column.size() != MAX_CLASS_LEVEL + 1) {
            throw new IllegalArgumentException(what + " has a value for each class level from 0 to " + MAX_CLASS_LEVEL
                    + ", not " + column.size() + " values");
        }
        return List.copyOf(column);
    }

    private static void requireClassLevel(Integer level, String what) {
        if (level == null || level < 1 || level > MAX_CLASS_LEVEL) {
            throw new IllegalArgumentException(what + " lists class levels from 1 to " + MAX_CLASS_LEVEL + ", not "
                    + level);
        }
    }
}
