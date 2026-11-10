package com.tabletophelper.rules.combat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a Tasha's spirit scales with the slot its spell is cast with (TCE p.109–114, D182), and Spirit of Death's Reaper
 * Spirit the same way (BMT p.50):
 * <ul>
 *   <li>AC: 11 + the spell's level, 12 + it for the fey spirit and 13 + it for the construct spirit.</li>
 *   <li>Hit points: the variant's at the spell's own level, plus so many for each slot level above it, as "30 + 5 for
 *       each spell level above 2nd" for a land or water beast.</li>
 *   <li>Multiattack: as many attacks as half the spell's level, rounded down.</li>
 *   <li>Damage: its dice plus a flat number plus the spell's level, as "1d8 + 4 + the spell's level".</li>
 * </ul>
 * The spirit's proficiency bonus is the caster's, and its attacks use the caster's spell attack bonus and its saves the
 * caster's spell save DC.
 */
public final class SpiritScaling {

    /** The placeholder for the slot level in a template's damage. */
    public static final String LEVEL = "LEVEL";

    // "1d8 + 4 + LEVEL", "2d4+3+LEVEL" or "1d6 + LEVEL": dice, any flat numbers, and the level
    private static final Pattern DAMAGE = Pattern.compile(
            "^\\s*(\\d+d\\d+)((?:\\s*\\+\\s*(?:\\d+|LEVEL))*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TERM = Pattern.compile("\\+\\s*(\\d+|LEVEL)", Pattern.CASE_INSENSITIVE);

    private SpiritScaling() {}

    /** The spirit's AC at this slot level. */
    public static int armourClass(int acBase, int slotLevel) {
        return acBase + slotLevel;
    }

    /** The spirit's hit points at this slot level; a slot below the spell's own counts as its own. */
    public static int hitPoints(int hpBase, int hpPerLevel, int baseLevel, int slotLevel) {
        return hpBase + hpPerLevel * Math.max(0, slotLevel - baseLevel);
    }

    /** How many attacks its Multiattack makes: half the spell's level, rounded down. */
    public static int attacks(int slotLevel) {
        return slotLevel / 2;
    }

    /**
     * A template's damage at this slot level: "1d8 + 4 + LEVEL" at 4th is "1d8 + 8". Anything else is returned as
     * it is.
     */
    public static String damage(String expression, int slotLevel) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = DAMAGE.matcher(expression);
        if (!matcher.matches() || !expression.toUpperCase().contains(LEVEL)) {
            return expression;
        }
        int flat = 0;
        Matcher term = TERM.matcher(matcher.group(2));
        while (term.find()) {
            flat += term.group(1).equalsIgnoreCase(LEVEL) ? slotLevel : Integer.parseInt(term.group(1));
        }
        return matcher.group(1) + (flat != 0 ? " + " + flat : "");
    }
}
