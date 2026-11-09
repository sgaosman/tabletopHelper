package com.tabletophelper.rules;

import com.tabletophelper.shared.ValidationException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A damage or healing expression: count dice with the given number of sides, plus a modifier. A flat amount
 * has no dice, so its count and sides are 0. The monster data uses every form {@link #parse} reads, such as a
 * cat's flat 1 damage and a {@code 1d6 - 1} bite.
 */
public record Dice(int count, int sides, int modifier) {

    private static final Pattern EXPRESSION = Pattern.compile(
            "^(?:(\\d{1,9})d(\\d{1,9})(?:\\s*([+-])\\s*(\\d{1,9}))?|(\\d{1,9}))$", Pattern.CASE_INSENSITIVE);

    public Dice {
        if (count < 0 || sides < 0 || (count == 0) != (sides == 0)) {
            throw new IllegalArgumentException("Dice need both a count and sides, or neither: " + count + "d" + sides);
        }
    }

    /**
     * Reads {@code NdM}, {@code NdM + K} or {@code NdM - K}, with or without spaces around the sign, or a flat
     * {@code K}: 1 to 100 dice of 1 to 100 sides.
     *
     * @throws ValidationException for anything else
     */
    public static Dice parse(String expression) {
        Matcher m = EXPRESSION.matcher(expression == null ? "" : expression.trim());
        if (!m.matches()) {
            throw new ValidationException("Invalid dice expression: " + expression);
        }
        if (m.group(5) != null) {
            return new Dice(0, 0, Integer.parseInt(m.group(5)));
        }

        int count = Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        if (count < 1 || count > 100 || sides < 1 || sides > 100) {
            throw new ValidationException("Dice values out of range: " + expression);
        }
        int modifier = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        return new Dice(count, sides, "-".equals(m.group(3)) ? -modifier : modifier);
    }

    /** The dice twice over with the same modifier: a critical hit's damage (PHB p.196). */
    public Dice doubled() {
        return new Dice(count * 2, sides, modifier);
    }

    /** Rolls the dice and returns their sum, leaving out the modifier. */
    public int rollDice(Rng rng) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            sum += rng.roll(sides);
        }
        return sum;
    }

    /**
     * Rolls the dice and returns their sum, leaving out the modifier, rolling each die that shows rerollUpTo or less
     * once more and keeping the new roll, as Great Weapon Fighting does with its 1s and 2s (PHB p.72). A rerollUpTo of
     * 0 rerolls nothing.
     */
    public int rollDice(Rng rng, int rerollUpTo) {
        int sum = 0;
        for (int roll : rollEach(rng, rerollUpTo)) {
            sum += roll;
        }
        return sum;
    }

    /**
     * Each die's roll, in order, rerolled as {@link #rollDice(Rng, int)} does, so a caller can reroll some of them
     * again, as Empowered Spell does (PHB p.102).
     */
    public int[] rollEach(Rng rng, int rerollUpTo) {
        int[] rolls = new int[count];
        for (int i = 0; i < count; i++) {
            int roll = rng.roll(sides);
            if (roll <= rerollUpTo) {
                roll = rng.roll(sides);
            }
            rolls[i] = roll;
        }
        return rolls;
    }

    /** The dice's average roll with the modifier, such as 4.5 for 1d8, to compare two damage dice. */
    public double average() {
        return count * (sides + 1) / 2.0 + modifier;
    }

    /**
     * The result when the dice show diceTotal: the modifier added, but never below 0. A penalty can bring
     * damage to 0, never below (PHB p.196).
     */
    public int total(int diceTotal) {
        return Math.max(0, diceTotal + modifier);
    }

    /** Rolls the dice and adds the modifier; see {@link #total}. */
    public int roll(Rng rng) {
        return total(rollDice(rng));
    }

    /** The expression in one form: {@code 2d6 + 3}, {@code 1d6 - 1}, {@code 1d8} or {@code 1}. */
    @Override
    public String toString() {
        if (count == 0) return String.valueOf(modifier);
        String dice = count + "d" + sides;
        if (modifier == 0) return dice;
        return dice + (modifier > 0 ? " + " : " - ") + Math.abs(modifier);
    }
}
