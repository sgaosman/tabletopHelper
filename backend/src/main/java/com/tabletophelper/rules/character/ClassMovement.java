package com.tabletophelper.rules.character;

/**
 * The walking speed class features add (D230). Both are the class level's, not the character level's, and a
 * multiclass character with both gets both when each one's condition holds: they are different features, and neither
 * says it doesn't combine with the other.
 * <ul>
 *   <li>Fast Movement: +10 feet from Barbarian 5 while not wearing heavy armour (PHB p.49).</li>
 *   <li>Unarmored Movement: +10 feet from Monk 2, +15 from 6, +20 from 10, +25 from 14 and +30 from 18, while wearing
 *       no armour and wielding no shield (PHB p.78).</li>
 * </ul>
 * They raise the walking speed only; a race's flying, swimming or climbing speed with a number of its own stays as it
 * is.
 */
public final class ClassMovement {

    /** The Barbarian level that brings Fast Movement (PHB p.49). */
    public static final int FAST_MOVEMENT_LEVEL = 5;
    /** What Fast Movement adds (PHB p.49). */
    public static final int FAST_MOVEMENT_BONUS = 10;
    /** The Monk level that brings Unarmored Movement (PHB p.78). */
    public static final int UNARMORED_MOVEMENT_LEVEL = 2;

    private ClassMovement() {}

    /**
     * Fast Movement's bonus: 10 feet at Barbarian 5 or higher unless heavy armour is worn (PHB p.49); otherwise 0.
     */
    public static int fastMovement(int barbarianLevel, boolean heavyArmour) {
        return barbarianLevel >= FAST_MOVEMENT_LEVEL && !heavyArmour ? FAST_MOVEMENT_BONUS : 0;
    }

    /**
     * Unarmored Movement's bonus by Monk level (PHB p.78, the Monk table's Unarmored Movement column): 10 feet from
     * 2nd level, 15 from 6th, 20 from 10th, 25 from 14th and 30 from 18th, only while wearing no armour and wielding no
     * shield; otherwise 0.
     */
    public static int unarmoredMovement(int monkLevel, boolean armour, boolean shield) {
        if (armour || shield || monkLevel < UNARMORED_MOVEMENT_LEVEL) {
            return 0;
        }
        if (monkLevel >= 18) return 30;
        if (monkLevel >= 14) return 25;
        if (monkLevel >= 10) return 20;
        if (monkLevel >= 6) return 15;
        return 10;
    }
}
