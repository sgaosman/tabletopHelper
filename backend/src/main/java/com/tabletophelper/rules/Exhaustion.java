package com.tabletophelper.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exhaustion (PHB p.291, appendix A; D183): six levels, each adding its effect to those of the levels below.
 * <ol>
 *   <li>Disadvantage on ability checks</li>
 *   <li>Speed halved</li>
 *   <li>Disadvantage on attack rolls and saving throws</li>
 *   <li>Hit point maximum halved</li>
 *   <li>Speed reduced to 0</li>
 *   <li>Death</li>
 * </ol>
 * An effect that removes exhaustion lowers the level, and the effects of the levels above end. Finishing a long rest
 * lowers it by one, for a creature that has had food and drink (PHB p.186, p.291).
 * <p>
 * A participant's level reaches the combat rules among its condition names as {@link #conditionName}, "exhausted (3)",
 * so every attack roll, saving throw and speed that reads the conditions reads the level too.
 */
public final class Exhaustion {

    /** The highest level: death. */
    public static final int MAX_LEVEL = 6;

    /** The name a condition immunity to exhaustion goes by in the stat blocks (MM). */
    public static final String CONDITION = "exhaustion";

    private static final Pattern NAME = Pattern.compile("^exhausted \\(([1-6])\\)$");

    private static final List<String> EFFECTS = List.of(
            "disadvantage on ability checks",
            "speed halved",
            "disadvantage on attack rolls and saving throws",
            "hit point maximum halved",
            "speed reduced to 0",
            "death");

    private Exhaustion() {}

    /** The level kept between 0 and 6; null counts as 0. */
    public static int clamp(Integer level) {
        return level == null ? 0 : Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /**
     * The level as a condition name, "exhausted (3)", which {@link #level(Collection)} reads back; empty for 0. It is
     * also how the log and the attack sources name it.
     */
    public static String conditionName(int level) {
        int kept = clamp(level);
        return kept == 0 ? "" : "exhausted (" + kept + ")";
    }

    /** The exhaustion level among a creature's condition names, 0 when none is there; the highest counts. */
    public static int level(Collection<String> conditions) {
        if (conditions == null) {
            return 0;
        }
        int level = 0;
        for (String condition : conditions) {
            if (condition == null) continue;
            Matcher matcher = NAME.matcher(condition.trim().toLowerCase(Locale.ROOT));
            if (matcher.matches()) {
                level = Math.max(level, Integer.parseInt(matcher.group(1)));
            }
        }
        return level;
    }

    /** Level 1 and up: disadvantage on ability checks, initiative among them (PHB p.189). */
    public static boolean abilityCheckDisadvantage(int level) {
        return level >= 1;
    }

    /** Level 3 and up: disadvantage on attack rolls. */
    public static boolean attackDisadvantage(int level) {
        return level >= 3;
    }

    /** Level 3 and up: disadvantage on saving throws, death saving throws among them (PHB p.197). */
    public static boolean saveDisadvantage(int level) {
        return level >= 3;
    }

    /** Level 5 and up: speed 0, which also ends the Dodge action's benefit (PHB p.192). */
    public static boolean speedZero(int level) {
        return level >= 5;
    }

    /** A speed in feet at this level: halved, rounding down (PHB p.7), from level 2, and 0 from level 5. */
    public static int speed(int feet, int level) {
        if (speedZero(level)) return 0;
        if (level >= 2) return Math.max(0, feet) / 2;
        return feet;
    }

    /**
     * The hit point maximum at this level: halved, rounding down (PHB p.7), from level 4, but never below 1, since a
     * creature with hit points has a maximum of at least 1.
     */
    public static int hitPointMaximum(int maximum, int level) {
        if (level >= 4) return Math.max(1, maximum / 2);
        return maximum;
    }

    /** Level 6: the creature dies. */
    public static boolean dies(int level) {
        return level >= MAX_LEVEL;
    }

    /** What the level does, its own effect and those below, lowest first; empty for 0. */
    public static List<String> effects(int level) {
        return new ArrayList<>(EFFECTS.subList(0, clamp(level)));
    }

    /** The level after a long rest with food and drink: one lower, not below 0 (PHB p.291). */
    public static int afterLongRest(int level) {
        return Math.max(0, clamp(level) - 1);
    }
}
