package com.tabletophelper.rules;

import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionTest {

    private static int eval(String formula) {
        return Expression.evaluate(formula, Map.of());
    }

    // ── Numbers and names ─────────────────────────────────────────

    @Test
    @DisplayName("A number is itself, and a name is the context's value for it")
    void numbersAndNames() {
        assertEquals(5, eval("5"));
        assertEquals(3, Expression.evaluate("monkLevel", Map.of("monkLevel", 3)));
        assertEquals(-1, Expression.evaluate("charismaModifier", Map.of("charismaModifier", -1)));
        assertEquals(8, Expression.evaluate("proficiencyBonus + totalLevel",
                Map.of("proficiencyBonus", 3, "totalLevel", 5)));
    }

    @Test
    @DisplayName("A name is read whole: monkLevel isn't 'Level' after 'monk', and a longer name doesn't take a shorter one's value")
    void namesAreReadWhole() {
        Map<String, Integer> context = Map.of("monkLevel", 3, "level", 10);
        assertEquals(13, Expression.evaluate("monkLevel + level", context));
    }

    // ── Arithmetic and precedence ─────────────────────────────────

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '=', value = {
            "2 + 3 = 5", "10 - 3 = 7", "4 * 3 = 12", "10 / 2 = 5",
            "2 + 3 * 4 = 14", "10 - 2 * 3 = 4", "2 * 3 + 4 = 10", "10 - 4 - 3 = 3", "24 / 4 / 3 = 2",
            "(2 + 3) * 4 = 20", "((1 + 1) * (2 + 1)) = 6", "2 * (10 - (3 + 1)) = 12"})
    @DisplayName("Multiplication and division bind tighter than addition and subtraction, operators of a rank go left to right, and brackets group")
    void precedenceAndBrackets(String formula, int expected) {
        assertEquals(expected, eval(formula.trim()));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '=', value = {"-3 + 5 = 2", "2 * -3 = -6", "5 - -2 = 7", "--4 = 4", "-(2 + 3) = -5",
            "+4 = 4"})
    @DisplayName("A minus sign before a number, a name or a bracket negates it, wherever it comes")
    void unaryMinus(String formula, int expected) {
        assertEquals(expected, eval(formula.trim()));
    }

    @Test
    @DisplayName("A negative value from the context stays negative after an operator: 2 * wisdomModifier at -1 is -2")
    void negativeContextValues() {
        assertEquals(-2, Expression.evaluate("2 * wisdomModifier", Map.of("wisdomModifier", -1)));
        assertEquals(0, Expression.evaluate("1 + charismaModifier", Map.of("charismaModifier", -1)));
        assertEquals(4, Expression.evaluate("5*paladinLevel - 1", Map.of("paladinLevel", 1)));
    }

    // ── Division and rounding ─────────────────────────────────────

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '=', value = {"10 / 3 = 3", "7 / 2 = 3", "-7 / 2 = -4", "1 / 3 * 3 = 1",
            "5 / 2 + 5 / 2 = 5", "ceil(5 / 2) = 3", "floor(7 / 2) = 3", "ceil(-5 / 2) = -2", "floor(-5 / 2) = -3",
            "ceil(15 / 7) = 3", "ceil(14 / 7) = 2"})
    @DisplayName("Division is exact, so fractions add up, and the result is rounded down; ceil and floor round the true quotient")
    void divisionIsExactAndRoundsDown(String formula, int expected) {
        assertEquals(expected, eval(formula.trim()));
    }

    @Test
    @DisplayName("Inside ceil, division binds tighter too: ceil(fighterLevel + 1 / 7) at 14 is ceil(14.14) = 15")
    void precedenceInsideCeil() {
        assertEquals(15, Expression.evaluate("ceil(fighterLevel + 1 / 7)", Map.of("fighterLevel", 14)));
        assertEquals(2, Expression.evaluate("floor(maxUses/2)", Map.of("maxUses", 5)));
        assertEquals(4, Expression.evaluate("ceil(level/2)", Map.of("level", 7)));
    }

    @Test
    @DisplayName("Dividing by zero is an error, not 0")
    void divisionByZero() {
        assertThrows(ExpressionException.class, () -> eval("10 / 0"));
        assertThrows(ExpressionException.class, () -> Expression.evaluate("4 / druidLevel", Map.of("druidLevel", 0)));
    }

    // ── Functions ─────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '=', value = {"max(1, -1) = 1", "max(1, 3) = 3", "min(4, 2) = 2", "max(0, 1 + 2) = 3",
            "min(5, 2, 9) = 2", "max(1, 2, 3) = 3", "max(1, min(5, 4)) = 4", "2 * max(1, 2) + 1 = 5",
            "min(1 / 2, 1) = 0"})
    @DisplayName("min and max give the smallest and largest of two or more arguments, and nest")
    void minAndMax(String formula, int expected) {
        assertEquals(expected, eval(formula.trim()));
    }

    @Test
    @DisplayName("At least once: max(1, wisdomModifier) is 1 at -1 and 0, and the modifier above")
    void atLeastOnce() {
        assertEquals(1, Expression.evaluate("max(1, wisdomModifier)", Map.of("wisdomModifier", -1)));
        assertEquals(1, Expression.evaluate("max(1, wisdomModifier)", Map.of("wisdomModifier", 0)));
        assertEquals(3, Expression.evaluate("max(1, wisdomModifier)", Map.of("wisdomModifier", 3)));
        assertEquals(0, Expression.evaluate("max(0, 1+charismaModifier)", Map.of("charismaModifier", -1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max(1)", "min(2)", "floor(1, 2)", "ceil()", "round(2)", "abs(-1)"})
    @DisplayName("A function with the wrong number of arguments, or one that isn't min, max, floor or ceil, is an error")
    void badFunctions(String formula) {
        assertThrows(ExpressionException.class, () -> eval(formula));
    }

    // ── Dice ──────────────────────────────────────────────────────

    @Test
    @DisplayName("NdM rolls N dice of M sides and dM one die, and they take part in arithmetic")
    void dice() {
        assertEquals(8, Expression.evaluate("2d6+1", Map.of(), Rng.scripted(3, 4)));
        assertEquals(17, Expression.evaluate("d20 + proficiencyBonus", Map.of("proficiencyBonus", 2),
                Rng.scripted(15)));
        assertEquals(10, Expression.evaluate("2 * 1d6", Map.of(), Rng.scripted(5)));
        assertEquals(2, Expression.evaluate("floor(1d8 / 2)", Map.of(), Rng.scripted(5)));
    }

    @Test
    @DisplayName("Dice are rolled each time the formula is worked out")
    void diceRollEachTime() {
        Expression formula = Expression.parse("1d6");
        Rng.Scripted rng = Rng.scripted(2, 6);
        assertEquals(2, formula.evaluate(Map.of(), rng));
        assertEquals(6, formula.evaluate(Map.of(), rng));
    }

    @Test
    @DisplayName("A name that starts with d is a name, not a die: druidLevel and d6x")
    void namesStartingWithD() {
        assertEquals(4, Expression.evaluate("druidLevel", Map.of("druidLevel", 4)));
        assertEquals(1, Expression.evaluate("d6x", Map.of("d6x", 1)));
    }

    @Test
    @DisplayName("A recharge check rolls its dice and succeeds when they reach the threshold, reporting the roll")
    void rechargeCheck() {
        assertEquals(new Expression.RechargeResult(true, 5), Expression.rechargeCheck("1d6>=5", Map.of(),
                Rng.scripted(5)));
        assertEquals(new Expression.RechargeResult(true, 6), Expression.rechargeCheck("1d6>=5", Map.of(),
                Rng.scripted(6)));
        assertEquals(new Expression.RechargeResult(false, 4), Expression.rechargeCheck("1d6>=5", Map.of(),
                Rng.scripted(4)));
        assertEquals(new Expression.RechargeResult(false, 5), Expression.rechargeCheck("1d6 >= 6", Map.of(),
                Rng.scripted(5)));
        assertEquals(new Expression.RechargeResult(true, 7), Expression.rechargeCheck("2d6>=7", Map.of(),
                Rng.scripted(3, 4)));
    }

    @Test
    @DisplayName("A random recharge check of 1d6>=5 succeeds about a third of the time")
    void rechargeCheckIsRandom() {
        int successes = 0;
        for (int i = 0; i < 3000; i++) {
            if (Expression.rechargeCheck("1d6>=5").success()) successes++;
        }
        assertTrue(successes > 800 && successes < 1200, "successes: " + successes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "1d6", "1d6 + 1 >= 5", "3 >= 1", "not-dice"})
    @DisplayName("A recharge check that isn't dice compared with a threshold is an error")
    void badRechargeChecks(String expression) {
        assertThrows(ExpressionException.class, () -> Expression.rechargeCheck(expression, Map.of(), sides -> 1));
    }

    @Test
    @DisplayName("In a formula, a comparison gives 1 or 0")
    void comparisonInAFormula() {
        assertEquals(1, eval("3 >= 2"));
        assertEquals(0, eval("1 >= 2"));
    }

    // ── Errors ────────────────────────────────────────────────────

    @Test
    @DisplayName("A name the context doesn't have is an error naming it, which answers 409")
    void unknownName() {
        ExpressionException error = assertThrows(ExpressionException.class, () -> eval("unknownVar + 1"));
        assertTrue(error.getMessage().contains("unknownVar"), error.getMessage());
        assertInstanceOf(ConflictException.class, error);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "2 +", "(2 + 3", "2 + 3)", "2 3", "2 ** 3", "max(1, 2", "3 >= ", "1.5",
            "2 $ 3", "d", "2d", "1d0"})
    @DisplayName("Bad syntax is an error, never 0")
    void badSyntax(String formula) {
        assertThrows(ExpressionException.class, () -> eval(formula));
    }

    @Test
    @DisplayName("A null formula is an error")
    void nullFormula() {
        assertThrows(ExpressionException.class, () -> Expression.parse(null));
    }

    // ── Validation ────────────────────────────────────────────────

    @Test
    @DisplayName("validate works a formula out with every name at 0, so a known name passes and an unknown one fails")
    void validate() {
        List<String> names = List.of("proficiencyBonus", "charismaModifier", "paladinLevel", "maxUses");
        assertDoesNotThrow(() -> Expression.validate("max(0, 1+charismaModifier)", names));
        assertDoesNotThrow(() -> Expression.validate("5*paladinLevel", names));
        assertDoesNotThrow(() -> Expression.validate("floor(maxUses/2) + 1d6", names));
        assertThrows(ExpressionException.class, () -> Expression.validate("5*paladinLvl", names));
        assertThrows(ExpressionException.class, () -> Expression.validate("2 +", names));
        assertDoesNotThrow(() -> Expression.validateRechargeCheck("1d6>=5", names));
        assertThrows(ExpressionException.class, () -> Expression.validateRechargeCheck("1d6", names));
    }
}
