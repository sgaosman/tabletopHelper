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
     * The hit points a character has once its maximum changes (D167): a higher maximum raises them by as much, a lower
     * one caps them, and a dead character stays at 0 whatever its maximum does, since only the DM's revival brings it
     * back (D166). A character with no maximum yet, one being created, keeps what it has, within the new maximum.
     */
    public static int afterMaximum(int current, Integer oldMaximum, int newMaximum, boolean dead) {
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
