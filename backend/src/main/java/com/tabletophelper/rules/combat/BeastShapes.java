package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wild Shape's rules under the 2014 PHB (Druid 2, p.66–67; Circle of the Moon, p.69), as pure functions.
 * <ul>
 *   <li>The beasts a druid can become: the Beast Shapes table limits the challenge rating and movement by druid level,
 *       CR 1/4 with no flying or swimming speed at 2nd, CR 1/2 with no flying speed at 4th, and CR 1 at 8th. A Circle
 *       of the Moon druid ignores the Max. CR column but keeps the movement limits (Circle Forms): CR 1 from 2nd, and
 *       its druid level divided by 3, rounded down, from 6th.</li>
 *   <li>The beasts offered are those of the rulebooks, not the adventures, with hit points in their stat block
 *       ({@link #offered}).</li>
 *   <li>Elemental Wild Shape (Circle of the Moon 10, PHB p.69): two uses of Wild Shape at once turn the druid into an
 *       air, earth, fire or water elemental, the Monster Manual's, outside the Beast Shapes limits.</li>
 *   <li>Wild Shape is an action, a bonus action for a Circle of the Moon druid (Combat Wild Shape), and lasts up to
 *       half the druid level in hours. Reverting early is a bonus action.</li>
 *   <li>Combat Wild Shape also lets a transformed Moon druid expend a spell slot as a bonus action to regain 1d8 hit
 *       points per level of the slot.</li>
 *   <li>A druid that drops to 0 hit points in beast form reverts, and damage left over carries to its normal form;
 *       it isn't knocked unconscious unless that damage reduces its normal form to 0.</li>
 *   <li>A saving throw or skill both the druid and the beast are proficient in uses the higher bonus.</li>
 * </ul>
 */
public final class BeastShapes {

    /** The entry a Wild Shape leaves among the druid's conditions while it lasts, holding the beast form. */
    public static final String ENTRY = "wild shape";

    /** The druid level that brings Wild Shape (PHB p.66). */
    public static final int FIRST_LEVEL = 2;

    /** The speed names that the Beast Shapes table limits. */
    public static final String FLY = "fly";
    public static final String SWIM = "swim";

    /**
     * The books whose beasts Wild Shape offers: the rulebooks 5etools files as core, supplement or setting books, of the
     * sources in the bundled bestiary data. Adventures, such as Waterdeep: Dungeon of the Mad Mage's CR 0 Haungharassk
     * with 52 hit points, and the alternative supplements, such as Adventures with Muk's Boontu Monkey with none, are
     * left out; the Plane Shift setting PDFs too. Acquisitions Incorporated, a supplement with no beasts in the data, isn't
     * listed. The codes are the monsters table's sources.
     */
    public static final Set<String> RULEBOOK_SOURCES = Set.of("PHB", "MM", "DMG", "SCAG", "VGM", "XGE", "MTF", "GGR",
            "ERLW", "EGW", "MOT", "TCE", "VRGR", "FTD", "SCC", "MPMM", "AAG", "BAM", "BGG", "MPP", "SatO", "BMT",
            "DMTCRG");

    /** The druid level of the Circle of the Moon that brings Elemental Wild Shape (PHB p.69). */
    public static final int ELEMENTAL_LEVEL = 10;

    /** The uses of Wild Shape that Elemental Wild Shape expends at the same time (PHB p.69). */
    public static final int ELEMENTAL_USES = 2;

    /** The book the elementals come from (PHB p.69 names the Monster Manual's). */
    public static final String ELEMENTAL_SOURCE = "MM";

    /** The four elementals Elemental Wild Shape can become (PHB p.69; MM p.124–125). */
    public static final List<String> ELEMENTALS = List.of("Air Elemental", "Earth Elemental", "Fire Elemental",
            "Water Elemental");

    private static final Pattern FRACTION = Pattern.compile("^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$");
    private static final Pattern WHOLE = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$");

    private BeastShapes() {}

    /**
     * What a druid of this level can become.
     *
     * @param maxChallengeRating the highest challenge rating, such as 0.25
     * @param flyingAllowed      whether a beast with a flying speed is allowed
     * @param swimmingAllowed    whether a beast with a swimming speed is allowed
     * @param label              the challenge rating as the books write it, such as "1/4"
     */
    public record Limit(double maxChallengeRating, boolean flyingAllowed, boolean swimmingAllowed, String label) {
    }

    /**
     * The Beast Shapes limits for the druid level (PHB p.66), with Circle Forms for a Circle of the Moon druid (PHB
     * p.69); empty below 2nd level, which has no Wild Shape.
     */
    public static Optional<Limit> limit(int druidLevel, boolean circleOfTheMoon) {
        if (druidLevel < FIRST_LEVEL) {
            return Optional.empty();
        }
        boolean fly = druidLevel >= 8;
        boolean swim = druidLevel >= 4;
        double cr;
        if (circleOfTheMoon) {
            cr = druidLevel >= 6 ? Math.floorDiv(druidLevel, 3) : 1;
        } else if (druidLevel >= 8) {
            cr = 1;
        } else if (druidLevel >= 4) {
            cr = 0.5;
        } else {
            cr = 0.25;
        }
        return Optional.of(new Limit(cr, fly, swim, label(cr)));
    }

    /**
     * Whether the picker offers a beast from this book with these hit points: one of {@link #RULEBOOK_SOURCES}, with more
     * than 0 hit points, so a stat block whose hit points are a formula of its master's level, such as Tasha's Beast of
     * the Land, isn't offered.
     */
    public static boolean offered(String source, Integer hitPoints) {
        return source != null && RULEBOOK_SOURCES.contains(source.trim()) && hitPoints != null && hitPoints > 0;
    }

    /** Whether a druid of this level can use Elemental Wild Shape: the Circle of the Moon's from 10th (PHB p.69). */
    public static boolean elementalWildShape(int druidLevel, boolean circleOfTheMoon) {
        return circleOfTheMoon && druidLevel >= ELEMENTAL_LEVEL;
    }

    /** Whether the monster is one of the four elementals Elemental Wild Shape can become, by name and book. */
    public static boolean elemental(String name, String source) {
        return name != null && ELEMENTAL_SOURCE.equals(source != null ? source.trim() : null)
                && ELEMENTALS.stream().anyMatch(elemental -> elemental.equalsIgnoreCase(name.trim()));
    }

    /** What Wild Shape costs: an action, or a bonus action for a Circle of the Moon druid (PHB p.66, p.69). */
    public static ActionCost cost(boolean circleOfTheMoon) {
        return circleOfTheMoon ? ActionCost.BONUS_ACTION : ActionCost.ACTION;
    }

    /** How long a Wild Shape lasts at most: half the druid level in hours, rounded down (PHB p.66). */
    public static int durationHours(int druidLevel) {
        return Math.max(0, druidLevel) / 2;
    }

    /**
     * A challenge rating as a number: "1/4" is 0.25 and "2" is 2; empty when it can't be read, such as a blank one.
     */
    public static OptionalDouble challengeRating(String rating) {
        if (rating == null) {
            return OptionalDouble.empty();
        }
        Matcher fraction = FRACTION.matcher(rating);
        if (fraction.matches()) {
            int below = Integer.parseInt(fraction.group(2));
            return below == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of(Integer.parseInt(fraction.group(1)) / (double) below);
        }
        Matcher whole = WHOLE.matcher(rating);
        return whole.matches() ? OptionalDouble.of(Double.parseDouble(whole.group(1))) : OptionalDouble.empty();
    }

    /**
     * Why a beast with this challenge rating and these speeds is beyond the limit, or empty when the druid can become
     * it. A rating that can't be read is refused, since its place on the table isn't known.
     *
     * @param speeds the beast's speeds in feet by kind, such as {"walk": 40, "swim": 30}
     */
    public static Optional<String> refusal(Limit limit, String beastName, String rating, Map<String, Integer> speeds) {
        OptionalDouble cr = challengeRating(rating);
        if (cr.isEmpty()) {
            return Optional.of(beastName + "'s challenge rating can't be read");
        }
        if (cr.getAsDouble() > limit.maxChallengeRating() + 1e-9) {
            return Optional.of(beastName + " is challenge rating " + rating.trim() + ", above the limit of "
                    + limit.label());
        }
        if (!limit.flyingAllowed() && hasSpeed(speeds, FLY)) {
            return Optional.of(beastName + " has a flying speed, which needs druid level 8");
        }
        if (!limit.swimmingAllowed() && hasSpeed(speeds, SWIM)) {
            return Optional.of(beastName + " has a swimming speed, which needs druid level 4");
        }
        return Optional.empty();
    }

    /** The dice a slot of this level heals with Combat Wild Shape: 1d8 per level (PHB p.69). */
    public static Dice combatHealing(int slotLevel) {
        if (slotLevel < 1 || slotLevel > 9) {
            throw new IllegalArgumentException("A spell slot is level 1 to 9, not " + slotLevel);
        }
        return new Dice(slotLevel, 8, 0);
    }

    /**
     * The damage that carries over to the druid's normal form when damage in beast form reaches 0 hit points (PHB
     * p.67): what is left after the beast's hit points and any temporary hit points; 0 when the damage doesn't drop it
     * to 0.
     */
    public static int carriedOver(int damage, int beastHp, int tempHp) {
        return Math.max(0, damage - Math.max(0, beastHp) - Math.max(0, tempHp));
    }

    /** Whether damage drops the beast form to 0 hit points, which ends the Wild Shape (PHB p.67). */
    public static boolean dropsForm(int damage, int beastHp, int tempHp) {
        return damage > 0 && damage >= Math.max(0, beastHp) + Math.max(0, tempHp);
    }

    /**
     * The bonus a druid in beast form uses for a saving throw or skill (PHB p.67): its own, worked out with the beast's
     * physical scores, or the beast's stat block bonus when that is listed and higher.
     *
     * @param beastListed the beast's bonus when its stat block lists one, or null
     */
    public static int bonus(int druidBonus, Integer beastListed) {
        return beastListed != null ? Math.max(druidBonus, beastListed) : druidBonus;
    }

    private static boolean hasSpeed(Map<String, Integer> speeds, String kind) {
        if (speeds == null) {
            return false;
        }
        Integer feet = speeds.get(kind);
        if (feet == null) {
            feet = speeds.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(kind))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        return feet != null && feet > 0;
    }

    private static String label(double cr) {
        if (cr == 0.25) {
            return "1/4";
        }
        if (cr == 0.5) {
            return "1/2";
        }
        return String.valueOf((int) cr);
    }
}
