package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The class features a creature turns on for itself and the rules then read (M17), under the 2014 PHB. Like the
 * Dodge action's {@link StandardActions#DODGING}, each one in force is an entry among the creature's conditions that
 * isn't one of the conditions of appendix A: nothing makes a creature immune to it, and only these rules read it.
 * <ul>
 *   <li>Rage (Barbarian 1, PHB p.48): {@link #RAGING} for 1 minute. Advantage on Strength checks and Strength saving
 *       throws, a bonus to the damage of melee weapon attacks using Strength ({@link #rageDamage}), and resistance to
 *       bludgeoning, piercing and slashing damage, none of it in heavy armour. A raging creature can't cast spells or
 *       concentrate on them. Rage ends early when the creature is knocked unconscious, or when its turn ends and it
 *       hasn't attacked a hostile creature since its last turn or taken damage since then; it can also end it on its
 *       turn as a bonus action.</li>
 *   <li>Reckless Attack (Barbarian 2, PHB p.48): {@link #RECKLESS} until the start of the creature's next turn. Chosen
 *       on its first attack of a turn, it gives advantage on melee weapon attack rolls using Strength for the rest of
 *       that turn, and attack rolls against the creature have advantage until its next turn.</li>
 * </ul>
 */
public final class ClassFeatures {

    /** The entry Rage leaves among the barbarian's conditions while it lasts. */
    public static final String RAGING = "raging";

    /** The entry Reckless Attack leaves among the barbarian's conditions until the start of its next turn. */
    public static final String RECKLESS = "reckless";

    /**
     * The entry a Bardic Inspiration die leaves on the creature holding it (PHB p.53), with the die in the entry's
     * {@code heldDie}, for 10 minutes or until it is rolled.
     */
    public static final String INSPIRED = "inspired";

    /** Bardic Inspiration's die lasts 10 minutes (PHB p.53): a hundred rounds. */
    public static final int INSPIRATION_ROUNDS = 100;

    /** The feature that adds to a Life cleric's healing spells (PHB p.60). */
    public static final String DISCIPLE_OF_LIFE = "Disciple of Life";

    /** The feature that lets a barbarian attack recklessly (Barbarian 2, PHB p.48). */
    public static final String RECKLESS_ATTACK = "Reckless Attack";

    /** Rage lasts 1 minute (PHB p.48): ten rounds of 6 seconds (PHB p.189). */
    public static final int RAGE_ROUNDS = 10;

    /** What Lay on Hands spends to cure one disease or neutralize one poison (PHB p.84). */
    public static final int LAY_ON_HANDS_CURE_COST = 5;

    // The damage types Rage resists (PHB p.48)
    private static final Set<String> RAGE_RESISTANCES = Set.of("bludgeoning", "piercing", "slashing");

    // The creature types Lay on Hands has no effect on (PHB p.84)
    private static final Set<String> UNHEALED_BY_LAY_ON_HANDS = Set.of("undead", "construct");

    private ClassFeatures() {}

    /** Whether the creature has a feature's entry of this name among its conditions, such as Sacred Weapon's. */
    public static boolean has(Collection<String> conditions, String entry) {
        return conditions != null && Conditions.has(conditions, entry);
    }

    /** Whether the creature is raging. */
    public static boolean raging(Collection<String> conditions) {
        return conditions != null && Conditions.has(conditions, RAGING);
    }

    /** Whether the creature attacked recklessly this turn or last, so attack rolls against it have advantage. */
    public static boolean reckless(Collection<String> conditions) {
        return conditions != null && Conditions.has(conditions, RECKLESS);
    }

    /**
     * The Rage Damage column of the Barbarian table (PHB p.46): +2 from 1st level, +3 from 9th and +4 from 16th.
     *
     * @throws IllegalArgumentException for a level outside 1 to 20
     */
    public static int rageDamage(int barbarianLevel) {
        if (barbarianLevel < 1 || barbarianLevel > 20) {
            throw new IllegalArgumentException("A barbarian is level 1 to 20, not " + barbarianLevel);
        }
        if (barbarianLevel >= 16) return 4;
        if (barbarianLevel >= 9) return 3;
        return 2;
    }

    /** Whether Rage gives resistance to the damage type: bludgeoning, piercing or slashing (PHB p.48). */
    public static boolean rageResists(String damageType) {
        return damageType != null && RAGE_RESISTANCES.contains(damageType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether an attack is a melee weapon attack using Strength, which Rage adds its damage to and Reckless Attack gives
     * advantage (PHB p.48): a weapon that isn't ranged, attacking with Strength. An unarmed strike is one (PHB p.195).
     */
    public static boolean meleeStrengthAttack(boolean rangedWeapon, Ability ability) {
        return !rangedWeapon && ability == Ability.STR;
    }

    /** Second Wind's healing (Fighter 1, PHB p.72): 1d10 + the fighter's level. */
    public static Dice secondWind(int fighterLevel) {
        if (fighterLevel < 1 || fighterLevel > 20) {
            throw new IllegalArgumentException("A fighter is level 1 to 20, not " + fighterLevel);
        }
        return new Dice(1, 10, fighterLevel);
    }

    /**
     * The Bardic Inspiration die of the bard's level (PHB p.53–54): a d6, a d8 from 5th level, a d10 from 10th and a
     * d12 from 15th; its number of sides.
     */
    public static int bardicInspirationDie(int bardLevel) {
        if (bardLevel < 1 || bardLevel > 20) {
            throw new IllegalArgumentException("A bard is level 1 to 20, not " + bardLevel);
        }
        if (bardLevel >= 15) return 12;
        if (bardLevel >= 10) return 10;
        if (bardLevel >= 5) return 8;
        return 6;
    }

    /**
     * Disciple of Life's extra healing (Life Domain 1, PHB p.60): a spell of 1st level or higher that restores hit
     * points restores 2 + the spell's level more, the level it was cast at. A cantrip gets nothing.
     */
    public static int discipleOfLife(int spellLevel) {
        return spellLevel >= 1 ? 2 + spellLevel : 0;
    }

    /**
     * Why Lay on Hands can't touch a creature of this type, or empty: it has no effect on undead and constructs
     * (PHB p.84). A creature without a type, such as a player character, can be healed.
     */
    public static Optional<String> layOnHandsRefusal(String creatureType) {
        if (creatureType == null) {
            return Optional.empty();
        }
        String type = creatureType.trim().toLowerCase(Locale.ROOT);
        return UNHEALED_BY_LAY_ON_HANDS.contains(type)
                ? Optional.of("Lay on Hands has no effect on " + (type.equals("undead") ? "undead" : "constructs"))
                : Optional.empty();
    }
}
