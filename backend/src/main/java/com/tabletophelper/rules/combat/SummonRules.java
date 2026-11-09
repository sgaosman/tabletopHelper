package com.tabletophelper.rules.combat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The summoning spells of the 2014 rules that bring creatures into a fight (D182), as pure functions: what each spell
 * offers at a slot level, which creatures fit an option, how their initiative works and what they can do.
 * <ul>
 *   <li>Conjure Animals (PHB p.225): one beast of CR 2 or lower, two of CR 1, four of CR 1/2 or eight of CR 1/4, twice
 *       as many with a 5th-level slot, three times with a 7th and four times with a 9th. Each is also fey, disappears
 *       at 0 hit points or when the spell ends, and the summoned creatures roll initiative as a group. The DM has the
 *       creatures' statistics; a campaign setting makes the DM choose them.</li>
 *   <li>Summon Lesser Demons (XGE p.167): a d6 for two demons of CR 1 or lower (1–2), four of CR 1/2 (3–4) or eight
 *       of CR 1/4 (5–6), twice as many with a 6th- or 7th-level slot and three times with an 8th or 9th. The DM chooses
 *       the demons, which are hostile to every creature, the caster included, disappear at 0 hit points and roll
 *       initiative as a group.</li>
 *   <li>Summon Beast, Fey, Shadowspawn and Undead (TCE p.109–114): one spirit whose statistics scale with the slot
 *       ({@link SpiritScaling}); it shares the caster's initiative count and takes its turn immediately after, and
 *       disappears at 0 hit points.</li>
 *   <li>Find Familiar (PHB p.240): one of fifteen forms, four more with Pact of the Chain (PHB p.107), a celestial,
 *       fey or fiend; it rolls its own initiative, can't attack, and disappears at 0 hit points. One at a time.</li>
 *   <li>Flock of Familiars (LLK p.57): three familiars, one more for each slot level above 2nd and one fewer when the
 *       caster has a familiar already; concentration, up to 1 hour.</li>
 *   <li>Find Steed (PHB p.240): a warhorse, pony, camel, elk or mastiff, a celestial, fey or fiend with an
 *       Intelligence of at least 6. It keeps its own place in the initiative order unless controlled as a mount,
 *       when its initiative matches the rider's (PHB p.198). One at a time; disappears at 0 hit points.</li>
 *   <li>Animate Dead (PHB p.212): a skeleton or zombie, two more for each slot level above 3rd, commanded with a bonus
 *       action; uncommanded, it only defends itself. Tiny Servant (XGE p.168): one object, two more for each slot level
 *       above 3rd, commanded the same way; at 0 hit points it reverts to an object.</li>
 * </ul>
 * Group initiative for identical creatures follows PHB p.189: "The DM makes one roll for an entire group of identical
 * creatures".
 */
public final class SummonRules {

    /** How the spell's creatures are made and governed. */
    public enum Kind {
        CONJURE_ANIMALS, LESSER_DEMONS, SPIRIT, FAMILIAR, FLOCK_OF_FAMILIARS, STEED, ANIMATE_DEAD, TINY_SERVANT
    }

    /** Where the summoned creatures take their turns. */
    public enum InitiativeMode {
        /** Right after the caster's turn, on its initiative count. */
        FOLLOWS_CASTER,
        /** One roll for the group, each creature still taking its own turn. */
        GROUP,
        /** Each creature rolls its own. */
        INDIVIDUAL
    }

    /** Who decides what the creatures do: the caster's player, or the DM. */
    public enum Controller { CASTER, DM }

