package com.tabletophelper.character.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The Hit Dice a character spends on a short rest (D158): one entry for each die, naming the class whose Hit Dice it
 * comes from and, if the player rolled it, the roll, from 1 to the die's size. The server rolls a die without one.
 * Spending none still brings back the pools that reset on a short rest and a warlock's pact slots.
 *
 * @param hitDice        the Hit Dice spent
 * @param arcaneRecovery a wizard's Arcane Recovery (PHB p.115), once a day: one spell level for each expended slot it
 *                       recovers, such as [2, 1]; left out or empty, it isn't used
 * @param naturalRecovery a Circle of the Land druid's Natural Recovery (PHB p.68, D222), once until a long rest, in
 *                        the same form; left out or empty, it isn't used
 */
public record ShortRestRequest(@Size(max = 20) List<@Valid HitDieSpent> hitDice,
                               @Size(max = 10) List<Integer> arcaneRecovery,
                               @Size(max = 10) List<Integer> naturalRecovery) {

    /** A short rest without Arcane Recovery or Natural Recovery. */
    public ShortRestRequest(List<HitDieSpent> hitDice) {
        this(hitDice, null, null);
    }

    /** A short rest without Natural Recovery. */
    public ShortRestRequest(List<HitDieSpent> hitDice, List<Integer> arcaneRecovery) {
        this(hitDice, arcaneRecovery, null);
    }

    /**
     * One Hit Die spent.
     *
     * @param className the class the die is from, as the sheet's Hit Dice name it
     * @param roll      the roll the player made, or null for the server to roll it
     */
    public record HitDieSpent(@NotBlank String className, Integer roll) {
    }
}
