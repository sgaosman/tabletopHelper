package com.questkeeper.encounter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiceRoller {

    private static final Pattern DICE_PATTERN = Pattern.compile(
            "^(\\d+)d(\\d+)(?:\\s*\\+\\s*(\\d+))?$", Pattern.CASE_INSENSITIVE);

    private DiceRoller() {}

    public static RollResult roll(String expression) {
        Matcher m = DICE_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid dice expression: " + expression);
        }

        int count = Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

        if (count < 1 || count > 100 || sides < 1 || sides > 100) {
            throw new IllegalArgumentException("Dice values out of range: " + expression);
        }

        int diceTotal = 0;
        for (int i = 0; i < count; i++) {
            diceTotal += ThreadLocalRandom.current().nextInt(1, sides + 1);
        }

        return new RollResult(diceTotal, modifier, diceTotal + modifier, count, sides);
    }

    public static RollResult rollCritical(String expression) {
        Matcher m = DICE_PATTERN.matcher(expression.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid dice expression: " + expression);
        }

        int count = Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        int doubledCount = count * 2;

        int diceTotal = 0;
        for (int i = 0; i < doubledCount; i++) {
            diceTotal += ThreadLocalRandom.current().nextInt(1, sides + 1);
        }

        return new RollResult(diceTotal, modifier, diceTotal + modifier, doubledCount, sides);
    }

    public record RollResult(int diceTotal, int modifier, int total, int diceCount, int diceSides) {}
}
