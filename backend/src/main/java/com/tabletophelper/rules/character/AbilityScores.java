package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;

/**
 * The ability score maximum and what an increase actually adds under it (D167). A score can't be increased above 20
 * (PHB p.15), except that Primal Champion, the barbarian's 20th-level feature, raises Strength's and Constitution's
 * maximum to 24 (the Barbarian, PHB p.46).
 */
public final class AbilityScores {

    /** The highest a score can be increased to. */
    public static final int MAXIMUM = 20;

    /** Strength's and Constitution's maximum once a barbarian has Primal Champion, at 20th level. */
    public static final int PRIMAL_CHAMPION_MAXIMUM = 24;

    private AbilityScores() {}

    /** The ability's maximum: 20, or 24 for Strength and Constitution after Primal Champion. */
    public static int maximum(Ability ability, boolean primalChampion) {
        return primalChampion && (ability == Ability.STR || ability == Ability.CON) ? PRIMAL_CHAMPION_MAXIMUM : MAXIMUM;
    }

    /**
     * The part of an increase that applies: the amount asked for, up to the ability's maximum. A score already at or
     * above the maximum gains nothing.
     *
     * @throws IllegalArgumentException for a negative amount
     */
    public static int increase(Ability ability, int score, int amount, boolean primalChampion) {
        if (amount < 0) {
            throw new IllegalArgumentException("An increase can't be negative: " + amount);
        }
        return Math.max(0, Math.min(amount, maximum(ability, primalChampion) - score));
    }
}
