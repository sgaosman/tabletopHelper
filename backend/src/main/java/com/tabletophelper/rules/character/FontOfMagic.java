package com.tabletophelper.rules.character;

/**
 * A sorcerer's Font of Magic, Flexible Casting (PHB p.101, M18): as a bonus action on their turn, a sorcerer spends
 * sorcery points to create one spell slot, by the Creating Spell Slots table, no higher than 5th level; or expends one
 * spell slot and gains sorcery points equal to its level. A created slot vanishes at the end of a long rest. The
 * sorcerer can never have more sorcery points than their level gives (PHB p.101).
 */
public final class FontOfMagic {

    /** The highest level of spell slot Flexible Casting can create (PHB p.101). */
    public static final int MAX_CREATED_LEVEL = 5;

    /** Which way the exchange goes. */
    public enum Direction {
        /** Expend a spell slot and gain sorcery points equal to its level */
        SLOT_TO_POINTS,
        /** Spend sorcery points to create a spell slot */
        POINTS_TO_SLOT
    }

    // The Creating Spell Slots table (PHB p.101), by slot level from the 1st
    private static final int[] CREATION_COST = {2, 3, 5, 6, 7};

    private FontOfMagic() {
    }

    /**
     * The sorcery points a slot of this level costs to create: 2, 3, 5, 6 and 7 for the 1st to the 5th level (PHB
     * p.101).
     *
     * @throws IllegalArgumentException for a level outside 1 to 5
     */
    public static int creationCost(int slotLevel) {
        if (slotLevel < 1 || slotLevel > MAX_CREATED_LEVEL) {
            throw new IllegalArgumentException("Flexible Casting creates slots of 1st to 5th level, not " + slotLevel);
        }
        return CREATION_COST[slotLevel - 1];
    }

    /** Whether Flexible Casting can create a slot of this level: 1st to 5th (PHB p.101). */
    public static boolean canCreate(int slotLevel) {
        return slotLevel >= 1 && slotLevel <= MAX_CREATED_LEVEL;
    }

    /**
     * The sorcery points expending a slot of this level gives, the slot's level, without going past the most the
     * sorcerer can have (PHB p.101).
     *
     * @param current the points the sorcerer has
     * @param maximum the most their level gives
     */
    public static int pointsGained(int slotLevel, int current, int maximum) {
        if (slotLevel < 1 || slotLevel > 9) {
            throw new IllegalArgumentException("A spell slot is of 1st to 9th level, not " + slotLevel);
        }
        return Math.max(0, Math.min(slotLevel, maximum - current));
    }
}
