package com.tabletophelper.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The formulas resource pools are sized and recharged by (D169), read by a recursive-descent parser:
 *
 * <pre>
 * formula    = comparison
 * comparison = sum [ "&gt;=" sum ]
 * sum        = product { ("+" | "-") product }
 * product    = unary { ("*" | "/") unary }
 * unary      = ("-" | "+") unary | primary
 * primary    = number | dice | name | function "(" sum { "," sum } ")" | "(" sum ")"
 * dice       = [ number ] "d" number          e.g. 2d6, d20
 * function   = "min" | "max"  (two or more arguments)  | "floor" | "ceil"  (one argument)
 * </pre>
 *
 * <p>Multiplication and division bind tighter than addition and subtraction, and operators of the same rank go left
 * to right. Arithmetic is exact: division keeps its fraction, so {@code ceil(fighterLevel / 7)} rounds the true
 * quotient, and the formula's result is rounded down at the end. A name is a variable the context gives, such as
 * {@code proficiencyBonus}, {@code totalLevel}, {@code monkLevel}, {@code wisdomModifier} or, in a reset amount,
 * {@code maxUses}. A name the context doesn't have, a function that isn't one of the four, bad syntax or a division
 * by zero is an {@link ExpressionException}, which answers 409.</p>
 *
 * <p>Dice ({@code NdM}) roll N dice of M sides from an {@link Rng} each time the formula is worked out; a bare
 * {@code dM} is one die. A comparison {@code NdM>=X} is a recharge check (MM p.11): {@link #rechargeCheck} rolls the
 * dice and reports the roll and whether it reached X. In {@link #evaluate} a comparison gives 1 or 0.</p>
 */
public final class Expression {

    private final String source;
    private final Node root;

    private Expression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /**
     * Reads a formula.
     *
     * @throws ExpressionException for a blank formula or bad syntax
     */
    public static Expression parse(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new ExpressionException("A formula is empty");
        }
        return new Parser(formula).parseFormula();
    }

    /** Works a formula out against the context, rolling any dice at random. */
    public static int evaluate(String formula, Map<String, Integer> context) {
        return parse(formula).evaluate(context, Rng.random());
    }

    /** Works a formula out against the context, rolling any dice with the given Rng. */
    public static int evaluate(String formula, Map<String, Integer> context, Rng rng) {
        return parse(formula).evaluate(context, rng);
    }

    /**
     * Checks that a formula reads and can be worked out with every one of the given names at 0, as the loaders do
     * with the data's formulas.
     *
     * @throws ExpressionException when it can't
     */
    public static void validate(String formula, Collection<String> names) {
        parse(formula).evaluate(zeros(names), sides -> 1);
    }

    /**
     * Checks that a recharge check reads, compares dice with a threshold, and can be worked out with every one of the
     * given names at 0.
     *
     * @throws ExpressionException when it can't
     */
    public static void validateRechargeCheck(String expression, Collection<String> names) {
        rechargeCheck(expression, zeros(names), sides -> 1);
    }

    /** The result of a recharge check: whether it succeeded, and the dice's roll, which the combat log shows. */
    public record RechargeResult(boolean success, int rollValue) {}

    /** Rolls a recharge check such as {@code 1d6>=5} with random dice. */
    public static RechargeResult rechargeCheck(String expression) {
        return rechargeCheck(expression, Map.of(), Rng.random());
    }

    /**
     * Rolls a recharge check, {@code NdM>=X}: the dice, and whether their total reaches X.
     *
     * @throws ExpressionException when the expression isn't dice compared with a threshold
     */
    public static RechargeResult rechargeCheck(String expression, Map<String, Integer> context, Rng rng) {
        Expression parsed = parse(expression);
        if (!(parsed.root instanceof Compare compare) || !(compare.left() instanceof Dice)) {
            throw new ExpressionException("The recharge check '" + expression + "' isn't dice compared with a"
                    + " number, such as 1d6>=5");
        }
        Scope scope = new Scope(parsed.source, context, rng);
        int roll = compare.left().value(scope).floor();
        int threshold = compare.right().value(scope).floor();
        return new RechargeResult(roll >= threshold, roll);
    }

    /** Works the formula out against the context, rolling any dice at random. */
    public int evaluate(Map<String, Integer> context) {
        return evaluate(context, Rng.random());
    }

    /**
     * Works the formula out against the context, rolling any dice with the given Rng, and rounds the result down.
     *
     * @throws ExpressionException for a name the context doesn't have or a division by zero
     */
    public int evaluate(Map<String, Integer> context, Rng rng) {
        return root.value(new Scope(source, context, rng)).floor();
    }

    @Override
    public String toString() {
        return source;
    }

    private static Map<String, Integer> zeros(Collection<String> names) {
        Map<String, Integer> context = new LinkedHashMap<>();
        names.forEach(name -> context.put(name, 0));
        return context;
    }

    // ── Values: exact fractions ───────────────────────────────────

    private record Fraction(long numerator, long denominator) {

        static Fraction of(long value) {
            return new Fraction(value, 1);
        }

        static Fraction reduced(long numerator, long denominator) {
            if (denominator < 0) {
                numerator = Math.negateExact(numerator);
                denominator = Math.negateExact(denominator);
            }
            long gcd = gcd(Math.abs(numerator), denominator);
            return new Fraction(numerator / gcd, denominator / gcd);
        }

        private static long gcd(long a, long b) {
            while (b != 0) {
                long t = a % b;
                a = b;
                b = t;
            }
            return a == 0 ? 1 : a;
        }

        Fraction plus(Fraction other) {
            return reduced(Math.addExact(Math.multiplyExact(numerator, other.denominator),
                    Math.multiplyExact(other.numerator, denominator)), Math.multiplyExact(denominator, other.denominator));
        }

        Fraction negate() {
            return new Fraction(Math.negateExact(numerator), denominator);
        }

        Fraction times(Fraction other) {
            return reduced(Math.multiplyExact(numerator, other.numerator),
                    Math.multiplyExact(denominator, other.denominator));
        }

        Fraction dividedBy(Fraction other) {
            return reduced(Math.multiplyExact(numerator, other.denominator),
                    Math.multiplyExact(denominator, other.numerator));
        }

        int compareTo(Fraction other) {
            return Long.compare(Math.multiplyExact(numerator, other.denominator),
                    Math.multiplyExact(other.numerator, denominator));
        }

        int floor() {
            return Math.toIntExact(Math.floorDiv(numerator, denominator));
        }

        int ceil() {
            return Math.toIntExact(-Math.floorDiv(-numerator, denominator));
        }
    }

    // ── The tree ──────────────────────────────────────────────────

    private record Scope(String source, Map<String, Integer> context, Rng rng) {
        ExpressionException error(String problem) {
            return new ExpressionException("The formula '" + source + "' " + problem);
        }
    }

    private interface Node {
        Fraction value(Scope scope);
    }

    private record Literal(long value) implements Node {
        public Fraction value(Scope scope) {
            return Fraction.of(value);
        }
    }

    private record Name(String name) implements Node {
        public Fraction value(Scope scope) {
            Integer value = scope.context().get(name);
            if (value == null) {
                throw scope.error("names '" + name + "', which isn't known here");
            }
            return Fraction.of(value);
        }
    }

    private record Dice(int count, int sides) implements Node {
        public Fraction value(Scope scope) {
            long total = 0;
            for (int i = 0; i < count; i++) {
                total += scope.rng().roll(sides);
            }
            return Fraction.of(total);
        }
    }

    private record Negate(Node operand) implements Node {
        public Fraction value(Scope scope) {
            return operand.value(scope).negate();
        }
    }

    private record Binary(char operator, Node left, Node right) implements Node {
        public Fraction value(Scope scope) {
            Fraction a = left.value(scope);
            Fraction b = right.value(scope);
            try {
                return switch (operator) {
                    case '+' -> a.plus(b);
                    case '-' -> a.plus(b.negate());
                    case '*' -> a.times(b);
                    default -> {
                        if (b.numerator() == 0) throw scope.error("divides by zero");
                        yield a.dividedBy(b);
                    }
                };
            } catch (ArithmeticException e) {
                throw scope.error("overflows");
            }
        }
    }

    private record Compare(Node left, Node right) implements Node {
        public Fraction value(Scope scope) {
            return Fraction.of(left.value(scope).compareTo(right.value(scope)) >= 0 ? 1 : 0);
        }
    }

    private record Call(String name, List<Node> arguments) implements Node {
        public Fraction value(Scope scope) {
            List<Fraction> values = arguments.stream().map(argument -> argument.value(scope)).toList();
            return switch (name) {
                case "floor" -> Fraction.of(values.get(0).floor());
                case "ceil" -> Fraction.of(values.get(0).ceil());
                case "min" -> values.stream().reduce((a, b) -> a.compareTo(b) <= 0 ? a : b).orElseThrow();
                default -> values.stream().reduce((a, b) -> a.compareTo(b) >= 0 ? a : b).orElseThrow();
            };
        }
    }

    // ── The parser ────────────────────────────────────────────────

    private static final class Parser {

        private final String source;
        private int position;

        Parser(String source) {
            this.source = source;
        }

        Expression parseFormula() {
            Node root = comparison();
            skipSpaces();
            if (position < source.length()) {
                throw error("has '" + source.substring(position) + "' left over");
            }
            return new Expression(source.trim(), root);
        }

        private Node comparison() {
            Node left = sum();
            if (take(">=")) {
                return new Compare(left, sum());
            }
            return left;
        }

        private Node sum() {
            Node node = product();
            while (true) {
                if (take("+")) node = new Binary('+', node, product());
                else if (take("-")) node = new Binary('-', node, product());
                else return node;
            }
        }

        private Node product() {
            Node node = unary();
            while (true) {
                if (take("*")) node = new Binary('*', node, unary());
                else if (take("/")) node = new Binary('/', node, unary());
                else return node;
            }
        }

        private Node unary() {
            if (take("-")) return new Negate(unary());
            if (take("+")) return unary();
            return primary();
        }

        private Node primary() {
            skipSpaces();
            if (take("(")) {
                Node inner = sum();
                expect(")");
                return inner;
            }
            if (position >= source.length()) {
                throw error("ends where a number or name should be");
            }
            char c = source.charAt(position);
            if (Character.isDigit(c)) {
                long number = digits();
                if (peekDice()) {
                    position++;
                    return dice(number);
                }
                return new Literal(number);
            }
            if (Character.isLetter(c) || c == '_') {
                if (peekDice()) {
                    position++;
                    return dice(1);
                }
                String name = name();
                skipSpaces();
                if (position < source.length() && source.charAt(position) == '(') {
                    position++;
                    return function(name);
                }
                return new Name(name);
            }
            throw error("has '" + c + "' where a number or name should be");
        }

        // A 'd' followed by digits and nothing that would make it a longer name, such as "d6" but not "druidLevel"
        private boolean peekDice() {
            if (position >= source.length() || source.charAt(position) != 'd') return false;
            int end = position + 1;
            while (end < source.length() && Character.isDigit(source.charAt(end))) end++;
            if (end == position + 1) return false;
            return end >= source.length() || !(Character.isLetterOrDigit(source.charAt(end))
                    || source.charAt(end) == '_');
        }

        private Node dice(long count) {
            long sides = digits();
            if (sides < 1) throw error("rolls a die with no sides");
            if (count > 100) throw error("rolls more than 100 dice");
            return new Dice((int) count, Math.toIntExact(sides));
        }

        private Node function(String name) {
            List<Node> arguments = new ArrayList<>();
            arguments.add(sum());
            while (take(",")) {
                arguments.add(sum());
            }
            expect(")");
            switch (name) {
                case "floor", "ceil" -> {
                    if (arguments.size() != 1) throw error("gives " + name + " " + arguments.size() + " arguments, not 1");
                }
                case "min", "max" -> {
                    if (arguments.size() < 2) throw error("gives " + name + " one argument, not two or more");
                }
                default -> throw error("calls '" + name + "', which isn't min, max, floor or ceil");
            }
            return new Call(name, List.copyOf(arguments));
        }

        private long digits() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            if (start == position) throw error("has no number where one should be");
            try {
                return Long.parseLong(source.substring(start, position));
            } catch (NumberFormatException e) {
                throw error("has a number too large");
            }
        }

        private String name() {
            int start = position;
            while (position < source.length()
                    && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '_')) {
                position++;
            }
            return source.substring(start, position);
        }

        private boolean take(String token) {
            skipSpaces();
            if (source.startsWith(token, position)) {
                position += token.length();
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!take(token)) {
                throw error(position < source.length() ? "has '" + source.charAt(position) + "' where '" + token
                        + "' should be" : "ends where '" + token + "' should be");
            }
        }

        private void skipSpaces() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private ExpressionException error(String problem) {
            return new ExpressionException("The formula '" + source.trim() + "' " + problem);
        }
    }
}
