package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A character's Armour Class under the 2014 rules, before the DM's adjustments (M13.5), with the parts it adds up from.
 * <ul>
 *   <li>Body armour (PHB p.144-145): light armour's base AC plus the DEX modifier, medium armour's plus the DEX modifier
 *       up to +2, heavy armour's alone, each with its magic bonus.</li>
 *   <li>Without body armour: 10 + the DEX modifier (PHB p.14), or the best of the character's other unarmoured
 *       formulas, which don't stack: the Barbarian's Unarmored Defense (10 + DEX + CON, a shield allowed, PHB p.48), the
 *       Monk's (10 + DEX + WIS, no shield, PHB p.78), Draconic Resilience (13 + DEX, PHB p.102) and a race's natural
 *       armour, such as the Lizardfolk's 13 + DEX (VGM p.113) or the Tortle's flat 17 (TTP p.4). The Lizardfolk's,
 *       Locathah's and Loxodon's natural armour may also stand in for worn armour that would give a lower AC.</li>
 *   <li>A shield adds 2 and its magic bonus (PHB p.144).</li>
 *   <li>Flat bonuses, such as the Warforged's Integrated Protection (+1, ERLW p.36), always apply.</li>
 * </ul>
 */
public final class ArmourClass {

    /** The AC of a creature without armour: 10 + its DEX modifier (PHB p.14). */
    public static final int UNARMOURED_BASE = 10;
    /** The most of its DEX modifier medium armour lets a creature add (PHB p.144). */
    public static final int MEDIUM_DEX_CAP = 2;
    /** How much heavy armour slows a wearer without the Strength it needs (PHB p.144). */
    public static final int HEAVY_ARMOUR_SPEED_PENALTY = 10;

    private ArmourClass() {}

    /** The armour categories of PHB p.144. */
    public enum Category {
        LIGHT, MEDIUM, HEAVY
    }

    /**
     * The body armour worn.
     *
     * @param base  its base AC, such as Chain Mail's 16
     * @param bonus its magic bonus to AC, 0 for none
     */
    public record Armour(String name, Category category, int base, int bonus) {
        public Armour {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
        }
    }

