package com.tabletophelper.rules.character;

/** Healing on a short rest (PHB p.186); what a long rest restores of the Hit Dice is {@link HitDice}'s. */
public final class Rests {

    private Rests() {}

    /**
     * The hit points one Hit Die spent on a short rest restores: the roll plus the Constitution modifier, and never
     * less than 0 (PHB p.186).
     *
     * @throws IllegalArgumentException for a roll outside 1 to the die's size
     */
    public static int hitDieHealing(int hitDie, int roll, int constitutionModifier) {
        HitDice.requireHitDie(hitDie);
        if (roll < 1 || roll > hitDie) {
            throw new IllegalArgumentException("A d" + hitDie + " rolls 1 to " + hitDie + ", not " + roll);
        }
        return Math.max(0, roll + constitutionModifier);
    }
}
