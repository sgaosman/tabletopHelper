package com.tabletophelper.rules.character;

/**
 * A character's hit points between encounters, as its sheet keeps them (PHB p.198, D158): damage comes off the
 * temporary hit points first and stops at 0, healing stops at the maximum and leaves temporary hit points alone, and
 * temporary hit points don't stack, so the character keeps the larger amount. Dying and death saves are combat's
 * (rules.combat.DamageRules): the sheet records only the totals.
 */
public final class CurrentHitPoints {

    /** The hit points a character has, its maximum, and its temporary hit points. */
    public record Totals(int current, int maximum, int temporary) {
        public Totals {
            if (maximum < 1 || current < 0 || current > maximum || temporary < 0) {
                throw new IllegalArgumentException("Impossible hit points: " + current + "/" + maximum + ", "
                        + temporary + " temporary");
            }
        }
    }

    private CurrentHitPoints() {}

    /** Damage: the temporary hit points absorb what they can, and the rest comes off the hit points, down to 0. */
    public static Totals damage(Totals totals, int damage) {
        requireNotNegative(damage, "Damage");
        int absorbed = Math.min(totals.temporary(), damage);
        int current = Math.max(0, totals.current() - (damage - absorbed));
        return new Totals(current, totals.maximum(), totals.temporary() - absorbed);
    }

    /** Healing, up to the maximum. Temporary hit points are untouched: healing can't restore them. */
    public static Totals heal(Totals totals, int healing) {
        requireNotNegative(healing, "Healing");
        int current = totals.current() + Math.min(healing, totals.maximum() - totals.current());
        return new Totals(current, totals.maximum(), totals.temporary());
    }

    /**
     * New temporary hit points replace the old only when there are more of them (PHB p.198; D161 makes the larger
     * amount the default choice).
     */
    public static Totals gainTemporary(Totals totals, int amount) {
        requireNotNegative(amount, "Temporary hit points");
        return new Totals(totals.current(), totals.maximum(), Math.max(totals.temporary(), amount));
    }

    /**
     * The hit points a character has once its maximum changes: a higher maximum raises them by as much, and a lower one
     * takes as much off them, never below 0 (D222, replacing D167's rule that a lower maximum only capped them), so a
     * level gained and then removed leaves a damaged character's hit points as they were. What a drop would take below
     * 0 isn't lost: a level-down records it, and the level's raise gives that much less when it comes back
     * (character.LevelDownSpending, D234, amending D222), so a level removed and gained back leaves them as they were
     * too. A character the drop leaves
     * at 0 is only at 0 hit points: the sheet keeps no dying state, and it never dies of it. A dead character stays at 0
     * whatever its maximum does, since only the DM's revival brings it back (D166). A character with no maximum yet,
     * one being created, keeps what it has, within the new maximum.
     */
    public static int afterMaximum(int current, Integer oldMaximum, int newMaximum, boolean dead) {
        if (dead) {
            return 0;
        }
        int changed = oldMaximum != null ? current + newMaximum - oldMaximum : current;
        return Math.max(0, Math.min(changed, newMaximum));
    }

    /**
     * The hit points a character has once the recompute-characters job repairs a stored maximum the rules disagree
     * with: a higher maximum raises them by as much, and a lower one only caps them, since the stored maximum was
     * never one the character had to lose hit points from (D222 keeps D167's rule for repairs). A dead character stays
     * at 0, and one with no maximum keeps what it has, within the new one.
     */
    public static int afterRepairedMaximum(int current, Integer oldMaximum, int newMaximum, boolean dead) {
        if (dead) {
            return 0;
        }
        int raised = oldMaximum != null && newMaximum > oldMaximum ? current + newMaximum - oldMaximum : current;
        return Math.max(0, Math.min(raised, newMaximum));
    }

    private static void requireNotNegative(int amount, String what) {
        if (amount < 0) {
            throw new IllegalArgumentException(what + " can't be negative: " + amount);
        }
    }
}