    /**
     * A summoning spell.
     *
     * @param name             as the spell is named
     * @param kind             how its creatures are made and governed
     * @param level            the spell's level
     * @param concentration    whether it needs concentration
     * @param durationRounds   how long its creatures stay, in rounds; null for an instantaneous spell whose creatures
     *                         stay until they drop or are dismissed
     * @param castInCombat     whether it can be cast in a turn; the others take a minute or more (PHB p.202)
     * @param dmChooses        whether the DM always chooses its creatures
     * @param vanishesAtZero   whether its creatures disappear when they drop to 0 hit points
     * @param cannotAttack     whether its creatures can't attack
     * @param needsCommand     whether its creatures act only when the caster has commanded them with a bonus action
     * @param initiativeModes  the ways its creatures' initiative may go, the rules' own first
     * @param houseRuleModes   those of the modes the rules don't give, which a table may still choose
     * @param controller       who decides what the creatures do
     * @param creatureType     the type its creatures count as, or null for their own
     * @param typeChoices      the types the caster chooses among, or none
     * @param source           the book, for the log
     * @param page             its page
     */
    public record Spell(String name, Kind kind, int level, boolean concentration, Integer durationRounds,
                        boolean castInCombat, boolean dmChooses, boolean vanishesAtZero, boolean cannotAttack,
                        boolean needsCommand, List<InitiativeMode> initiativeModes, List<InitiativeMode> houseRuleModes,
                        Controller controller, String creatureType, List<String> typeChoices, String source, int page) {

        /** The way the rules give its creatures' initiative. */
        public InitiativeMode defaultInitiativeMode() {
            return initiativeModes.getFirst();
        }

        /** Whether its creatures roll initiative per kind of identical creature rather than as one group. */
        public boolean groupsByCreature() {
            return kind == Kind.ANIMATE_DEAD || kind == Kind.TINY_SERVANT;
        }

        /** Whether a creature count below the option's is allowed: the caster may animate fewer. */
        public boolean fewerAllowed() {
            return kind == Kind.ANIMATE_DEAD || kind == Kind.TINY_SERVANT;
        }
    }

    /**
     * What a casting can bring: count creatures, each of challenge rating maxCr or lower when the spell limits it.
     *
     * @param id     names the option, such as "8xCR1/4" or "1"
     * @param count  how many creatures, the upcast multiplier included
     * @param maxCr  the highest challenge rating, or null for no limit
     * @param label  the option in words
     */
    public record Option(String id, int count, Double maxCr, String label) {
    }

    /** One hour, in rounds of six seconds. */
    public static final int ONE_HOUR = 600;

    /** Eight hours, in rounds. */
    public static final int EIGHT_HOURS = 4800;

    /** The fifteen forms of Find Familiar (PHB p.240), as the Monster Manual names them. */
    public static final List<String> FAMILIAR_FORMS = List.of("Bat", "Cat", "Crab", "Frog", "Hawk", "Lizard", "Octopus",
            "Owl", "Poisonous Snake", "Quipper", "Rat", "Raven", "Sea Horse", "Spider", "Weasel");

    /** The forms Pact of the Chain adds (PHB p.107). */
    public static final List<String> PACT_OF_THE_CHAIN_FORMS = List.of("Imp", "Pseudodragon", "Quasit", "Sprite");

    /** Find Steed's forms (PHB p.240). */
    public static final List<String> STEED_FORMS = List.of("Warhorse", "Pony", "Camel", "Elk", "Mastiff");

    /** Animate Dead's undead (PHB p.212). */
    public static final List<String> ANIMATE_DEAD_FORMS = List.of("Skeleton", "Zombie");

    /** Tiny Servant's creature (XGE p.169). */
    public static final String TINY_SERVANT = "Tiny Servant";

    /** The types a familiar or steed may be (PHB p.240). */
    public static final List<String> CELESTIAL_FEY_FIEND = List.of("celestial", "fey", "fiend");

    /** The Intelligence a steed has at least (PHB p.240). */
    public static final int STEED_MINIMUM_INTELLIGENCE = 6;

    /** The Pact Boon that adds familiar forms (PHB p.107). */
    public static final String PACT_OF_THE_CHAIN = "Pact of the Chain";

