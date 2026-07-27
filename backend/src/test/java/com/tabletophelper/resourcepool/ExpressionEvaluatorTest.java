package com.tabletophelper.resourcepool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

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
}
