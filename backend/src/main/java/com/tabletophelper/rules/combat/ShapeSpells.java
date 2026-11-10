package com.tabletophelper.rules.combat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The spells that turn a creature into another creature (D192), and the limits on the form each allows. The form's
 * statistics replace the creature's as {@link BeastShapes} describes for Wild Shape: the form's hit points are taken
 * first, and at 0 the creature reverts with the excess damage carried over.
 * <ul>
 *   <li>Polymorph (PHB p.266): a beast of challenge rating no higher than the target's, or its level; every game
 *       statistic is the beast's, mental scores included; no spells and no speech.</li>
 *   <li>Mass Polymorph (XGE p.161): as Polymorph, for up to ten creatures, the limit being half the level of a target
 *       without a challenge rating.</li>
 *   <li>True Polymorph (PHB p.283): any kind of creature of challenge rating no higher than the target's, or its level.
 *       Turning a creature into an object, or an object into a creature, is the DM's.</li>
 *   <li>Shapechange (PHB p.274): the caster becomes a creature of challenge rating no higher than its own level, not a
 *       construct or an undead; it keeps its Intelligence, Wisdom and Charisma, its features and proficiencies, using
 *       the higher save and skill bonus, and can change form with an action.</li>
 *   <li>Animal Shapes (PHB p.212): willing creatures become beasts of challenge rating 4 or lower, Large or smaller;
 *       each keeps its Intelligence, Wisdom and Charisma, and the caster can give them new forms with an action.</li>
 * </ul>
 */
public final class ShapeSpells {

    /** The entry a Polymorph-family spell leaves on its creature, which carries the form. */
    public static final String POLYMORPHED = "polymorphed";

    /** The entry Shapechange leaves on its caster. */
    public static final String SHAPECHANGED = "shapechanged";

    /** The entry Animal Shapes leaves on each creature. */
    public static final String ANIMAL_SHAPE = "animal shape";

    private static final String BEAST = "beast";
    private static final List<String> SIZES = List.of("t", "s", "m", "l", "h", "g");

    private ShapeSpells() {
    }

    /** How a spell's challenge rating limit is worked out. */
    public enum Limit {
        /** The target's challenge rating, or its level when it has none (Polymorph, True Polymorph). */
        TARGET,
        /** The target's challenge rating, or half its level when it has none (Mass Polymorph). */
        TARGET_HALF_LEVEL,
        /** The caster's level, or its challenge rating for a monster (Shapechange). */
        CASTER_LEVEL,
        /** A fixed rating (Animal Shapes' 4). */
        FIXED
    }

    /**
     * One spell's rules.
     *
     * @param spell            the spell's name
     * @param entry            the entry it leaves on the creature, which carries the form
     * @param beastsOnly       whether the form must be a beast
     * @param limit            how the challenge rating limit is worked out
     * @param fixedLimit       the limit for {@link Limit#FIXED}
     * @param largestSize      the largest size allowed, such as "Large", or null for any
     * @param keepsMind        whether the creature keeps its Intelligence, Wisdom and Charisma
     * @param keepsOwnTraits   whether it keeps its own proficiencies, features and defences alongside the form's
     * @param canCast          whether it can cast spells in the form
     * @param changesWithAction whether the caster can give a new form with an action while the spell lasts
     * @param noConstructOrUndead whether constructs and undead are refused as forms
     */
    public record Rules(String spell, String entry, boolean beastsOnly, Limit limit, double fixedLimit,
                        String largestSize, boolean keepsMind, boolean keepsOwnTraits, boolean canCast,
                        boolean changesWithAction, boolean noConstructOrUndead) {
    }

