package com.tabletophelper.resourcepool;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight expression evaluator for resource pool formulas.
 * Supports variable substitution, basic arithmetic, ceil/floor,
 * dice-roll expressions ({@code 2d6+1}, {@code d20}),
 * and probabilistic recharge checks ("1d6>=N").
 *
 * <p>Variables are resolved from a context map. Supported keys:
 * {@code proficiencyBonus}, {@code totalLevel},
 * {@code {className}Level} (e.g. monkLevel),
 * {@code {ability}Modifier} (e.g. wisdomModifier),
 * and {@code maxUses} for self-referencing resetAmount expressions.</p>
 *
 * <p>Integer division truncates toward zero. Use {@code ceil(a/b)} or
 * {@code floor(a/b)} when fractional-aware division is needed.</p>
 *
 * <p>Dice notation ({@code NdM±C}) is expanded to the sum of N rolls
 * of a dM plus an optional constant. A bare {@code dM} defaults to 1 die.
 * Dice rolls use {@link ThreadLocalRandom} and are evaluated after
 * variable substitution but before integer arithmetic.</p>
 */
public final class ExpressionEvaluator {

    private static final Pattern RECHARGE_PATTERN =
            Pattern.compile("^1d6>=([1-6])$");
    private static final Pattern FUNCTION_PATTERN =
            Pattern.compile("(ceil|floor)\\s*\\(\\s*([^)]+)\\s*\\)");
    private static final Pattern DICE_PATTERN =
            Pattern.compile("(\\d*)d(\\d+)(?:([+-])(\\d+))?");

    private ExpressionEvaluator() {}

    /**
     * Evaluates a formula string against a variable context.
     *
     * @param formula the expression to evaluate (e.g. "2 * proficiencyBonus")
     * @param context variable name → value mappings
     * @return the evaluated integer, or 0 if the formula is blank
     */
    public static int evaluate(String formula, Map<String, Integer> context) {
        if (formula == null || formula.isBlank()) {
            return 0;
        }
        String expr = formula.trim();

        // Resolve ceil/floor functions first (uses double arithmetic internally)
        expr = resolveFunctions(expr, context);

        // Substitute remaining variables
        expr = substituteVariables(expr, context);

        // Roll any dice expressions (e.g. 1d6+1) before integer arithmetic
        expr = resolveDice(expr);

        // Evaluate remaining integer arithmetic
        return evaluateArithmetic(expr);
    }

    /**
     * Rolls a d6 and checks if the result &gt;= threshold.
     *
     * @param rechargeExpression e.g. "1d6>=5"
     * @return true if the recharge succeeds
     */
    public static boolean evaluateRechargeCheck(String rechargeExpression) {
        if (rechargeExpression == null) return false;
        Matcher m = RECHARGE_PATTERN.matcher(rechargeExpression.trim());
        if (!m.matches()) return false;
        int threshold = Integer.parseInt(m.group(1));
        int roll = ThreadLocalRandom.current().nextInt(1, 7);
        return roll >= threshold;
    }

    // ---- private helpers ----

    /**
     * Finds all dice-roll expressions ({@code NdM}, {@code NdM+P}, {@code NdM-P})
     * and replaces them with their rolled totals. Dice with no leading count
     * (e.g. {@code d20}) default to 1 die.
     *
     * <p>Runs after variable substitution so context variables like
     * {@code maxUses} have already been replaced with their integer values.</p>
     */
    static String resolveDice(String expr) {
        Matcher m = DICE_PATTERN.matcher(expr);
        if (!m.find()) return expr;
        m.reset(); // rewind — find() consumed the first match

        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
            int faces = Integer.parseInt(m.group(2));
            int modifier = 0;
            if (m.group(3) != null) {
                modifier = Integer.parseInt(m.group(4));
                if ("-".equals(m.group(3))) modifier = -modifier;
            }
            int total = modifier;
            for (int i = 0; i < count; i++) {
                total += ThreadLocalRandom.current().nextInt(1, faces + 1);
            }
            m.appendReplacement(sb, String.valueOf(total));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveFunctions(String expr, Map<String, Integer> context) {
        Matcher m;
        // Process innermost function calls first by repeatedly matching
        while ((m = FUNCTION_PATTERN.matcher(expr)).find()) {
            String func = m.group(1);
            String inner = m.group(2).trim();

            // Substitute variables in the inner expression, then evaluate
            // with double arithmetic so division produces fractional results
            String innerSubbed = substituteVariables(inner, context);
            innerSubbed = resolveDice(innerSubbed);
            double innerVal = evaluateDoubleArithmetic(innerSubbed);
            int result = "ceil".equals(func)
                    ? (int) Math.ceil(innerVal)
                    : (int) Math.floor(innerVal);

            expr = expr.substring(0, m.start()) + result + expr.substring(m.end());
        }
        return expr;
    }

    private static String substituteVariables(String expr, Map<String, Integer> context) {
        // Sort keys longest-first so "monkLevel" matches before "Level"
        String[] keys = context.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toArray(String[]::new);

        for (String key : keys) {
            expr = expr.replace(key, String.valueOf(context.get(key)));
        }
        return expr;
    }

    /** Integer arithmetic, left-to-right. Truncates division. */
    static int evaluateArithmetic(String expr) {
        expr = expr.replaceAll("\\s+", "");
        if (expr.isEmpty()) return 0;

        String[] parts = expr.split("(?=[+\\-*/])|(?<=[+\\-*/])");
        int result = 0;
        String pendingOp = "+";

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.equals("+") || part.equals("-") || part.equals("*") || part.equals("/")) {
                pendingOp = part;
            } else {
                int value;
                try {
                    value = (int) Double.parseDouble(part);
                } catch (NumberFormatException e) {
                    return 0;
                }
                result = applyIntOp(result, value, pendingOp);
            }
        }
        return result;
    }

    /** Double arithmetic, left-to-right. Used inside ceil/floor for precise division. */
    private static double evaluateDoubleArithmetic(String expr) {
        expr = expr.replaceAll("\\s+", "");
        if (expr.isEmpty()) return 0.0;

        String[] parts = expr.split("(?=[+\\-*/])|(?<=[+\\-*/])");
        double result = 0.0;
        String pendingOp = "+";

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.equals("+") || part.equals("-") || part.equals("*") || part.equals("/")) {
                pendingOp = part;
            } else {
                double value;
                try {
                    value = Double.parseDouble(part);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
                result = applyDoubleOp(result, value, pendingOp);
            }
        }
        return result;
    }

    private static int applyIntOp(int left, int right, String op) {
        return switch (op) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> right != 0 ? left / right : 0;
            default -> left;
        };
    }

    private static double applyDoubleOp(double left, double right, String op) {
        return switch (op) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> right != 0.0 ? left / right : 0.0;
            default -> left;
        };
    }
}
