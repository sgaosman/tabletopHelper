package com.tabletophelper.rules;

import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiceTest {

    // ── Parsing ───────────────────────────────────────────────────

    // The monster data writes negative modifiers as "1d6 - 1", "1d6-1" and "1d8 -1", and flat damage as "1"
    @ParameterizedTest(name = "\"{0}\" → {1}d{2}, modifier {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # expression | count | sides | modifier
              1d6        | 1     | 6     | 0
              2d6+3      | 2     | 6     | 3
              2d6 + 3    | 2     | 6     | 3
              1d6 - 1    | 1     | 6     | -1
              1d6-1      | 1     | 6     | -1
              1d8 -1     | 1     | 8     | -1
              1d4 - 3    | 1     | 4     | -3
              1D20       | 1     | 20    | 0
              ' 3d4 + 2 '| 3     | 4     | 2
              100d100    | 100   | 100   | 0
              1          | 0     | 0     | 1
              60         | 0     | 0     | 60
            """)
    @DisplayName("Parses NdM, NdM + K and NdM - K, with or without spaces, and a flat K")
    void parses(String expression, int count, int sides, int modifier) {
        assertEquals(new Dice(count, sides, modifier), Dice.parse(expression));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "abc", "d6", "d6d8", "1d", "1d6 +", "1d6 + x", "+3", "-1", "1d6 * 2",
            "2d6+1d4", "1 d6", "1d6 + 1 + 1", "1234567890"})
    @DisplayName("Rejects anything else")
    void rejectsMalformedExpressions(String expression) {
        var e = assertThrows(ValidationException.class, () -> Dice.parse(expression));
        assertEquals("Invalid dice expression: " + expression, e.getMessage());
    }

    @Test
    @DisplayName("Rejects a missing expression")
    void rejectsNull() {
        assertThrows(ValidationException.class, () -> Dice.parse(null));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0d6", "101d6", "1d0", "1d101"})
    @DisplayName("Rejects fewer than 1 or more than 100 dice, and dice of fewer than 1 or more than 100 sides")
    void rejectsDiceOutOfRange(String expression) {
        var e = assertThrows(ValidationException.class, () -> Dice.parse(expression));
        assertEquals("Dice values out of range: " + expression, e.getMessage());
    }

    @Test
    @DisplayName("Dice need both a count and sides, and a flat amount neither")
    void constructorRefusesHalfDice() {
        assertThrows(IllegalArgumentException.class, () -> new Dice(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Dice(0, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> new Dice(-1, 6, 0));
    }

    @Test
    @DisplayName("toString writes every expression in one form")
    void writesOneForm() {
        assertEquals("2d6 + 3", Dice.parse("2d6+3").toString());
        assertEquals("1d6 - 1", Dice.parse("1d6-1").toString());
        assertEquals("1d8", Dice.parse("1D8").toString());
        assertEquals("1", Dice.parse("1").toString());
    }

    // ── Rolling ───────────────────────────────────────────────────

    @Test
    @DisplayName("roll adds the modifier to the dice, which come from the Rng")
    void rollAddsTheModifier() {
        assertEquals(12, Dice.parse("2d6 + 3").roll(Rng.scripted(4, 5)));
        assertEquals(2, Dice.parse("1d6 - 1").roll(Rng.scripted(3)));
    }

    @Test
    @DisplayName("A penalty can bring a roll to 0 but never below: damage is never negative (PHB p.196)")
    void rollIsNeverNegative() {
        assertEquals(0, Dice.parse("1d6 - 1").roll(Rng.scripted(1)));
        assertEquals(0, Dice.parse("1d4 - 3").roll(Rng.scripted(1)));
        assertEquals(1, Dice.parse("1d4 - 3").rollDice(Rng.scripted(1)), "the dice alone are not clamped");
    }

    @Test
    @DisplayName("A flat amount rolls no dice")
    void flatRollsNoDice() {
        var rng = Rng.scripted();
        assertEquals(1, Dice.parse("1").roll(rng));
        assertEquals(0, Dice.parse("1").rollDice(rng));
    }

    @Test
    @DisplayName("doubled doubles the dice but not the modifier, as a critical hit does (PHB p.196)")
    void doubledKeepsTheModifier() {
        assertEquals(new Dice(2, 8, 3), Dice.parse("1d8+3").doubled());
        assertEquals(new Dice(2, 6, -1), Dice.parse("1d6 - 1").doubled());
        assertEquals(new Dice(0, 0, 1), Dice.parse("1").doubled());
    }

    // ── Rng ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Rng.random rolls every face of a die and nothing outside it")
    void randomCoversEveryFace() {
        var rng = Rng.random();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int roll = rng.roll(6);
            assertTrue(roll >= 1 && roll <= 6, "A d6 rolled " + roll);
            seen.add(roll);
        }
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), seen);
    }

    @Test
    @DisplayName("Rng.scripted returns its rolls in order and counts what is left")
    void scriptedReturnsRollsInOrder() {
        var rng = Rng.scripted(3, 20, 1);
        assertEquals(3, rng.roll(6));
        assertEquals(20, rng.roll(20));
        assertEquals(1, rng.remaining());
    }

    @Test
    @DisplayName("Rng.scripted fails when its rolls run out or a roll can't come up on the die")
    void scriptedFailsOnSurprises() {
        assertThrows(IllegalStateException.class, () -> Rng.scripted().roll(20));
        assertThrows(IllegalStateException.class, () -> Rng.scripted(7).roll(6));
        assertThrows(IllegalStateException.class, () -> Rng.scripted(0).roll(6));
    }
}
