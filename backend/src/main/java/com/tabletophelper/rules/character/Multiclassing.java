package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multiclassing prerequisites (PHB p.163). To take a level in a new class, a character must meet the ability score
 * prerequisites of the new class and of every class it already has; another level in a class it has needs no check.
 */
public final class Multiclassing {

    /**
     * A class's prerequisites: a minimum score in each ability named, or, when any is set, in at least one of them,
     * as for the fighter's Strength 13 or Dexterity 13. A class without prerequisites names none.
     */
    public record Prerequisites(Map<Ability, Integer> minimums, boolean any) {

        public static final Prerequisites NONE = new Prerequisites(Map.of(), false);

        public Prerequisites {
            minimums = Map.copyOf(minimums);
        }

        /** Every minimum must be met. */
        public static Prerequisites allOf(Map<Ability, Integer> minimums) {
            return new Prerequisites(minimums, false);
        }

        /** Any one minimum will do. */
        public static Prerequisites anyOf(Map<Ability, Integer> minimums) {
            return new Prerequisites(minimums, true);
        }

        /** Whether the scores meet these prerequisites; an ability missing from the scores counts as 0. */
        public boolean metBy(Map<Ability, Integer> scores) {
            if (minimums.isEmpty()) return true;
            return any
                    ? minimums.entrySet().stream().anyMatch(minimum -> meets(scores, minimum))
                    : minimums.entrySet().stream().allMatch(minimum -> meets(scores, minimum));
        }

        private static boolean meets(Map<Ability, Integer> scores, Map.Entry<Ability, Integer> minimum) {
            return scores.getOrDefault(minimum.getKey(), 0) >= minimum.getValue();
        }
    }

    private Multiclassing() {}

    /**
     * The classes whose prerequisites the scores don't meet when a character takes a level in a class: the new
     * class first, then the current classes in the order given. Empty when it may take the level, which it always
     * may in a class it already has.
     */
    public static List<String> unmetPrerequisites(Map<Ability, Integer> scores,
                                                  Map<String, Prerequisites> currentClasses,
                                                  String newClass, Prerequisites newClassPrerequisites) {
        if (currentClasses.containsKey(newClass)) return List.of();
        List<String> unmet = new ArrayList<>();
        if (!newClassPrerequisites.metBy(scores)) unmet.add(newClass);
        currentClasses.forEach((className, prerequisites) -> {
            if (!prerequisites.metBy(scores)) unmet.add(className);
        });
        return List.copyOf(unmet);
    }

    /** Whether a character with these scores may take a level in the class. */
    public static boolean canAdd(Map<Ability, Integer> scores, Map<String, Prerequisites> currentClasses,
                                 String newClass, Prerequisites newClassPrerequisites) {
        return unmetPrerequisites(scores, currentClasses, newClass, newClassPrerequisites).isEmpty();
    }
}
