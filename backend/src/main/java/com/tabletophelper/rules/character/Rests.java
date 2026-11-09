package com.tabletophelper.rules.character;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Healing on a short rest (PHB p.186), and the wizard's Arcane Recovery on one (PHB p.115); what a long rest restores
 * of the Hit Dice is {@link HitDice}'s.
 */
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

    /** The highest slot level Arcane Recovery can recover is 5th: none can be 6th level or higher (PHB p.115). */
    public static final int ARCANE_RECOVERY_HIGHEST_SLOT = 5;

    /** The combined slot levels Arcane Recovery recovers at most: half the wizard's level, rounded up (PHB p.115). */
    public static int arcaneRecoveryLevels(int wizardLevel) {
        return (wizardLevel + 1) / 2;
    }

    /**
     * Why Arcane Recovery can't recover these slots, or empty (PHB p.115): once a day when finishing a short rest, a
     * wizard chooses expended spell slots whose levels add up to no more than half its wizard level, rounded up, none
     * of 6th level or higher.
     *
     * @param slotLevels one level for each slot to recover, such as [2, 1]
     * @param expended   how many slots of each level are expended
     */
    public static Optional<String> arcaneRecoveryRefusal(int wizardLevel, List<Integer> slotLevels,
                                                         Map<Integer, Integer> expended) {
        if (wizardLevel < 1) {
            return Optional.of("Arcane Recovery is a wizard's feature");
        }
        if (slotLevels.isEmpty()) {
            return Optional.of("Choose at least one expended spell slot to recover");
        }
        Map<Integer, Integer> wanted = new TreeMap<>();
        int total = 0;
        for (Integer level : slotLevels) {
            if (level == null || level < 1 || level > ARCANE_RECOVERY_HIGHEST_SLOT) {
                return Optional.of("Arcane Recovery recovers slots of 1st to 5th level, not " + level);
            }
            wanted.merge(level, 1, Integer::sum);
            total += level;
        }
        int most = arcaneRecoveryLevels(wizardLevel);
        if (total > most) {
            return Optional.of("Arcane Recovery recovers slots whose levels add up to " + most + " at most (half of "
                    + "wizard level " + wizardLevel + ", rounded up), not " + total);
        }
        for (Map.Entry<Integer, Integer> entry : wanted.entrySet()) {
            int spent = expended.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > spent) {
                return Optional.of("Only " + spent + " level " + entry.getKey() + " spell slot"
                        + (spent == 1 ? " is" : "s are") + " expended, not " + entry.getValue());
            }
        }
        return Optional.empty();
    }
}