    public static final Rules POLYMORPH = new Rules("Polymorph", POLYMORPHED, true, Limit.TARGET, 0, null, false,
            false, false, false, false);
    public static final Rules MASS_POLYMORPH = new Rules("Mass Polymorph", POLYMORPHED, true, Limit.TARGET_HALF_LEVEL,
            0, null, false, false, false, false, false);
    public static final Rules TRUE_POLYMORPH = new Rules("True Polymorph", POLYMORPHED, false, Limit.TARGET, 0, null,
            false, false, false, false, false);
    public static final Rules SHAPECHANGE = new Rules("Shapechange", SHAPECHANGED, false, Limit.CASTER_LEVEL, 0, null,
            true, true, true, true, true);
    public static final Rules ANIMAL_SHAPES = new Rules("Animal Shapes", ANIMAL_SHAPE, true, Limit.FIXED, 4, "Large",
            true, false, false, true, false);

    private static final List<Rules> ALL = List.of(POLYMORPH, MASS_POLYMORPH, TRUE_POLYMORPH, SHAPECHANGE,
            ANIMAL_SHAPES);

    /** The rules of the spell by name, or empty for a spell that gives no form. */
    public static Optional<Rules> of(String spell) {
        if (spell == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(rules -> rules.spell().equalsIgnoreCase(spell.trim())).findFirst();
    }

    /**
     * The highest challenge rating the form may have.
     *
     * @param targetRating the target's challenge rating as its stat block writes it, or null for a player character
     * @param targetLevel  the target's level, for a target without a challenge rating
     * @param casterLevel  the caster's level, or its challenge rating for a monster, for Shapechange
     */
    public static double maxChallengeRating(Rules rules, String targetRating, int targetLevel, double casterLevel) {
        OptionalDouble cr = BeastShapes.challengeRating(targetRating);
        return switch (rules.limit()) {
            case TARGET -> cr.isPresent() ? cr.getAsDouble() : targetLevel;
            // Half its level, a fraction kept, as 1/2 for a 1st-level character (XGE p.161)
            case TARGET_HALF_LEVEL -> cr.isPresent() ? cr.getAsDouble() : targetLevel / 2.0;
            case CASTER_LEVEL -> casterLevel;
            case FIXED -> rules.fixedLimit();
        };
    }

    /**
     * Why a creature can't be the form, or empty when it can.
     *
     * @param maxRating the highest challenge rating allowed, from {@link #maxChallengeRating}
     */
    public static Optional<String> refusal(Rules rules, String name, String type, String rating, String size,
                                           Integer hitPoints, double maxRating) {
        String kind = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        if (rules.beastsOnly() && !kind.startsWith(BEAST)) {
            return Optional.of(name + " isn't a beast, and " + rules.spell() + " turns a creature only into a beast");
        }
        if (rules.noConstructOrUndead() && (kind.startsWith("construct") || kind.startsWith("undead"))) {
            return Optional.of(name + " is " + (kind.startsWith("undead") ? "an undead" : "a construct") + ", which "
                    + rules.spell() + " can't become");
        }
        if (hitPoints == null || hitPoints <= 0) {
            return Optional.of(name + "'s stat block gives no hit points, so " + rules.spell()
                    + " can't take its form");
        }
        OptionalDouble cr = BeastShapes.challengeRating(rating);
        if (cr.isEmpty()) {
            return Optional.of(name + "'s challenge rating can't be read");
        }
        if (cr.getAsDouble() > maxRating + 1e-9) {
            return Optional.of(name + " is challenge rating " + rating.trim() + ", above the limit of "
                    + label(maxRating));
        }
        if (rules.largestSize() != null && sizeRank(size) > sizeRank(rules.largestSize())) {
            return Optional.of(name + " is " + size + ", larger than " + rules.largestSize());
        }
        return Optional.empty();
    }

    /** A challenge rating limit as written, such as "1/2" or "7". */
    public static String label(double rating) {
        if (rating == 0.125) {
            return "1/8";
        }
        if (rating == 0.25) {
            return "1/4";
        }
        if (rating == 0.5) {
            return "1/2";
        }
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }

    // Tiny 0 to Gargantuan 5 by the size's first letter; an unknown size counts as Medium
    private static int sizeRank(String size) {
        if (size == null || size.isBlank()) {
            return 2;
        }
        int rank = SIZES.indexOf(size.trim().substring(0, 1).toLowerCase(Locale.ROOT));
        return rank >= 0 ? rank : 2;
    }
}
