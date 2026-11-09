package com.tabletophelper.rules.combat;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sorcerer's Metamagic (PHB p.101–102, M18): the eight options of the Player's Handbook, what each costs in sorcery
 * points, which spells each can change, and which may go together. Only one option may change a casting, except that
 * Empowered Spell may be added to another (PHB p.102). Pure: the encounter module reads a spell's facts from its data
 * and applies what the options do.
 */
public final class Metamagic {

    /** A sorcerer knows an option as a feature of this name followed by the option's, such as "Metamagic: Subtle Spell". */
    public static final String FEATURE_PREFIX = "Metamagic: ";

    /** Extended Spell doubles a duration to at most 24 hours (PHB p.102), in rounds of 6 seconds. */
    public static final int MAX_EXTENDED_ROUNDS = 24 * 600;

    // A minute is 10 rounds (PHB p.189)
    private static final int ONE_MINUTE = 10;
    private static final Pattern LASTING = Pattern.compile("\\b\\d+\\s*(minute|hour|day)s?\\b");
    private static final Pattern DISTANCE = Pattern.compile("^(\\d+)\\s*(feet|foot|ft|miles?)\\b");

    private Metamagic() {
    }

    /** The Player's Handbook's options, each with its name and the sorcery points it costs (PHB p.102). */
    public enum Option {
        /** 1 point: up to the sorcerer's CHA modifier (at least one) creatures succeed on their save automatically */
        CAREFUL("Careful Spell", 1),
        /** 1 point: a range of 5 feet or more doubles, and touch becomes 30 feet */
        DISTANT("Distant Spell", 1),
        /** 1 point: reroll up to the CHA modifier damage dice, which may join another option */
        EMPOWERED("Empowered Spell", 1),
        /** 1 point: a duration of 1 minute or longer doubles, to at most 24 hours */
        EXTENDED("Extended Spell", 1),
        /** 3 points: one target has disadvantage on its first save against the spell */
        HEIGHTENED("Heightened Spell", 3),
        /** 2 points: a casting time of 1 action becomes 1 bonus action */
        QUICKENED("Quickened Spell", 2),
        /** 1 point: no somatic or verbal components */
        SUBTLE("Subtle Spell", 1),
        /** The spell's level in points, 1 for a cantrip: a second target for a spell that can have only one */
        TWINNED("Twinned Spell", 0);

        private final String displayName;
        private final int points;

        Option(String displayName, int points) {
            this.displayName = displayName;
            this.points = points;
        }

        /** Its name as the PHB and the class tables give it, such as "Quickened Spell". */
        public String displayName() {
            return displayName;
        }

