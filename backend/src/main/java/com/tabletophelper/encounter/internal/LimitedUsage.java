package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.monster.MonsterTemplates;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often a monster's action, bonus action, reaction or legendary action can be used when its uses are limited
 * (MM p.11, "Limited Usage"): so many times a day, which come back after a long rest, or once until a short or long
 * rest. A d6 recharge is the other kind of limit, and {@link MonsterPools#rechargePool} keeps it.
 *
 * <p>The data writes the limit in several ways, read in this order:
 * <ol>
 *   <li>the action's name, as the stat block gives it: "(1/Day)", "(3/Day each)", "(1st-Level Spell; 2/Day)",
 *       "(1/Short Rest)", "(Recharges after a Short or Long Rest)" or "(Recharges on a Long Rest)";</li>
 *   <li>a {@code usesPerDay} count;</li>
 *   <li>a {@code recharge} of "1/DAY" or "3/DAY";</li>
 *   <li>a {@code uses} count with what brings it back, in {@code recharge}, {@code rechargeType} or
 *       {@code usesRecharge};</li>
 *   <li>a {@code recharge}, {@code rechargeType} or {@code usesRecharge} of SHORT_REST, LONG_REST,
 *       SHORT_OR_LONG_REST, DAWN or a span of days, alone, which is one use.</li>
 * </ol>
 * A recharge on an event, such as "Recharges after the Enchanter Casts an Enchantment Spell", or once a turn, isn't
 * one of these, and limits written only in an action's description aren't read. Rests don't happen during an
 * encounter, so a limited pool never refills in one.
 *
 * @param uses    how many uses the monster has
 * @param resetOn what brings them back, as a pool's resetOn: "longRest", "shortRest", "dawn" or "never"
 */
record LimitedUsage(int uses, String resetOn) {

    // "(3/Day)", "(3/Day each)" or "; 2/Day)" in a name
    private static final Pattern PER_DAY = Pattern.compile("(?i)(\\d{1,2})\\s*/\\s*day\\b");
    // "(1/Short Rest)" in a name
    private static final Pattern PER_REST = Pattern.compile("(?i)(\\d{1,2})\\s*/\\s*(short or long|short|long)\\s+rest");
    // "Recharges after a Short or Long Rest" or "Recharges on a Long Rest" in a name
    private static final Pattern RECHARGES_ON_REST =
            Pattern.compile("(?i)recharges?\\s+(?:after|on)\\s+(?:a|an|its)\\s+(short or long|short|long)\\s+rest");
    // A recharge of "3/DAY"
    private static final Pattern DAILY_RECHARGE = Pattern.compile("^(\\d{1,2})_?/_?DAY$");
    // A span of days, such as "9_DAYS"
    private static final Pattern DAYS = Pattern.compile("^\\d{1,3}_DAYS?$");

    /** The action's limit, or empty for one without, such as an action used at will or on a d6 recharge. */
    static Optional<LimitedUsage> of(MonsterTemplates.Action action) {
        String name = action.name() != null ? action.name() : "";
        Matcher perDay = PER_DAY.matcher(name);
        if (perDay.find()) {
            return positive(Integer.parseInt(perDay.group(1)), "longRest");
        }
        Matcher perRest = PER_REST.matcher(name);
        if (perRest.find()) {
            return positive(Integer.parseInt(perRest.group(1)), restOf(perRest.group(2)));
        }
        Matcher onRest = RECHARGES_ON_REST.matcher(name);
        if (onRest.find()) {
            return positive(1, restOf(onRest.group(1)));
        }

        if (action.usesPerDay() != null) {
            return positive(action.usesPerDay(), "longRest");
        }
        String recharge = token(action.recharge());
        Matcher dailyRecharge = DAILY_RECHARGE.matcher(recharge);
        if (dailyRecharge.matches()) {
            return positive(Integer.parseInt(dailyRecharge.group(1)), "longRest");
        }
        String reset = reset(recharge);
        if (reset == null) {
            reset = reset(token(action.rechargeType()));
        }
        if (reset == null) {
            reset = reset(token(action.usesRecharge()));
        }
        if (reset == null) {
            return Optional.empty();
        }
        return positive(action.uses() != null ? action.uses() : 1, reset);
    }

    /**
     * The full pool an action with this limit spends, named after the action; it never refills during an encounter.
     *
     * @param spendActionType what the action costs, ACTION, BONUS_ACTION, REACTION or FREE, as the pool's
     *                        spendActionType
     */
    static Optional<ResourcePoolEntry> pool(MonsterTemplates.Action action, String monsterName,
                                            String spendActionType) {
        String name = action.name() != null ? action.name().trim() : "";
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return of(action).map(limit -> new ResourcePoolEntry(poolId(name), name, "MONSTER", monsterName,
                limit.uses(), null, limit.uses(), limit.resetOn(), null, null, spendActionType, "sparkles", Map.of()));
    }

    /** The pool a limited action spends: "monster:limited:" and the action's name in kebab case. */
    static String poolId(String actionName) {
        return "monster:limited:" + actionName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    /** When the uses come back, for the refusal of an action that has none left. */
    String comesBack() {
        return switch (resetOn) {
            case "shortRest" -> "after a short or long rest";
            case "dawn" -> "at dawn";
            case "never" -> "only after days";
            default -> "after a long rest";
        };
    }

    private static Optional<LimitedUsage> positive(int uses, String resetOn) {
        return uses > 0 ? Optional.of(new LimitedUsage(uses, resetOn)) : Optional.empty();
    }

    // "short or long" and "short" come back on a short rest, which a long rest also is; "long" only on a long rest
    private static String restOf(String rest) {
        return rest.toLowerCase(Locale.ROOT).startsWith("short") ? "shortRest" : "longRest";
    }

    // A recharge, rechargeType or usesRecharge as a rest token: upper case, words joined by underscores
    private static String token(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    // What a token brings the uses back on, or null for one that isn't a rest, a dawn or a span of days
    private static String reset(String token) {
        return switch (token) {
            case "SHORT_REST", "SHORT_OR_LONG_REST" -> "shortRest";
            case "LONG_REST" -> "longRest";
            case "DAWN" -> "dawn";
            default -> DAYS.matcher(token).matches() ? "never" : null;
        };
    }
}
