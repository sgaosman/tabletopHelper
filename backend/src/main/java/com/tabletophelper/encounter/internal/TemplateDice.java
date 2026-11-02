package com.tabletophelper.encounter.internal;

import com.tabletophelper.rules.Dice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Damage and healing expressions as spell and monster templates write them, read into {@link Dice} without
 * exceptions, so an expression the app can't roll leaves the Effect to the DM instead of failing the command.
 */
final class TemplateDice {

    // One group: NdM with an optional modifier, or a flat amount; counts and sides from 1 to 100, as Dice reads
    private static final Pattern GROUP = Pattern.compile(
            "^(?:(?:100|[1-9]\\d?)d(?:100|[1-9]\\d?)(?:\\s*[+-]\\s*\\d{1,4})?|\\d{1,4})$", Pattern.CASE_INSENSITIVE);

    // The spellcasting modifier placeholder, as "+MOD" or "+SPELL_MOD"
    private static final Pattern MODIFIER_PLACEHOLDER = Pattern.compile("\\s*\\+\\s*(?:SPELL_)?MOD\\b");

    // Where one dice group ends and the next begins, as in "2d8+1d6"
    private static final Pattern NEXT_GROUP = Pattern.compile("\\s*\\+\\s*(?=\\d{1,3}d\\d)", Pattern.CASE_INSENSITIVE);

    private TemplateDice() {
    }

    /**
     * Reads an expression such as "2d6", "1d4 + 2", "5", "2d8+1d6" or "1d8+MOD" into one Dice per group. A MOD
     * or SPELL_MOD placeholder adds spellcastingModifier to the last group as a number, so a negative modifier
     * still reads (D160). Empty when the expression is none of these.
     */
    static Optional<List<Dice>> read(String expression, int spellcastingModifier) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        Matcher placeholder = MODIFIER_PLACEHOLDER.matcher(expression);
        boolean addsModifier = placeholder.find();
        String rest = placeholder.replaceAll("").trim();

        List<Dice> groups = new ArrayList<>();
        for (String group : NEXT_GROUP.split(rest)) {
            String trimmed = group.trim();
            if (!GROUP.matcher(trimmed).matches()) {
                return Optional.empty();
            }
            groups.add(Dice.parse(trimmed));
        }
        if (addsModifier) {
            groups.set(groups.size() - 1, withModifier(groups.getLast(), spellcastingModifier));
        }
        return Optional.of(List.copyOf(groups));
    }

    /** Reads an expression without a spellcasting modifier; see {@link #read(String, int)}. */
    static Optional<List<Dice>> read(String expression) {
        return read(expression, 0);
    }

    /**
     * The groups cast at a higher level: perLevel's dice, levels times over, join the group with the same die,
     * or form a group of their own when none has it, and flatPerLevel adds to the last group's modifier.
     */
    static List<Dice> upcast(List<Dice> groups, Dice perLevel, int flatPerLevel, int levels) {
        List<Dice> result = new ArrayList<>(groups);
        if (levels <= 0) {
            return List.copyOf(result);
        }
        if (perLevel != null && perLevel.count() > 0) {
            int extra = perLevel.count() * levels;
            int index = indexOfSides(result, perLevel.sides());
            if (index >= 0) {
                Dice group = result.get(index);
                result.set(index, new Dice(group.count() + extra, group.sides(), group.modifier()));
            } else {
                result.add(new Dice(extra, perLevel.sides(), 0));
            }
        }
        if (flatPerLevel != 0 && !result.isEmpty()) {
            result.set(result.size() - 1, withModifier(result.getLast(), flatPerLevel * levels));
        }
        return List.copyOf(result);
    }

    private static int indexOfSides(List<Dice> groups, int sides) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).count() > 0 && groups.get(i).sides() == sides) {
                return i;
            }
        }
        return -1;
    }

    private static Dice withModifier(Dice dice, int added) {
        return new Dice(dice.count(), dice.sides(), dice.modifier() + added);
    }
}
