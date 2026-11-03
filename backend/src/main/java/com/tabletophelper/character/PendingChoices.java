package com.tabletophelper.character;

import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Source;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.rules.character.ClassProgression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * The Pending Choices a class level brings (D159), worked out from the rules in rules.character's ClassProgression:
 * the subclass at the class's subclass level, a Fighting Style, an Ability Score Improvement, Expertise, the cantrips
 * by which its Cantrips Known rise, and its spells. A class that knows a set number of spells, a bard, ranger,
 * sorcerer or warlock or an Eldritch Knight or Arcane Trickster (PHB p.51, p.89, p.99, p.105, p.74, p.97), learns those
 * by which its Spells Known rise and may replace one it already knows as it does; a wizard copies spells into its
 * spellbook, six at 1st level and two at each level after (PHB p.114); and a class that prepares its spells has none
 * to choose, since it prepares them from its whole list. Invocations, pact boons, Metamagic and maneuvers come later.
 */
final class PendingChoices {

    /** A level's choices in the order they are made, a subclass first since it can bring more, oldest level first. */
    static final Comparator<PendingChoice> ORDER = Comparator.comparingInt(PendingChoice::characterLevel)
            .thenComparing(PendingChoice::type);

    // The choices a subclass can bring on its own: an Eldritch Knight's and Arcane Trickster's spells, and the
    // Champion's Additional Fighting Style
    private static final Set<Type> SUBCLASS_DEPENDENT = EnumSet.of(Type.FIGHTING_STYLE, Type.CANTRIPS, Type.SPELLS);

    private PendingChoices() {
    }

    /**
     * One of a character's class levels, as its choices are worked out.
     *
     * @param subclassName    the subclass the class has by this level, or null for none yet
     * @param subclassLevel   the class level the class chooses its subclass at
     * @param subclassOptions the subclasses it chooses from, by name; a class with none to choose has no SUBCLASS
     */
    record ClassLevel(UUID classId, String className, int classLevel, String subclassName, int subclassLevel,
                      List<String> subclassOptions) {

        ClassLevel withoutSubclass() {
            return new ClassLevel(classId, className, classLevel, null, subclassLevel, subclassOptions);
        }
    }

    /** The choices a class level owes, in the order they are made. */
    static List<PendingChoice> owedAt(int characterLevel, ClassLevel level, Source source) {
        List<PendingChoice> owed = new ArrayList<>();
        String className = level.className();
        int classLevel = level.classLevel();
        if (className == null || classLevel < 1) {
            return owed;
        }
        String subclass = level.subclassName();
        if (classLevel == level.subclassLevel() && subclass == null && !level.subclassOptions().isEmpty()) {
            owed.add(choice(Type.SUBCLASS, source, characterLevel, level, 1, level.subclassOptions(), null, null));
        }
        if (ClassProgression.isFightingStyleLevel(className, subclass, classLevel)) {
            owed.add(choice(Type.FIGHTING_STYLE, source, characterLevel, level, 1,
                    ClassProgression.fightingStyles(className), null, null));
        }
        if (ClassProgression.isAsiLevel(className, classLevel)) {
            owed.add(choice(Type.ASI, source, characterLevel, level, 1, null, null, null));
        }
        int expertise = ClassProgression.expertiseChoices(className, classLevel);
        if (expertise > 0) {
            owed.add(choice(Type.EXPERTISE, source, characterLevel, level, expertise, null, null, null));
        }
        int cantrips = ClassProgression.cantripsKnown(className, subclass, classLevel)
                - ClassProgression.cantripsKnown(className, subclass, classLevel - 1);
        if (cantrips > 0) {
            owed.add(choice(Type.CANTRIPS, source, characterLevel, level, cantrips, null, 0, null));
        }
        int highest = ClassProgression.highestSpellLevel(className, subclass, classLevel);
        int spellbook = ClassProgression.spellbookSpells(className, classLevel);
        if (spellbook > 0) {
            owed.add(choice(Type.SPELLS, source, characterLevel, level, spellbook, null, highest, null));
        } else {
            OptionalInt known = ClassProgression.spellsKnown(className, subclass, classLevel);
            int before = ClassProgression.spellsKnown(className, subclass, classLevel - 1).orElse(0);
            int learned = known.orElse(0) - before;
            if (learned > 0) {
                owed.add(choice(Type.SPELLS, source, characterLevel, level, learned, null, highest,
                        before > 0 ? Boolean.TRUE : null));
            }
        }
        return owed;
    }

    /**
     * The choices a subclass brings to one of the class levels the character already has, when the subclass is chosen
     * after the level was gained: those the class level owes with the subclass and wouldn't without it, such as an
     * Eldritch Knight's cantrips and spells from 3rd level or the Champion's Additional Fighting Style at 10th.
     */
    static List<PendingChoice> owedBySubclass(int characterLevel, ClassLevel level, Source source) {
        Set<Type> without = EnumSet.noneOf(Type.class);
        owedAt(characterLevel, level.withoutSubclass(), source).forEach(choice -> without.add(choice.type()));
        return owedAt(characterLevel, level, source).stream()
                .filter(choice -> SUBCLASS_DEPENDENT.contains(choice.type()) && !without.contains(choice.type()))
                .toList();
    }

    private static PendingChoice choice(Type type, Source source, int characterLevel, ClassLevel level, int count,
                                        List<String> options, Integer maxSpellLevel, Boolean swap) {
        return new PendingChoice(UUID.randomUUID(), type, source, characterLevel, level.classId(), level.className(),
                level.classLevel(), count, options != null ? List.copyOf(options) : null, maxSpellLevel, swap);
    }
}
