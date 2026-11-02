package com.tabletophelper.character;

/**
 * Proficiency bonus by character level (PHB p.15) and by challenge rating (MM p.8). Both tables start at +2
 * and rise by 1 every four levels or CRs, so levels above 20 continue on the challenge rating table.
 */
public final class ProficiencyBonus {

    private ProficiencyBonus() {}

    /** A character level, or a monster's challenge rating rounded up. */
    public static int forLevel(int level) {
        if (level <= 4) return 2;
        return (level - 1) / 4 + 2;
    }

    /**
     * A challenge rating as monsters store it: "0", "1/8", "1/4", "1/2" or a whole number.
     * A missing or unreadable rating gets +2.
     */
    public static int forChallengeRating(String cr) {
        if (cr == null) return 2;
        try {
            return forLevel((int) Math.ceil(parseChallengeRating(cr)));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private static double parseChallengeRating(String cr) {
        return switch (cr) {
            case "1/8" -> 0.125;
            case "1/4" -> 0.25;
            case "1/2" -> 0.5;
            default -> Double.parseDouble(cr);
        };
    }
}