    /**
     * The books whose creatures a newer book reprints under the same name: Mordenkainen Presents: Monsters of the
     * Multiverse updates Volo's Guide to Monsters' and Mordenkainen's Tome of Foes', such as the Maw Demon and the Ox.
     */
    public static final Map<String, Set<String>> REPRINTS = Map.of("MPMM", Set.of("VGM", "MTF"));

    private static final List<InitiativeMode> GROUP_ONLY = List.of(InitiativeMode.GROUP);

    private static final Map<String, Spell> SPELLS = Stream.of(
            new Spell("Conjure Animals", Kind.CONJURE_ANIMALS, 3, true, ONE_HOUR, true, false, true, false, false,
                    List.of(InitiativeMode.GROUP, InitiativeMode.FOLLOWS_CASTER),
                    List.of(InitiativeMode.FOLLOWS_CASTER), Controller.CASTER, "fey",
                    List.of(), "PHB", 225),
            new Spell("Summon Lesser Demons", Kind.LESSER_DEMONS, 3, true, ONE_HOUR, true, true, true, false, false,
                    GROUP_ONLY, List.of(), Controller.DM, null, List.of(), "XGE", 167),
            spirit("Summon Beast", 2, 109),
            spirit("Summon Fey", 3, 112),
            spirit("Summon Shadowspawn", 3, 113),
            spirit("Summon Undead", 3, 114),
            new Spell("Find Familiar", Kind.FAMILIAR, 1, false, null, false, false, true, true, false,
                    List.of(InitiativeMode.INDIVIDUAL), List.of(), Controller.CASTER, null, CELESTIAL_FEY_FIEND,
                    "PHB", 240),
            new Spell("Flock of Familiars", Kind.FLOCK_OF_FAMILIARS, 2, true, ONE_HOUR, false, false, true, true,
                    false, List.of(InitiativeMode.INDIVIDUAL), List.of(), Controller.CASTER, null,
                    CELESTIAL_FEY_FIEND, "LLK", 57),
            new Spell("Find Steed", Kind.STEED, 2, false, null, false, false, true, false, false,
                    List.of(InitiativeMode.INDIVIDUAL, InitiativeMode.FOLLOWS_CASTER), List.of(), Controller.CASTER,
                    null, CELESTIAL_FEY_FIEND, "PHB", 240),
            new Spell("Animate Dead", Kind.ANIMATE_DEAD, 3, false, null, false, false, false, false, true,
                    GROUP_ONLY, List.of(), Controller.CASTER, null,
                    List.of(), "PHB", 212),
            new Spell("Tiny Servant", Kind.TINY_SERVANT, 3, false, EIGHT_HOURS, false, false, false, false, true,
                    GROUP_ONLY, List.of(), Controller.CASTER, null,
                    List.of(), "XGE", 168))
            .collect(Collectors.toUnmodifiableMap(spell -> spell.name().toLowerCase(Locale.ROOT), spell -> spell));

    private SummonRules() {}

    private static Spell spirit(String name, int level, int page) {
        return new Spell(name, Kind.SPIRIT, level, true, ONE_HOUR, true, false, true, false, false,
                List.of(InitiativeMode.FOLLOWS_CASTER), List.of(), Controller.CASTER, null, List.of(), "TCE", page);
    }

