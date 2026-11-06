package com.tabletophelper.rules.character;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The hit point maximum under the 2014 rules (D167), worked out afresh from what each level recorded: each level's
 * hit die result plus the current Constitution modifier, at least 1 a level (PHB p.15), so a changed modifier counts
 * as though the character had it from 1st level (PHB p.177); then the bonuses some races, subclasses and feats give
 * for every level, which their effects in the data hold (R7), and any flat adjustments.
 */
public final class HitPoints {

    private static final int MAX_CHARACTER_LEVEL = 20;

    /** One character level: the class it was taken in and the hit die result it recorded. */
    public record Level(String className, int hitDieResult) {
        public Level {
            if (hitDieResult < 1) {
                throw new IllegalArgumentException("A hit die result is at least 1, not " + hitDieResult);
            }
        }
    }

    /**
     * A bonus to the hit point maximum for every character level, or, when it names a class, for every level in
     * that class.
     */
    public record PerLevelBonus(int amount, String className) {

        /** A bonus for every character level, as from Tough or Dwarven Toughness. */
        public static PerLevelBonus everyLevel(int amount) {
            return new PerLevelBonus(amount, null);
        }

        /** A bonus for every level in one class, as from Draconic Resilience. */
        public static PerLevelBonus perClassLevel(String className, int amount) {
            return new PerLevelBonus(amount, Objects.requireNonNull(className));
        }

        private int total(List<Level> levels) {
            long counted = className == null ? levels.size()
                    : levels.stream().filter(level -> className.equals(level.className())).count();
            return amount * (int) counted;
        }
    }

    private HitPoints() {}

    /**
     * The hit die result a level records without rolling: the die's maximum at character level 1 (PHB p.12), and the
     * fixed value, the die's average rounded up, at every level after (PHB p.15). That includes the first level in a
     * second class, since a multiclass character gains 1st-level hit points only at character level 1 (PHB p.163).
     */
    public static int fixedHitDieResult(int characterLevel, int hitDie) {
        if (characterLevel < 1 || characterLevel > MAX_CHARACTER_LEVEL) {
            throw new IllegalArgumentException(
                    "A character level is 1 to " + MAX_CHARACTER_LEVEL + ", not " + characterLevel);
        }
        HitDice.requireHitDie(hitDie);
        return characterLevel == 1 ? hitDie : hitDie / 2 + 1;
    }

    /**
     * The hit point maximum: for each level its hit die result plus the Constitution modifier, at least 1 (PHB p.15);
     * then each per-level bonus; then the adjustment, the sum of any flat changes such as DM Adjustments (D158). The
     * maximum is never below 1.
     */
    public static int maximum(List<Level> levels, int constitutionModifier, Collection<PerLevelBonus> bonuses,
                              int adjustment) {
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("A character has at least one level");
        }
        int maximum = 0;
        for (Level level : levels) {
            maximum += Math.max(1, level.hitDieResult() + constitutionModifier);
        }
        for (PerLevelBonus bonus : bonuses) {
            maximum += bonus.total(levels);
        }
        return Math.max(1, maximum + adjustment);
    }
}