        /** The option a name gives, by its PHB name or its constant, ignoring case. */
        public static Optional<Option> named(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String trimmed = name.trim();
            for (Option option : values()) {
                if (option.displayName.equalsIgnoreCase(trimmed) || option.name().equalsIgnoreCase(trimmed)) {
                    return Optional.of(option);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What Metamagic needs to know of a spell, from its data.
     *
     * @param level                     its level, 0 for a cantrip
     * @param castingTime               what casting it costs from a turn
     * @param forcesSave                it makes creatures roll a saving throw
     * @param dealsDamage               it rolls damage
     * @param durationRounds            how many rounds it lasts, when the data says
     * @param durationText              its duration as the PHB words it, such as "Concentration, up to 1 minute"
     * @param singleTarget              it targets one creature
     * @param selfOnly                  it can target only its caster
     * @param targetCount               how many targets it has at its own level, when it has a limit
     * @param additionalTargetsPerLevel the targets it adds for each slot level above its own, when it adds any
     * @param range                     its range as the PHB words it, such as "60 feet", "Touch" or "Self"
     * @param verbal                    it has a verbal component
     * @param somatic                   it has a somatic component
     */
    public record SpellFacts(int level, CastingTime castingTime, boolean forcesSave, boolean dealsDamage,
                             Integer durationRounds, String durationText, boolean singleTarget, boolean selfOnly,
                             Integer targetCount, Integer additionalTargetsPerLevel, String range, boolean verbal,
                             boolean somatic) {

        /** The same facts with another casting time, such as the one a cast is charged. */
        public SpellFacts withCastingTime(CastingTime time) {
            return new SpellFacts(level, time, forcesSave, dealsDamage, durationRounds, durationText, singleTarget,
                    selfOnly, targetCount, additionalTargetsPerLevel, range, verbal, somatic);
        }
    }

    /** The sorcery points the option costs for a spell cast at castLevel: Twinned's is that level, 1 for a cantrip. */
    public static int cost(Option option, int castLevel) {
        return option == Option.TWINNED ? Math.max(1, castLevel) : option.points;
    }

    /**
     * Why the option can't change the spell cast at castLevel, or empty when it can (PHB p.102):
     * <ul>
     *   <li>Careful and Heightened need a spell that forces a saving throw;</li>
     *   <li>Distant a range of 5 feet or more, or touch;</li>
     *   <li>Empowered a spell that rolls damage;</li>
     *   <li>Extended a duration of 1 minute or longer;</li>
     *   <li>Quickened a casting time of 1 action;</li>
     *   <li>Subtle a verbal or somatic component;</li>
     *   <li>Twinned a spell that targets only one creature, doesn't have a range of self, and can't target more than one
     *       creature at the level it is cast at.</li>
     * </ul>
     */
    public static Optional<String> ineligible(Option option, SpellFacts spell, int castLevel) {
        String name = option.displayName();
        return switch (option) {
            case CAREFUL, HEIGHTENED -> spell.forcesSave() ? Optional.empty()
                    : Optional.of(name + " needs a spell that forces a saving throw");
            case DISTANT -> distantRange(spell) ? Optional.empty()
                    : Optional.of(name + " needs a range of 5 feet or more, or touch");
            case EMPOWERED -> spell.dealsDamage() ? Optional.empty()
                    : Optional.of(name + " needs a spell that rolls damage");
            case EXTENDED -> lastsAMinute(spell) ? Optional.empty()
                    : Optional.of(name + " needs a duration of 1 minute or longer");
            case QUICKENED -> spell.castingTime() == CastingTime.ACTION ? Optional.empty()
                    : Optional.of(name + " needs a casting time of 1 action");
            case SUBTLE -> spell.verbal() || spell.somatic() ? Optional.empty()
                    : Optional.of(name + " needs a spell with a verbal or somatic component");
            case TWINNED -> twinnedProblem(spell, castLevel);
        };
    }

    /**
     * The highest level the option can change the spell at, when it is lower than 9th: Twinned Spell's limit for a
     * spell that gains targets when cast with a higher slot, such as Hold Person, which can target two creatures from
     * 3rd level (PHB p.102, p.251). Empty when the level doesn't matter.
     */
    public static Optional<Integer> maxCastLevel(Option option, SpellFacts spell) {
        if (option == Option.TWINNED && spell.additionalTargetsPerLevel() != null
                && spell.additionalTargetsPerLevel() > 0) {
            return Optional.of(Math.max(1, spell.level()));
        }
        return Optional.empty();
    }

    /**
     * Why these options can't go on one casting together, or empty when they can: each once, and only one besides
     * Empowered Spell (PHB p.102).
     */
    public static Optional<String> combinationProblem(Collection<Option> options) {
        Set<Option> distinct = EnumSet.noneOf(Option.class);
        for (Option option : options) {
            if (!distinct.add(option)) {
                return Optional.of(option.displayName() + " is chosen twice");
            }
        }
        long others = distinct.stream().filter(option -> option != Option.EMPOWERED).count();
        if (others > 1) {
            return Optional.of("Only one Metamagic option can change a spell, except Empowered Spell, which can go "
                    + "with another (" + String.join(", ", distinct.stream().map(Option::displayName).toList())
                    + ")");
        }
        return Optional.empty();
    }

    /** The creatures Careful Spell protects: up to the CHA modifier, at least one (PHB p.102). */
    public static int carefulCreatures(int charismaModifier) {
        return Math.max(1, charismaModifier);
    }

    /** The damage dice Empowered Spell rerolls: up to the CHA modifier, which gives none below +1 (PHB p.102). */
    public static int empoweredDice(int charismaModifier) {
        return Math.max(0, charismaModifier);
    }

    /** A duration doubled by Extended Spell, to at most 24 hours (PHB p.102). */
    public static int extendedRounds(int rounds) {
        return (int) Math.min(MAX_EXTENDED_ROUNDS, rounds * 2L);
    }

    /** Whether the spell lasts 1 minute or longer, which Extended Spell needs. */
    public static boolean lastsAMinute(SpellFacts spell) {
        if (spell.durationRounds() != null) {
            return spell.durationRounds() >= ONE_MINUTE;
        }
        return spell.durationText() != null
                && LASTING.matcher(spell.durationText().toLowerCase(Locale.ROOT)).find();
    }

    // A range of 5 feet or more, or touch; not self, sight, unlimited or special
    private static boolean distantRange(SpellFacts spell) {
        if (spell.range() == null || spell.selfOnly()) {
            return false;
        }
        String range = spell.range().trim().toLowerCase(Locale.ROOT);
        if (range.equals("touch")) {
            return true;
        }
        Matcher distance = DISTANCE.matcher(range);
        if (!distance.find()) {
            return false;
        }
        return distance.group(2).startsWith("mile") || Integer.parseInt(distance.group(1)) >= 5;
    }

    private static Optional<String> twinnedProblem(SpellFacts spell, int castLevel) {
        String name = Option.TWINNED.displayName();
        if (spell.selfOnly() || spell.range() != null && spell.range().trim().toLowerCase(Locale.ROOT)
                .startsWith("self")) {
            return Optional.of(name + " can't change a spell with a range of self");
        }
        if (!spell.singleTarget() || spell.targetCount() != null && spell.targetCount() != 1) {
            return Optional.of(name + " needs a spell that targets only one creature");
        }
        Integer perLevel = spell.additionalTargetsPerLevel();
        if (perLevel != null && perLevel > 0 && castLevel > spell.level()) {
            return Optional.of(name + " needs a spell that can't target more than one creature at the level it is "
                    + "cast at, and a level " + castLevel + " casting can target more");
        }
        return Optional.empty();
    }

    /** Each option's PHB name, in the order given. */
    public static List<String> names(Collection<Option> options) {
        return options.stream().map(Option::displayName).toList();
    }
}
