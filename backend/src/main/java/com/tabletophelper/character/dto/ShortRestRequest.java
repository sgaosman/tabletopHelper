package com.tabletophelper.character.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * The Hit Dice a character spends on a short rest (D158): one entry for each die, naming the class whose Hit Dice it
 * comes from and, if the player rolled it, the roll, from 1 to the die's size. The server rolls a die without one.
 * Spending none still brings back the pools that reset on a short rest and a warlock's pact slots.
 */
public record ShortRestRequest(List<@Valid HitDieSpent> hitDice) {

    /**
     * One Hit Die spent.
     *
     * @param className the class the die is from, as the sheet's Hit Dice name it
     * @param roll      the roll the player made, or null for the server to roll it
     */
    public record HitDieSpent(@NotBlank String className, Integer roll) {
    }
}
