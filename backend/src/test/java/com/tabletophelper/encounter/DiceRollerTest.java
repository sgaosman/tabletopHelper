package com.tabletophelper.encounter;

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
    @DisplayName("9.3 roll(\"1d20-2\"): negative modifier syntax not supported, throws exception")
    void rollNegativeModifierNotSupported() {
        // DiceRoller regex only supports positive modifiers (+), not minus (-)
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll("1d20-2"));
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
    @DisplayName("9.8 Invalid expressions throw IllegalArgumentException")
    void invalidExpressionThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll("abc"),
                "Alphabetic-only input should throw");
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll("d6d8"),
                "Malformed expression should throw");
        assertThrows(IllegalArgumentException.class, () -> DiceRoller.roll(""),
                "Empty string should throw");
    }
}