    /**
     * The shield carried.
     *
     * @param base  what it adds, 2 for every shield in the books
     * @param bonus its magic bonus to AC, 0 for none
     */
    public record Shield(String name, int base, int bonus) {
        public Shield {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A way to work the AC out without body armour, such as Unarmored Defense or natural armour.
     *
     * @param abilities    the abilities whose modifiers it adds, in the order the breakdown shows them
     * @param allowsShield whether it still applies with a shield; the Monk's doesn't
     * @param withArmour   whether it may be used in place of worn armour that would give a lower AC, as the
     *                     Lizardfolk's, Locathah's and Loxodon's natural armour may (VGM p.113, LR p.5, GGR p.19)
     */
    public record Unarmoured(String name, int base, List<Ability> abilities, boolean allowsShield,
                             boolean withArmour) {
        public Unarmoured {
            Objects.requireNonNull(name, "name");
            abilities = List.copyOf(abilities);
        }

        /** A formula that applies only without armour. */
        public Unarmoured(String name, int base, List<Ability> abilities, boolean allowsShield) {
            this(name, base, abilities, allowsShield, false);
        }
    }

    /** A bonus to AC that always applies, such as Integrated Protection's +1. */
    public record Bonus(String name, int amount) {
        public Bonus {
            Objects.requireNonNull(name, "name");
        }
    }

    /** One line of the breakdown, such as "Chain Mail" 16 or "DEX (max 2)" 2. */
    public record Part(String label, int amount) {
    }

    /** The AC and what it adds up from, in order. */
    public record Result(int total, List<Part> parts) {
        public Result {
            parts = List.copyOf(parts);
        }
    }

    /** What the Defense fighting style adds while the character wears armour (PHB p.72). */
    public static final int DEFENSE_BONUS = 1;

    /**
     * The Defense fighting style's +1 AC, which applies only while the character wears armour (PHB p.72): a shield
     * alone isn't armour for it. Empty without the style or without armour.
     */
    public static Optional<Bonus> defense(boolean hasStyle, Armour armour) {
        return hasStyle && armour != null ? Optional.of(new Bonus("Defense", DEFENSE_BONUS))
                : Optional.empty();
    }

    /** The default unarmoured formula, 10 + DEX, which everyone has (PHB p.14). */
    public static Unarmoured unarmoured() {
        return new Unarmoured("Unarmoured", UNARMOURED_BASE, List.of(Ability.DEX), true);
    }

    /** The Barbarian's Unarmored Defense: 10 + DEX + CON, a shield allowed (PHB p.48). */
    public static Unarmoured barbarianUnarmouredDefence() {
        return new Unarmoured("Unarmoured Defence", UNARMOURED_BASE, List.of(Ability.DEX, Ability.CON), true);
    }

    /** The Monk's Unarmored Defense: 10 + DEX + WIS, without a shield (PHB p.78). */
    public static Unarmoured monkUnarmouredDefence() {
        return new Unarmoured("Unarmoured Defence", UNARMOURED_BASE, List.of(Ability.DEX, Ability.WIS), false);
    }

    /**
     * The AC: the body armour's when one is worn, or else the best unarmoured formula that applies (10 + DEX among
     * them, so none is ever worse), then the shield and the flat bonuses.
     *
     * @param armour     the body armour worn, or null for none
     * @param shield     the shield carried, or null for none
     * @param modifiers  each ability's modifier
     * @param unarmoured the character's unarmoured formulas besides 10 + DEX, in the order they are offered; one
     *                   gained first wins a tie
     * @param bonuses    the flat bonuses
     */
    public static Result of(Armour armour, Shield shield, Map<Ability, Integer> modifiers, List<Unarmoured> unarmoured,
                            List<Bonus> bonuses) {
        List<Part> parts = new ArrayList<>();
        if (armour != null) {
            parts.add(new Part(armour.name(), armour.base() + armour.bonus()));
            int dex = modifier(modifiers, Ability.DEX);
            switch (armour.category()) {
                case LIGHT -> parts.add(new Part("DEX", dex));
                case MEDIUM -> parts.add(new Part("DEX (max " + MEDIUM_DEX_CAP + ")", Math.min(dex, MEDIUM_DEX_CAP)));
                case HEAVY -> { }
            }
            // Natural armour that may stand in for armour giving a lower AC
            int worn = parts.stream().mapToInt(Part::amount).sum();
            for (Unarmoured formula : unarmoured) {
                if (formula.withArmour() && (shield == null || formula.allowsShield())
                        && value(formula, modifiers) > worn) {
                    parts = formulaParts(formula, modifiers);
                    worn = value(formula, modifiers);
                }
            }
        } else {
            parts.addAll(best(shield != null, modifiers, unarmoured));
        }
        if (shield != null) {
            parts.add(new Part(shield.name(), shield.base() + shield.bonus()));
        }
        for (Bonus bonus : bonuses) {
            parts.add(new Part(bonus.name(), bonus.amount()));
        }
        int total = parts.stream().mapToInt(Part::amount).sum();
        return new Result(total, parts);
    }

    /** What an unarmoured formula gives, its base and each ability's modifier. */
    public static int value(Unarmoured formula, Map<Ability, Integer> modifiers) {
        return formula.base() + formula.abilities().stream().mapToInt(a -> modifier(modifiers, a)).sum();
    }

    /**
     * How much heavy armour slows its wearer: 10 feet when the Strength score is below the armour's requirement
     * (PHB p.144), unless something exempts the wearer, as a dwarf's Speed trait does (PHB p.20); otherwise 0.
     *
     * @param strengthRequirement the armour's requirement, or null for none
     */
    public static int speedPenalty(Armour armour, Integer strengthRequirement, int strength, boolean exempt) {
        if (armour == null || armour.category() != Category.HEAVY || strengthRequirement == null || exempt) {
            return 0;
        }
        return strength < strengthRequirement ? HEAVY_ARMOUR_SPEED_PENALTY : 0;
    }

    // The highest of the formulas that apply, 10 + DEX first, a later one only when strictly higher
    private static List<Part> best(boolean shield, Map<Ability, Integer> modifiers, List<Unarmoured> formulas) {
        Unarmoured best = unarmoured();
        int bestValue = value(best, modifiers);
        for (Unarmoured formula : formulas) {
            if (shield && !formula.allowsShield()) continue;
            int value = value(formula, modifiers);
            if (value > bestValue) {
                best = formula;
                bestValue = value;
            }
        }
        return formulaParts(best, modifiers);
    }

    private static List<Part> formulaParts(Unarmoured formula, Map<Ability, Integer> modifiers) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(formula.name(), formula.base()));
        for (Ability ability : formula.abilities()) {
            parts.add(new Part(ability.name(), modifier(modifiers, ability)));
        }
        return parts;
    }

    private static int modifier(Map<Ability, Integer> modifiers, Ability ability) {
        Integer modifier = modifiers.get(ability);
        return modifier != null ? modifier : 0;
    }
}
