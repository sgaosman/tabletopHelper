package com.tabletophelper.rules;

/**
 * Rolls damage and healing expressions for the combat code. {@link Dice} reads them, and the numbers come from
 * an {@link Rng}: random unless one is passed in.
 */
public final class DiceRoller {

    private DiceRoller() {}

    public static RollResult roll(String expression) {
        return roll(expression, Rng.random());
    }

    public static RollResult roll(String expression, Rng rng) {
        return result(Dice.parse(expression), rng);
    }

    /** A critical hit: the dice twice over, the modifier once (PHB p.196). */
    public static RollResult rollCritical(String expression) {
        return rollCritical(expression, Rng.random());
    }

    public static RollResult rollCritical(String expression, Rng rng) {
        return result(Dice.parse(expression).doubled(), rng);
    }

    private static RollResult result(Dice dice, Rng rng) {
        int diceTotal = dice.rollDice(rng);
        return new RollResult(diceTotal, dice.modifier(), dice.total(diceTotal), dice.count(), dice.sides());
    }

    public record RollResult(int diceTotal, int modifier, int total, int diceCount, int diceSides) {}
}
