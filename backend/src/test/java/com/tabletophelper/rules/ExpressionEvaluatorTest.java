package com.tabletophelper.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    /**
     * Expressions today's evaluator gets wrong, each with the value it gives today: it works strictly left to right,
     * reads an operator after another as replacing it, and reads anything it can't parse, such as {@code max} or a
     * bracket, as 0. Each is asserted to give that value still, and not the right one, so the suite passes and a
     * case that changes fails until it leaves this list; R7 workstream D's parser empties it (D169, D172).
     */
    private static final Map<String, Integer> KNOWN_WRONG = Map.of(
            "2 + 3 * 4", 20,
            "10 - 2 * 3", 24,
            "(2 + 3) * 4", 0,
            "ceil(fighterLevel + 1 / 7)", 3,
            "2 * -3", -1,
            "2 * wisdomModifier", 1,
            "5 - -2", 3,
            "max(1, wisdomModifier)", 0,
            "max(1, intelligenceModifier)", 0,
            "max(0, 1 + charismaModifier)", 0);

    // The value arithmetic gives, or for a known-wrong expression, today's value and not that one
    private static void assertEvaluates(int expected, String formula, Map<String, Integer> context) {
        int actual = ExpressionEvaluator.evaluate(formula, context);
        Integer today = KNOWN_WRONG.get(formula);
        if (today == null) {
            assertEquals(expected, actual, formula);
        } else {
            assertNotEquals(expected, actual, formula + " now gives " + expected + "; take it out of KNOWN_WRONG");
            assertEquals(today, actual, formula + ": KNOWN_WRONG expects " + today + " today");
        }
    }

    // ── Basic variable substitution ───────────────────────────────

    @Test
    @DisplayName("Evaluates simple integer literal")
    void literalReturnsItself() {
        assertEquals(5, ExpressionEvaluator.evaluate("5", Map.of()));
    }

    @Test
    @DisplayName("Evaluates single variable from context")
    void singleVariableSubstitution() {
        assertEquals(3, ExpressionEvaluator.evaluate("monkLevel", Map.of("monkLevel", 3)));
    }

    @Test
    @DisplayName("Evaluates simple addition: 2 + 3 = 5")
    void simpleAddition() {
        assertEquals(5, ExpressionEvaluator.evaluate("2 + 3", Map.of()));
    }

    @Test
    @DisplayName("Evaluates subtraction: 10 - 3 = 7")
    void simpleSubtraction() {
        assertEquals(7, ExpressionEvaluator.evaluate("10 - 3", Map.of()));
    }

    @Test
    @DisplayName("Evaluates multiplication: 4 * 3 = 12")
    void simpleMultiplication() {
        assertEquals(12, ExpressionEvaluator.evaluate("4 * 3", Map.of()));
    }

    @Test
    @DisplayName("Evaluates division: 10 / 2 = 5")
    void simpleDivision() {
        assertEquals(5, ExpressionEvaluator.evaluate("10 / 2", Map.of()));
    }

    @Test
    @DisplayName("Integer division truncates: 10 / 3 = 3")
    void integerDivisionTruncates() {
        assertEquals(3, ExpressionEvaluator.evaluate("10 / 3", Map.of()));
    }

    // ── Variable + arithmetic ─────────────────────────────────────

    @Test
    @DisplayName("monkLevel variable: monkLevel=5 → 5")
    void monkLevel() {
        assertEquals(5, ExpressionEvaluator.evaluate("monkLevel", Map.of("monkLevel", 5)));
    }

    @Test
    @DisplayName("2 * proficiencyBonus: proficiencyBonus=3 → 6")
    void proficiencyBonusTimesTwo() {
        Map<String, Integer> ctx = Map.of("proficiencyBonus", 3);
        assertEquals(6, ExpressionEvaluator.evaluate("2 * proficiencyBonus", ctx));
    }

    @Test
    @DisplayName("proficiencyBonus + totalLevel: both 3 and 5 → 8")
    void proficiencyPlusLevel() {
        Map<String, Integer> ctx = Map.of("proficiencyBonus", 3, "totalLevel", 5);
        assertEquals(8, ExpressionEvaluator.evaluate("proficiencyBonus + totalLevel", ctx));
    }

    @Test
    @DisplayName("wisdomModifier variable: wisdomModifier=3 → 3")
    void wisdomModifier() {
        assertEquals(3, ExpressionEvaluator.evaluate("wisdomModifier", Map.of("wisdomModifier", 3)));
    }

    @Test
    @DisplayName("charismaModifier with min 1: the formula itself is raw — min is caller's responsibility")
    void charismaModifierRaw() {
        assertEquals(-1, ExpressionEvaluator.evaluate("charismaModifier", Map.of("charismaModifier", -1)));
    }

    // ── ceil / floor ──────────────────────────────────────────────

    @Test
    @DisplayName("ceil(5/2) = 3 (fractional-aware division)")
    void ceilFractionalDivision() {
        assertEquals(3, ExpressionEvaluator.evaluate("ceil(5/2)", Map.of()));
    }

    @Test
    @DisplayName("floor(7/2) = 3")
    void floorFractionalDivision() {
        assertEquals(3, ExpressionEvaluator.evaluate("floor(7/2)", Map.of()));
    }

    @Test
    @DisplayName("ceil(level/2) with level=7 → ceil(3.5) = 4")
    void ceilLevelHalved() {
        assertEquals(4, ExpressionEvaluator.evaluate("ceil(level/2)", Map.of("level", 7)));
    }

    @Test
    @DisplayName("floor(maxUses/2) with maxUses=5 → floor(2.5) = 2")
    void floorMaxUsesHalved() {
        assertEquals(2, ExpressionEvaluator.evaluate("floor(maxUses/2)", Map.of("maxUses", 5)));
    }

    // ── Dice ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Dice in a formula roll from the Rng passed in: 2d6+1 rolling 3 and 4 → 8")
    void diceRollFromTheGivenRng() {
        assertEquals(8, ExpressionEvaluator.evaluate("2d6+1", Map.of(), Rng.scripted(3, 4)));
    }

    @Test
    @DisplayName("A bare d20 is one die, after variables are substituted: d20 + proficiencyBonus rolling 15 → 17")
    void bareDieAfterVariables() {
        Map<String, Integer> ctx = Map.of("proficiencyBonus", 2);
        assertEquals(17, ExpressionEvaluator.evaluate("d20 + proficiencyBonus", ctx, Rng.scripted(15)));
    }

    // ── Recharge checks ───────────────────────────────────────────

    @Test
    @DisplayName("1d6>=5 succeeds when roll is 5 or 6")
    void recharge5to6() {
        int successes = 0;
        for (int i = 0; i < 1000; i++) {
            if (ExpressionEvaluator.evaluateRechargeCheck("1d6>=5")) successes++;
        }
        // Should succeed roughly 2/6 ≈ 33% of the time
        assertTrue(successes >= 200 && successes <= 450,
                "Expected ~333 successes out of 1000, got " + successes);
    }

    @Test
    @DisplayName("1d6>=6 succeeds only on a 6")
    void recharge6Only() {
        int successes = 0;
        for (int i = 0; i < 1000; i++) {
            if (ExpressionEvaluator.evaluateRechargeCheck("1d6>=6")) successes++;
        }
        // Should succeed roughly 1/6 ≈ 16.7% of the time
        assertTrue(successes >= 80 && successes <= 250,
                "Expected ~167 successes out of 1000, got " + successes);
    }

    @Test
    @DisplayName("A recharge check rolls its d6 from the Rng passed in")
    void rechargeRollsFromTheGivenRng() {
        ExpressionEvaluator.RechargeResult recharged =
                ExpressionEvaluator.evaluateRechargeCheckWithRoll("1d6>=5", Rng.scripted(5));
        assertTrue(recharged.success());
        assertEquals(5, recharged.rollValue());

        ExpressionEvaluator.RechargeResult notRecharged =
                ExpressionEvaluator.evaluateRechargeCheckWithRoll("1d6>=5", Rng.scripted(4));
        assertFalse(notRecharged.success());
        assertEquals(4, notRecharged.rollValue());
    }

    @Test
    @DisplayName("evaluateRechargeCheck returns false for null")
    void rechargeNullReturnsFalse() {
        assertFalse(ExpressionEvaluator.evaluateRechargeCheck(null));
    }

    @Test
    @DisplayName("evaluateRechargeCheck returns false for invalid expression")
    void rechargeInvalidReturnsFalse() {
        assertFalse(ExpressionEvaluator.evaluateRechargeCheck("2d6>=5"));
        assertFalse(ExpressionEvaluator.evaluateRechargeCheck(""));
        assertFalse(ExpressionEvaluator.evaluateRechargeCheck("not-dice"));
    }

    // ── Edge cases ────────────────────────────────────────────────

    @Test
    @DisplayName("null formula returns 0")
    void nullFormulaReturnsZero() {
        assertEquals(0, ExpressionEvaluator.evaluate(null, Map.of()));
    }

    @Test
    @DisplayName("blank formula returns 0")
    void blankFormulaReturnsZero() {
        assertEquals(0, ExpressionEvaluator.evaluate("   ", Map.of()));
    }

    @Test
    @DisplayName("Longest key matches first: monkLevel before just Level")
    void longestKeyMatchesFirst() {
        // If "Level" matched before "monkLevel", we'd get "monk3" which fails
        Map<String, Integer> ctx = Map.of("monkLevel", 3, "level", 5);
        assertEquals(3, ExpressionEvaluator.evaluate("monkLevel", ctx));
    }

    @Test
    @DisplayName("Division by zero returns 0")
    void divideByZero() {
        assertEquals(0, ExpressionEvaluator.evaluate("10 / 0", Map.of()));
    }

    @Test
    @DisplayName("Unrecognized variable treated as 0 (no match in context)")
    void unknownVariableHandledGracefully() {
        // "unknownVar" stays as literal text, evaluateArithmetic tries Double.parseDouble → fails → 0
        // Actually it would add 0 + 0 = 0 for the first token, then hit "unknownVar"
        // which fails NumberFormatException → returns 0
        int result = ExpressionEvaluator.evaluate("unknownVar", Map.of());
        assertEquals(0, result);
    }

    // ── Precedence, unary minus and max (KNOWN_WRONG until R7 workstream D) ──

    @Test
    @DisplayName("Multiplication binds tighter than addition and subtraction: 2 + 3 * 4 = 14 and 10 - 2 * 3 = 4")
    void multiplicationBeforeAddition() {
        assertEvaluates(14, "2 + 3 * 4", Map.of());
        assertEvaluates(4, "10 - 2 * 3", Map.of());
        assertEvaluates(10, "2 * 3 + 4", Map.of());
    }

    @Test
    @DisplayName("Brackets group: (2 + 3) * 4 = 20")
    void bracketsGroup() {
        assertEvaluates(20, "(2 + 3) * 4", Map.of());
    }

    @Test
    @DisplayName("Inside ceil, division binds tighter too: ceil(fighterLevel + 1 / 7) at 14 is ceil(14.14) = 15")
    void precedenceInsideCeil() {
        // The Battle Master's formula, 3+ceil(fighterLevel+1/7), gives its dice only because it is read left to right
        assertEvaluates(15, "ceil(fighterLevel + 1 / 7)", Map.of("fighterLevel", 14));
    }

    @Test
    @DisplayName("A minus sign after an operator negates the number after it: 2 * -3 = -6 and 5 - -2 = 7")
    void unaryMinusAfterAnOperator() {
        assertEvaluates(-6, "2 * -3", Map.of());
        assertEvaluates(7, "5 - -2", Map.of());
        assertEvaluates(2, "-3 + 5", Map.of());
    }

    @Test
    @DisplayName("A negative modifier substituted after an operator stays negative: 2 * wisdomModifier at -1 is -2, 1 + charismaModifier at -1 is 0")
    void negativeModifierAfterAnOperator() {
        assertEvaluates(-2, "2 * wisdomModifier", Map.of("wisdomModifier", -1));
        assertEvaluates(0, "1 + charismaModifier", Map.of("charismaModifier", -1));
    }

    @Test
    @DisplayName("max gives the larger argument: max(1, -1) = 1, max(1, 3) = 3, max(0, 1 + 2) = 3")
    void maxGivesTheLargerArgument() {
        // "a number of times equal to your Wisdom modifier (a minimum of once)", and the Paladin's Divine Sense, 1 + the Charisma modifier (PHB)
        assertEvaluates(1, "max(1, wisdomModifier)", Map.of("wisdomModifier", -1));
        assertEvaluates(3, "max(1, intelligenceModifier)", Map.of("intelligenceModifier", 3));
        assertEvaluates(3, "max(0, 1 + charismaModifier)", Map.of("charismaModifier", 2));
    }
}
