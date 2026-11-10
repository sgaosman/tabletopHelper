package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Channel Divinity under the 2014 PHB (M17): the cleric's (PHB p.58–59) and the paladin's (PHB p.85) options, their
 * tables and the markers some of them leave among a creature's conditions. Each option is a use of the class's Channel
 * Divinity pool, and the class's subclass decides which options it has beyond Turn Undead.
 * <p>
 * The save DC is the class's spell save DC, 8 + the proficiency bonus + the spellcasting ability's modifier (PHB
 * p.205): Wisdom for a cleric (PHB p.58), Charisma for a paladin (PHB p.84).
 */
public final class ChannelDivinity {

    public static final String CLERIC = "Cleric";
    public static final String PALADIN = "Paladin";

    /** A turned creature's entry (PHB p.59): it flees the one who turned it for 1 minute or until it takes damage. */
    public static final String TURNED = "turned";

    /** Sacred Weapon's entry on the paladin (PHB p.86): its Charisma modifier added to weapon attack rolls. */
    public static final String SACRED_WEAPON = "sacred weapon";

    /** Vow of Enmity's entry on the creature vowed against (PHB p.88): the paladin attacks it with advantage. */
    public static final String VOW_OF_ENMITY = "vow of enmity";

    /** War God's Blessing's entry on the creature blessed (PHB p.63): +10 to its next attack roll. */
    public static final String WAR_GODS_BLESSING = "war god's blessing";

    /** Guided Strike's and War God's Blessing's bonus to an attack roll (PHB p.63). */
    public static final int ATTACK_BONUS = 10;

    /** Turn Undead and the options like it last 1 minute (PHB p.59): ten rounds. */
    public static final int MINUTE_ROUNDS = 10;

    /** Preserve Life heals five times the cleric's level (PHB p.60). */
    public static final int PRESERVE_LIFE_PER_LEVEL = 5;

    /**
     * The entry Abjure Enemy leaves on a creature that failed its save, beside its frightened (PHB p.88, D199): its
     * speed is 0, and it can't benefit from any bonus to it, for as long as the fear lasts, 1 minute or until it takes
     * damage.
     */
    public static final String ABJURED = "abjured";

    /**
     * The entry Abjure Enemy leaves on a creature that succeeded on its save (PHB p.88, D199): its speed is halved for 1
     * minute or until it takes damage.
     */
    public static final String ABJURED_SLOWED = "abjured (half speed)";

