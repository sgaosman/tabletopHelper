package com.tabletophelper.rules;

import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiceRollerTest {

    @Test
    @DisplayName("9.1 roll(\"1d6\"): total is between 1 and 6 with modifier 0")
    void roll1d6BasicRoll() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.roll("1d6");
            assertTrue(result.total() >= 1 && result.total() <= 6,
                    "Total should be between 1 and 6, got: " + result.total());
            assertEquals(0, result.modifier());
            assertEquals(1, result.diceCount());
            assertEquals(6, result.diceSides());
            assertEquals(result.diceTotal(), result.total(),
                    "With zero modifier, total should equal diceTotal");
        }
    }

    @Test
    @DisplayName("9.2 roll(\"2d6+3\"): total is between 5 and 15 with modifier 3")
    void roll2d6Plus3() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.roll("2d6+3");
            assertTrue(result.total() >= 5 && result.total() <= 15,
                    "Total should be between 5 and 15, got: " + result.total());
            assertEquals(3, result.modifier());
            assertEquals(2, result.diceCount());
            assertEquals(6, result.diceSides());
            assertEquals(result.diceTotal() + 3, result.total(),
                    "Total should equal diceTotal + modifier");
        }
    }

    @Test
    @DisplayName("9.3 roll(\"1d20-2\"): a negative modifier is subtracted, and the total never goes below 0")
    void rollNegativeModifier() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.roll("1d20-2");
            assertEquals(-2, result.modifier());
            assertEquals(1, result.diceCount());
            assertEquals(20, result.diceSides());
            assertEquals(Math.max(0, result.diceTotal() - 2), result.total());
            assertTrue(result.total() >= 0 && result.total() <= 18,
                    "Total should be between 0 and 18, got: " + result.total());
        }
    }

    @Test
    @DisplayName("9.4 roll(\"1d8\"): parses diceCount 1 and diceSides 8")
    void roll1d8ParsesDiceCountAndSides() {
        for (int i = 0; i < 50; i++) {
            DiceRoller.RollResult result = DiceRoller.roll("1d8");
            assertEquals(1, result.diceCount());
            assertEquals(8, result.diceSides());
            assertEquals(0, result.modifier());
            assertTrue(result.total() >= 1 && result.total() <= 8,
                    "Total should be between 1 and 8, got: " + result.total());
        }
    }

    @Test
    @DisplayName("9.5 rollCritical(\"1d8+3\"): doubles dice count from 1 to 2")
    void rollCriticalDoublesDiceCount() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.rollCritical("1d8+3");
            assertEquals(2, result.diceCount(), "Critical should double dice count from 1 to 2");
            assertEquals(8, result.diceSides());
            assertEquals(3, result.modifier());
            // 2d8+3: min = 2+3=5, max = 16+3=19
            assertTrue(result.total() >= 5 && result.total() <= 19,
                    "Critical 1d8+3 total should be between 5 and 19, got: " + result.total());
        }
    }

    @Test
    @DisplayName("9.6 rollCritical(\"1d10+5\"): modifier is NOT doubled, remains 5")
    void rollCriticalModifierNotDoubled() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.rollCritical("1d10+5");
            assertEquals(5, result.modifier(), "Modifier should remain 5, not be doubled to 10");
            assertEquals(2, result.diceCount(), "Dice count should be doubled from 1 to 2");
            // 2d10+5: min = 2+5=7, max = 20+5=25
            assertTrue(result.total() >= 7 && result.total() <= 25,
                    "Critical 1d10+5 total should be between 7 and 25, got: " + result.total());
        }
    }

    @Test
    @DisplayName("9.7 roll(\"1d20\"): zero modifier, total equals raw dice result")
    void rollWithZeroModifier() {
        for (int i = 0; i < 100; i++) {
            DiceRoller.RollResult result = DiceRoller.roll("1d20");
            assertEquals(0, result.modifier());
            assertEquals(result.diceTotal(), result.total(),
                    "With zero modifier, total should equal diceTotal");
            assertTrue(result.total() >= 1 && result.total() <= 20,
                    "d20 total should be between 1 and 20, got: " + result.total());
        }
    }

    @Test
    @DisplayName("9.8 Invalid expressions throw ValidationException")
    void invalidExpressionThrowsException() {
        assertThrows(ValidationException.class, () -> DiceRoller.roll("abc"),
                "Alphabetic-only input should throw");
        assertThrows(ValidationException.class, () -> DiceRoller.roll("d6d8"),
                "Malformed expression should throw");
        assertThrows(ValidationException.class, () -> DiceRoller.roll(""),
                "Empty string should throw");
    }

    @Test
    @DisplayName("9.9 roll(\"1\") and rollCritical(\"1\"): flat damage rolls no dice, and a critical hit doesn't change it")
    void flatDamage() {
        DiceRoller.RollResult result = DiceRoller.roll("1");
        assertEquals(1, result.total());
        assertEquals(0, result.diceTotal());
        assertEquals(1, result.modifier());
        assertEquals(0, result.diceCount());
        assertEquals(0, result.diceSides());
        assertEquals(1, DiceRoller.rollCritical("1").total());
    }

    @Test
    @DisplayName("9.10 roll(\"1d4 - 3\") with a scripted 2: the dice show 2 and the total stops at 0")
    void scriptedNegativeRollStopsAtZero() {
        DiceRoller.RollResult result = DiceRoller.roll("1d4 - 3", Rng.scripted(2));
        assertEquals(2, result.diceTotal());
        assertEquals(-3, result.modifier());
        assertEquals(0, result.total());
    }

    @Test
    @DisplayName("9.11 rollCritical(\"1d8+3\") with scripted 2 and 7: two dice, one modifier, total 12")
    void scriptedCritical() {
        DiceRoller.RollResult result = DiceRoller.rollCritical("1d8+3", Rng.scripted(2, 7));
        assertEquals(9, result.diceTotal());
        assertEquals(3, result.modifier());
        assertEquals(12, result.total());
        assertEquals(2, result.diceCount());
        assertEquals(8, result.diceSides());
    }
}