    /** The summoning spell of that name, in any case; empty for any other spell. */
    public static Optional<Spell> spell(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(SPELLS.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** Every summoning spell. */
    public static List<Spell> spells() {
        return SPELLS.values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    /**
     * How many times over the creatures come at this slot level: Conjure Animals ×2 at 5th, ×3 at 7th, ×4 at 9th (PHB
     * p.225); Summon Lesser Demons ×2 at 6th or 7th, ×3 at 8th or 9th (XGE p.167); 1 for anything else.
     */
    public static int multiplier(Spell spell, int slotLevel) {
        return switch (spell.kind()) {
            case CONJURE_ANIMALS -> slotLevel >= 9 ? 4 : slotLevel >= 7 ? 3 : slotLevel >= 5 ? 2 : 1;
            case LESSER_DEMONS -> slotLevel >= 8 ? 3 : slotLevel >= 6 ? 2 : 1;
            default -> 1;
        };
    }

    /**
     * What a casting at this slot level can bring. Conjure Animals and Summon Lesser Demons offer their tiers times the
     * multiplier; the others one option of their count.
     *
     * @param hasFamiliar whether the caster has a familiar already, which costs Flock of Familiars one (LLK p.57)
     */
    public static List<Option> options(Spell spell, int slotLevel, boolean hasFamiliar) {
        int above = Math.max(0, slotLevel - spell.level());
        int times = multiplier(spell, slotLevel);
        return switch (spell.kind()) {
            case CONJURE_ANIMALS -> List.of(
                    tier(1 * times, 2.0, "beasts"), tier(2 * times, 1.0, "beasts"), tier(4 * times, 0.5, "beasts"),
                    tier(8 * times, 0.25, "beasts"));
            case LESSER_DEMONS -> List.of(
                    tier(2 * times, 1.0, "demons"), tier(4 * times, 0.5, "demons"), tier(8 * times, 0.25, "demons"));
            case FLOCK_OF_FAMILIARS -> List.of(single(Math.max(0, 3 + above - (hasFamiliar ? 1 : 0)), "familiars"));
            case ANIMATE_DEAD, TINY_SERVANT -> List.of(single(1 + 2 * above,
                    spell.kind() == Kind.ANIMATE_DEAD ? "undead" : "tiny servants"));
            case FAMILIAR -> List.of(single(1, "familiars"));
            case STEED -> List.of(single(1, "steeds"));
            case SPIRIT -> List.of(single(1, "spirits"));
        };
    }

    /**
     * Summon Lesser Demons' table (XGE p.167): 1–2 two demons of CR 1 or lower, 3–4 four of CR 1/2, 5–6 eight of CR
     * 1/4, times the slot's multiplier.
     */
    public static Option lesserDemons(int d6, int slotLevel) {
        if (d6 < 1 || d6 > 6) {
            throw new IllegalArgumentException("A d6 rolls 1 to 6, not " + d6);
        }
        Spell spell = spell("Summon Lesser Demons").orElseThrow();
        List<Option> tiers = options(spell, slotLevel, false);
        return tiers.get((d6 - 1) / 2);
    }

    /**
     * Whether a creature from this book replaces one of the same name from that one, the newer printing over the
     * older ({@link #REPRINTS}), so a list offers each creature once.
     */
    public static boolean reprints(String source, String other) {
        return source != null && other != null
                && REPRINTS.getOrDefault(source.trim(), Set.of()).contains(other.trim());
    }

    /** The option of that ID at this slot level. */
    public static Optional<Option> option(Spell spell, int slotLevel, boolean hasFamiliar, String id) {
        return options(spell, slotLevel, hasFamiliar).stream().filter(option -> option.id().equals(id)).findFirst();
    }

    /**
     * Why a creature doesn't fit the spell's option, or empty when it does: its challenge rating, read as
     * {@link BeastShapes#challengeRating} reads it, must be within the option's, and its type and name the spell's.
     * Conjure Animals takes beasts that aren't swarms (the 5etools filter on PHB p.225 leaves swarms out, and a swarm
     * is many creatures, not one), Summon Lesser Demons demons (fiends of the demon kind).
     *
     * @param type    the creature's type, such as "beast"
     * @param subtype its subtype, such as "demon"
     */
    public static Optional<String> refusal(Spell spell, Option option, String name, String challengeRating,
                                           String type, String subtype, boolean pactOfTheChain) {
        String lowerType = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        String lowerSubtype = subtype != null ? subtype.toLowerCase(Locale.ROOT) : "";
        switch (spell.kind()) {
            case CONJURE_ANIMALS -> {
                if (!lowerType.equals("beast") || name.toLowerCase(Locale.ROOT).startsWith("swarm of")) {
                    return Optional.of(name + " isn't a beast Conjure Animals can summon");
                }
            }
            case LESSER_DEMONS -> {
                if (!lowerSubtype.contains("demon") && !lowerType.equals("demon")) {
                    return Optional.of(name + " isn't a demon");
                }
            }
            case FAMILIAR, FLOCK_OF_FAMILIARS -> {
                if (!named(FAMILIAR_FORMS, name) && !(pactOfTheChain && spell.kind() == Kind.FAMILIAR
                        && named(PACT_OF_THE_CHAIN_FORMS, name))) {
                    return Optional.of(name + " isn't one of " + spell.name() + "'s forms");
                }
            }
            case STEED -> {
                if (!named(STEED_FORMS, name)) {
                    return Optional.of(name + " isn't one of Find Steed's forms");
                }
            }
            case ANIMATE_DEAD -> {
                if (!named(ANIMATE_DEAD_FORMS, name)) {
                    return Optional.of("Animate Dead raises a skeleton or a zombie, not " + name);
                }
            }
            case TINY_SERVANT -> {
                if (!TINY_SERVANT.equalsIgnoreCase(name)) {
                    return Optional.of("Tiny Servant animates a tiny servant, not " + name);
                }
            }
            case SPIRIT -> {
                return Optional.of(spell.name() + " summons its own spirit");
            }
        }
        if (option.maxCr() != null) {
            double rating = BeastShapes.challengeRating(challengeRating).orElse(Double.MAX_VALUE);
            if (rating > option.maxCr()) {
                return Optional.of(name + " (CR " + challengeRating + ") is above challenge rating "
                        + crText(option.maxCr()));
            }
        }
        return Optional.empty();
    }

    /**
     * Why the number of creatures chosen doesn't fit the option, or empty: exactly its count, or for Animate Dead and
     * Tiny Servant from one up to it.
     */
    public static Optional<String> countRefusal(Spell spell, Option option, int chosen) {
        if (chosen < 1) {
            return Optional.of("Choose at least one creature");
        }
        if (spell.fewerAllowed() ? chosen > option.count() : chosen != option.count()) {
            return Optional.of(spell.name() + " brings " + (spell.fewerAllowed() ? "up to " : "") + option.count()
                    + " creature" + (option.count() != 1 ? "s" : "") + " with that option, but " + chosen
                    + " were chosen");
        }
        return Optional.empty();
    }

    /** A challenge rating as the books write it: "1/4", "1/2" or a whole number. */
    public static String crText(double rating) {
        if (rating == 0.125) return "1/8";
        if (rating == 0.25) return "1/4";
        if (rating == 0.5) return "1/2";
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }

    /** The types the creatures may count as: the spell's choices, or its one type, or none. */
    public static Optional<String> refusalOfType(Spell spell, String chosenType) {
        if (chosenType == null || chosenType.isBlank()) {
            return Optional.empty();
        }
        if (spell.typeChoices().stream().noneMatch(type -> type.equalsIgnoreCase(chosenType.trim()))) {
            return Optional.of(spell.name() + "'s creatures can't be " + chosenType.trim()
                    + (spell.typeChoices().isEmpty() ? "" : "; choose " + String.join(", ", spell.typeChoices())));
        }
        return Optional.empty();
    }

    private static Option tier(int count, double maxCr, String what) {
        String cr = crText(maxCr);
        return new Option(count + "xCR" + cr, count, maxCr, count + " " + counted(count, what)
                + " of challenge rating " + cr + " or lower");
    }

    private static Option single(int count, String what) {
        return new Option(String.valueOf(count), count, null, count + " " + counted(count, what));
    }

    // The plural noun, or its singular for one: "1 beast", "2 beasts", "1 tiny servant"; "undead" is both
    private static String counted(int count, String plural) {
        return count == 1 && plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
    }

    private static boolean named(List<String> names, String name) {
        return name != null && names.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(name.trim()));
    }
}