    // A trait that keeps turning off its creature (D199): Turn Immunity's "immune to effects that turn undead" (the
    // revenant's, MM p.259, and the demilich's, MM p.48) or "features that turn undead" (the avatar of death's, DMG
    // p.164), and Lord Soth's Marshal Undead, "unless Soth is incapacitated" (DSotDQ)
    private static final Pattern TURN_IMMUNITY = Pattern.compile(
            "immune to (?:effects|features) that turn undead", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNLESS_INCAPACITATED = Pattern.compile("unless [^.]*incapacitated",
            Pattern.CASE_INSENSITIVE);

    // A stat block trait that gives advantage on saves against turning, such as a vampire spawn's or a mummy lord's
    private static final List<String> TURN_RESISTANCE_TRAITS = List.of("turn resistance", "turn defiance");

    // The creature types Preserve Life has no effect on (PHB p.60)
    private static final Set<String> NOT_PRESERVED = Set.of("undead", "construct");

    /** What an option does, which decides how the use-pool command carries it out. */
    public enum Kind {
        /** Each creature of the option's types that the user picks makes a Wisdom save or is turned. */
        TURN,
        /** Healing split among the creatures picked (Preserve Life). */
        PRESERVE_LIFE,
        /** A Constitution save against radiant damage by each creature picked (Radiance of the Dawn). */
        RADIANCE,
        /** Beasts and plants picked make a Wisdom save or are charmed (Charm Animals and Plants). */
        CHARM,
        /** One creature makes a Strength or Dexterity save or is restrained (Nature's Wrath). */
        RESTRAIN,
        /** One creature makes a Wisdom save or is frightened (Abjure Enemy). */
        FRIGHTEN,
        /** The user turns invisible (Cloak of Shadows). */
        INVISIBLE,
        /** A marker on the user (Sacred Weapon). */
        SELF_MARKER,
        /** A marker on one creature (Vow of Enmity, War God's Blessing). */
        TARGET_MARKER,
        /** Chosen on the attack itself, not here (Guided Strike). */
        ON_ATTACK,
        /** Logged for the DM, with no rules for the engine to apply. */
        LOG
    }

    /**
     * A Channel Divinity option.
     *
     * @param id        what the use-pool command's option names
     * @param label     its name
     * @param className the class whose pool it spends
     * @param subclass  a word the subclass's name contains, such as "Life" for the Life Domain; null for every subclass
     * @param level     the class level it needs
     * @param cost      what it costs from the turn
     * @param kind      what it does
     * @param page      its page in the PHB
     * @param text      what it does, for the log
     */
    public enum Option {
        TURN_UNDEAD("turn-undead", "Turn Undead", CLERIC, null, 2, ActionCost.ACTION, Kind.TURN, 59,
                "each undead that fails a Wisdom save is turned for 1 minute or until it takes damage"),
        PRESERVE_LIFE("preserve-life", "Preserve Life", CLERIC, "Life", 2, ActionCost.ACTION, Kind.PRESERVE_LIFE, 60,
                "restores hit points split among creatures within 30 feet, none above half its hit point maximum"),
        RADIANCE_OF_THE_DAWN("radiance-of-the-dawn", "Radiance of the Dawn", CLERIC, "Light", 2, ActionCost.ACTION,
                Kind.RADIANCE, 61, "dispels magical darkness within 30 feet; each hostile creature within 30 feet makes "
                + "a Constitution save, taking 2d10 + cleric level radiant damage, half on a success"),
        CHARM_ANIMALS_AND_PLANTS("charm-animals-and-plants", "Charm Animals and Plants", CLERIC, "Nature", 2,
                ActionCost.ACTION, Kind.CHARM, 62, "each beast or plant creature that fails a Wisdom save is charmed "
                + "for 1 minute or until it takes damage, friendly to the cleric and its allies"),
        DESTRUCTIVE_WRATH("destructive-wrath", "Destructive Wrath", CLERIC, "Tempest", 2, ActionCost.FREE, Kind.LOG,
                62, "deals maximum damage with its lightning or thunder damage roll instead of rolling; the DM sets "
                + "the damage to the dice's maximum"),
        KNOWLEDGE_OF_THE_AGES("knowledge-of-the-ages", "Knowledge of the Ages", CLERIC, "Knowledge", 2,
                ActionCost.ACTION, Kind.LOG, 59, "gains proficiency with one skill or tool of its choice for 10 "
                + "minutes"),
        READ_THOUGHTS("read-thoughts", "Read Thoughts", CLERIC, "Knowledge", 6, ActionCost.ACTION, Kind.LOG, 59,
                "targets a creature within 60 feet, which makes a Wisdom save or has its surface thoughts read for 1 "
                + "minute (the DM rolls and rules)"),
        INVOKE_DUPLICITY("invoke-duplicity", "Invoke Duplicity", CLERIC, "Trickery", 2, ActionCost.ACTION, Kind.LOG,
                63, "creates an illusory duplicate within 30 feet for 1 minute, concentrating as on a spell, and has "
                + "advantage on attack rolls against creatures within 5 feet of both"),
        CLOAK_OF_SHADOWS("cloak-of-shadows", "Cloak of Shadows", CLERIC, "Trickery", 6, ActionCost.ACTION,
                Kind.INVISIBLE, 63, "becomes invisible until the end of its next turn, or until it attacks or casts a "
                + "spell"),
        GUIDED_STRIKE("guided-strike", "Guided Strike", CLERIC, "War", 2, ActionCost.FREE, Kind.ON_ATTACK, 63,
                "+10 to an attack roll, chosen on the attack"),
        WAR_GODS_BLESSING("war-gods-blessing", "War God's Blessing", CLERIC, "War", 6, ActionCost.REACTION,
                Kind.TARGET_MARKER, 63, "gives a creature within 30 feet +10 to its attack roll"),
        SACRED_WEAPON("sacred-weapon", "Sacred Weapon", PALADIN, "Devotion", 3, ActionCost.ACTION, Kind.SELF_MARKER,
                86, "adds its Charisma modifier (at least +1) to attack rolls with its weapon for 1 minute; the weapon "
                + "sheds bright light"),
        TURN_THE_UNHOLY("turn-the-unholy", "Turn the Unholy", PALADIN, "Devotion", 3, ActionCost.ACTION, Kind.TURN,
                86, "each fiend or undead that fails a Wisdom save is turned for 1 minute or until it takes damage"),
        NATURES_WRATH("natures-wrath", "Nature's Wrath", PALADIN, "Ancients", 3, ActionCost.ACTION, Kind.RESTRAIN,
                87, "spectral vines reach for a creature within 10 feet, which makes a Strength or Dexterity save (its "
                + "choice) or is restrained, repeating the save at the end of each of its turns"),
        TURN_THE_FAITHLESS("turn-the-faithless", "Turn the Faithless", PALADIN, "Ancients", 3, ActionCost.ACTION,
                Kind.TURN, 87, "each fey or fiend that fails a Wisdom save is turned for 1 minute or until it takes "
                + "damage; one hiding its true form in an illusion shows it"),
        ABJURE_ENEMY("abjure-enemy", "Abjure Enemy", PALADIN, "Vengeance", 3, ActionCost.ACTION, Kind.FRIGHTEN, 88,
                "a creature within 60 feet makes a Wisdom save, with disadvantage for a fiend or undead, or is "
                + "frightened with speed 0 for 1 minute or until it takes damage; on a success its speed is halved for "
                + "1 minute or until it takes damage"),
        VOW_OF_ENMITY("vow-of-enmity", "Vow of Enmity", PALADIN, "Vengeance", 3, ActionCost.BONUS_ACTION,
                Kind.TARGET_MARKER, 88, "gains advantage on attack rolls against a creature within 10 feet for 1 "
                + "minute or until it drops to 0 hit points");

        private final String id;
        private final String label;
        private final String className;
        private final String subclass;
        private final int level;
        private final ActionCost cost;
        private final Kind kind;
        private final int page;
        private final String text;

        Option(String id, String label, String className, String subclass, int level, ActionCost cost, Kind kind,
               int page, String text) {
            this.id = id;
            this.label = label;
            this.className = className;
            this.subclass = subclass;
            this.level = level;
            this.cost = cost;
            this.kind = kind;
            this.page = page;
            this.text = text;
        }

        public String id() { return id; }
        public String label() { return label; }
        public String className() { return className; }
        public String subclass() { return subclass; }
        public int level() { return level; }
        public ActionCost cost() { return cost; }
        public Kind kind() { return kind; }
        public int page() { return page; }
        public String text() { return text; }

        /** Whether a creature of the class at this level, with this subclass, has the option. */
        public boolean availableTo(String characterClass, String subclassName, int classLevel) {
            return className.equalsIgnoreCase(characterClass) && classLevel >= level
                    && (subclass == null || subclassMatches(subclassName, subclass));
        }

        /** The creature types a turning option affects (PHB p.59, p.86, p.87); empty for the others. */
        public Set<String> turnedTypes() {
            return switch (this) {
                case TURN_UNDEAD -> Set.of("undead");
                case TURN_THE_UNHOLY -> Set.of("fiend", "undead");
                case TURN_THE_FAITHLESS -> Set.of("fey", "fiend");
                case CHARM_ANIMALS_AND_PLANTS -> Set.of("beast", "plant");
                default -> Set.of();
            };
        }
    }

    private ChannelDivinity() {}

    /** The option the use-pool command names, in any case. */
    public static Optional<Option> option(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(Option.values()).filter(option -> option.id().equals(wanted)).findFirst();
    }

    /** The options a creature of the class at this level, with this subclass, has, in the order they are listed. */
    public static List<Option> available(String characterClass, String subclassName, int classLevel) {
        return Arrays.stream(Option.values())
                .filter(option -> option.availableTo(characterClass, subclassName, classLevel))
                .toList();
    }

    /** Whether a subclass's name, such as "Life Domain" or "Oath of Devotion", has the word, in any case. */
    public static boolean subclassMatches(String subclassName, String word) {
        return subclassName != null && word != null
                && subclassName.toLowerCase(Locale.ROOT).matches(".*\\b" + word.toLowerCase(Locale.ROOT) + "\\b.*");
    }

    /** The ability the class's Channel Divinity save DC uses: Wisdom for a cleric, Charisma for a paladin. */
    public static Ability saveAbility(String className) {
        return PALADIN.equalsIgnoreCase(className) ? Ability.CHA : Ability.WIS;
    }

    /** A spell save DC: 8 + the proficiency bonus + the ability's modifier (PHB p.205). */
    public static int saveDc(int proficiencyBonus, int abilityScore) {
        return 8 + proficiencyBonus + Ability.modifier(abilityScore);
    }

    /** Whether a creature type, such as "undead" or "celestial or fiend", is one of the types. */
    public static boolean ofType(String creatureType, Set<String> types) {
        if (creatureType == null || types.isEmpty()) {
            return false;
        }
        String type = creatureType.toLowerCase(Locale.ROOT);
        return types.stream().anyMatch(wanted -> type.matches(".*\\b" + wanted + "s?\\b.*"));
    }

    /**
     * The highest challenge rating Destroy Undead destroys at the cleric's level (PHB p.59): 1/2 from 5th, 1 from 8th,
     * 2 from 11th, 3 from 14th and 4 from 17th; empty below 5th.
     */
    public static OptionalDouble destroyUndeadRating(int clericLevel) {
        if (clericLevel >= 17) return OptionalDouble.of(4);
        if (clericLevel >= 14) return OptionalDouble.of(3);
        if (clericLevel >= 11) return OptionalDouble.of(2);
        if (clericLevel >= 8) return OptionalDouble.of(1);
        if (clericLevel >= 5) return OptionalDouble.of(0.5);
        return OptionalDouble.empty();
    }

    /** A challenge rating such as "1/2", "1/8" or "5" as a number; empty when unreadable. */
    public static OptionalDouble challengeRating(String rating) {
        if (rating == null) {
            return OptionalDouble.empty();
        }
        String text = rating.trim();
        try {
            int slash = text.indexOf('/');
            if (slash > 0) {
                return OptionalDouble.of(Double.parseDouble(text.substring(0, slash).trim())
                        / Double.parseDouble(text.substring(slash + 1).trim()));
            }
            return OptionalDouble.of(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Whether Destroy Undead destroys a creature of this rating that failed its save against Turn Undead (PHB p.59): a
     * cleric of 5th level or higher destroys undead of the rating its level gives or lower. An unreadable rating isn't
     * destroyed.
     */
    public static boolean destroys(int clericLevel, String rating) {
        OptionalDouble highest = destroyUndeadRating(clericLevel);
        OptionalDouble cr = challengeRating(rating);
        return highest.isPresent() && cr.isPresent() && cr.getAsDouble() <= highest.getAsDouble();
    }

    /**
     * Whether a trait's words make its creature immune to being turned (D199), as Turn Immunity's "immune to effects
     * that turn undead" (MM p.259): such a creature is left out of Turn Undead, Turn the Unholy and Destroy Undead. One
     * that holds "unless" the creature is incapacitated, as Lord Soth's Marshal Undead, doesn't while it is.
     */
    public static boolean immuneToTurning(String traitWords, boolean incapacitated) {
        if (traitWords == null || !TURN_IMMUNITY.matcher(traitWords).find()) {
            return false;
        }
        return !(incapacitated && UNLESS_INCAPACITATED.matcher(traitWords).find());
    }

    /** Whether a stat block's traits give advantage on saves against turning, such as Turn Resistance. */
    public static boolean resistsTurning(String traits) {
        if (traits == null) {
            return false;
        }
        String text = traits.toLowerCase(Locale.ROOT);
        return TURN_RESISTANCE_TRAITS.stream().anyMatch(text::contains);
    }

    /** Radiance of the Dawn's damage (PHB p.61): 2d10 + the cleric's level. */
    public static Dice radianceOfTheDawn(int clericLevel) {
        return new Dice(2, 10, clericLevel);
    }

    /** Sacred Weapon's bonus to attack rolls (PHB p.86): the Charisma modifier, at least +1. */
    public static int sacredWeaponBonus(int charismaScore) {
        return Math.max(1, Ability.modifier(charismaScore));
    }

    /**
     * Why Preserve Life can't heal a creature the amount, or empty (PHB p.60): it has no effect on undead and
     * constructs, and it can't bring a creature above half its hit point maximum.
     *
     * @param name the creature's name, for the reason
     * @param type its creature type; null for a player character
     */
    public static Optional<String> preserveLifeRefusal(String name, String type, int hp, int maxHp, int amount) {
        if (ofType(type, NOT_PRESERVED)) {
            return Optional.of("Preserve Life has no effect on undead or constructs, and " + name + " is one");
        }
        int ceiling = maxHp / 2;
        if (hp + amount > ceiling) {
            return Optional.of("Preserve Life can't restore " + name + " above half its hit point maximum ("
                    + ceiling + " HP): it has " + hp + ", so at most " + Math.max(0, ceiling - hp));
        }
        return Optional.empty();
    }
}
