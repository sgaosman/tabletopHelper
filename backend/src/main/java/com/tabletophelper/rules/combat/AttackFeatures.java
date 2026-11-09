package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The class features that change a weapon attack, under the 2014 PHB (M17 workstream B). {@link WeaponRules} works out
 * a weapon's plain numbers; these are what a character's features add to them, before the roll or after a hit.
 * <ul>
 *   <li>Fighting Styles (PHB p.72, p.84, p.91), chosen as the features "Fighting Style: X" (D159): Archery, +2 to attack
 *       rolls with ranged weapons; Dueling, +2 damage with a melee weapon held in one hand and no other weapon; Great
 *       Weapon Fighting, a 1 or 2 on a damage die of a two-handed or versatile melee weapon held in two hands is
 *       rolled again; Two-Weapon Fighting, the ability modifier added to an off-hand attack's damage. Defense (+1 AC in
 *       armour) is rules/character/ArmourClass's, and Protection a reaction the DM applies.</li>
 *   <li>Martial Arts (Monk 1, PHB p.78): unarmed strikes and monk weapons may use DEX, and deal the martial arts die
 *       in place of their own damage when it is higher, while the monk wears no armour and carries no shield.</li>
 *   <li>Improved Critical (Champion 3, PHB p.72) and Superior Critical (Champion 15, PHB p.73): weapon attacks score a
 *       critical hit on 19-20, then 18-20.</li>
 *   <li>Sneak Attack (Rogue 1, PHB p.96): once per turn, extra damage on a hit with a finesse or ranged weapon that had
 *       advantage, or whose target has another enemy of it within 5 feet, when the attack hasn't disadvantage.</li>
 *   <li>Divine Smite (Paladin 2, PHB p.85): after a melee weapon attack's hit, an unarmed strike's included (PHB p.195),
 *       a spell slot for 2d8 radiant damage and 1d8 for each slot level above 1st, to 5d8, and 1d8 more against an
 *       undead or a fiend.</li>
 *   <li>Stunning Strike (Monk 5, PHB p.79): after a melee weapon hit, 1 ki point for a Constitution save against the
 *       ki save DC, stunned until the end of the monk's next turn on a failure.</li>
 * </ul>
 * A critical hit doubles every damage die these add, Sneak Attack's and Divine Smite's too, and never a flat bonus such
 * as Dueling's (PHB p.196).
 */
public final class AttackFeatures {

    /** What Archery adds to attack rolls with ranged weapons (PHB p.72). */
    public static final int ARCHERY_BONUS = 2;
    /** What Dueling adds to damage with a melee weapon in one hand (PHB p.72). */
    public static final int DUELING_BONUS = 2;
    /** The die faces Great Weapon Fighting rolls again (PHB p.72). */
    public static final int GREAT_WEAPON_FIGHTING_REROLL = 2;

    /** The Monk level of Flurry of Blows, which spends ki (PHB p.78). */
    public static final int FLURRY_OF_BLOWS_LEVEL = 2;
    /** The strikes Flurry of Blows gives (PHB p.78). */
    public static final int FLURRY_OF_BLOWS_STRIKES = 2;
    /** The Monk level of Stunning Strike (PHB p.79). */
    public static final int STUNNING_STRIKE_LEVEL = 5;
    /** The condition Stunning Strike gives on a failed save (PHB p.79). */
    public static final String STUNNED = "stunned";
    /** The Paladin level of Divine Smite (PHB p.85). */
    public static final int DIVINE_SMITE_LEVEL = 2;
    /** Divine Smite's damage type (PHB p.85). */
    public static final String RADIANT = "radiant";
    /** The most d8s Divine Smite deals before the bonus against undead and fiends (PHB p.85). */
    public static final int DIVINE_SMITE_MOST_DICE = 5;
    /** The Champion level of Improved Critical (PHB p.72). */
    public static final int IMPROVED_CRITICAL_LEVEL = 3;
    /** The Champion level of Superior Critical (PHB p.73). */
    public static final int SUPERIOR_CRITICAL_LEVEL = 15;

    private static final String SHORTSWORD = "shortsword";

    private AttackFeatures() {}

    /** The Fighting Styles of the PHB's Fighter, Paladin and Ranger (PHB p.72, p.84, p.91), by their names. */
    public enum FightingStyle {
        ARCHERY("Archery"),
        DEFENSE("Defense"),
        DUELING("Dueling"),
        GREAT_WEAPON_FIGHTING("Great Weapon Fighting"),
        PROTECTION("Protection"),
        TWO_WEAPON_FIGHTING("Two-Weapon Fighting");

        /** How the feature a chosen style becomes begins (D159), as in "Fighting Style: Archery". */
        public static final String FEATURE_PREFIX = "Fighting Style: ";

        private final String styleName;

        FightingStyle(String styleName) {
            this.styleName = styleName;
        }

        public String styleName() {
            return styleName;
        }

        /** The feature the style is chosen as, such as "Fighting Style: Archery". */
        public String featureName() {
            return FEATURE_PREFIX + styleName;
        }

        /** The styles among a character's feature names, in any case; other features and styles are ignored. */
        public static Set<FightingStyle> of(Collection<String> featureNames) {
            Set<FightingStyle> styles = EnumSet.noneOf(FightingStyle.class);
            for (String name : featureNames) {
                if (name == null) continue;
                String feature = name.trim();
                for (FightingStyle style : values()) {
                    if (feature.equalsIgnoreCase(style.featureName())) {
                        styles.add(style);
                    }
                }
            }
            return styles;
        }
    }

    /**
     * How the attacker holds its weapons for the attack. There is no hand tracking, so the attack says it.
     * <ul>
     *   <li>ONE_HANDED: the weapon in one hand and no other weapon, as Dueling needs.</li>
     *   <li>TWO_HANDED: the weapon in both hands, as a two-handed weapon needs and a versatile one may be (PHB
     *       p.147).</li>
     *   <li>TWO_WEAPONS: a weapon in each hand, as two-weapon fighting has (PHB p.195).</li>
     * </ul>
     */
    public enum Grip {
        ONE_HANDED,
        TWO_HANDED,
        TWO_WEAPONS
    }

    /**
     * The attacker as its weapon attacks see it.
     *
     * @param strength         its STR score
     * @param dexterity        its DEX score
     * @param proficiencyBonus its proficiency bonus
     * @param styles           its Fighting Styles
     * @param martialArtsLevel its Monk level while Martial Arts applies (no armour, no shield); 0 otherwise
     * @param critRange        the lowest d20 that is a critical hit for its weapon attacks ({@link #critRange})
     */
    public record Attacker(int strength, int dexterity, int proficiencyBonus, Set<FightingStyle> styles,
                           int martialArtsLevel, int critRange) {

        public Attacker {
            styles = styles.isEmpty() ? EnumSet.noneOf(FightingStyle.class) : EnumSet.copyOf(styles);
        }

        /** An attacker with none of these features. */
        public static Attacker plain(int strength, int dexterity, int proficiencyBonus) {
            return new Attacker(strength, dexterity, proficiencyBonus, Set.of(), 0, AttackRules.CRITICAL_D20);
        }
    }

    /**
     * What an attack with the weapon comes to with the attacker's features.
     *
     * @param ability     the ability it uses, for the attack roll and the damage alike
     * @param attackBonus the attack bonus
     * @param damage      the damage dice and modifier
     * @param rerollUpTo  the die faces its damage dice roll again: 2 with Great Weapon Fighting, otherwise 0
     * @param critRange   the lowest d20 that is a critical hit
     * @param martialArts whether Martial Arts applies to it
     * @param notes       the features that changed it, for the log and the attack options, such as "Archery +2"
     */
    public record Numbers(Ability ability, int attackBonus, Dice damage, int rerollUpTo, int critRange,
                          boolean martialArts, List<String> notes) {

        public Numbers {
            Objects.requireNonNull(ability, "ability");
            Objects.requireNonNull(damage, "damage");
            notes = List.copyOf(notes);
        }
    }

    /** The grip an attack has unless it says otherwise: two hands for a two-handed weapon, two weapons off-hand. */
    public static Grip defaultGrip(WeaponRules.Weapon weapon, boolean offHand) {
        if (offHand) return Grip.TWO_WEAPONS;
        return weapon.twoHanded() ? Grip.TWO_HANDED : Grip.ONE_HANDED;
    }

    /**
     * Why the weapon can't be held that way for the attack, or empty: a two-handed weapon needs both hands (PHB p.147),
     * only a two-handed or versatile weapon is held in two hands, and an off-hand attack has a weapon in each hand (PHB
     * p.195). An unarmed strike takes any grip.
     */
    public static Optional<String> gripRefusal(WeaponRules.Weapon weapon, Grip grip, boolean offHand) {
        if (weapon == WeaponRules.UNARMED_STRIKE) {
            return Optional.empty();
        }
        if (offHand && grip != Grip.TWO_WEAPONS) {
            return Optional.of("An off-hand attack is made with a weapon in each hand");
        }
        if (weapon.twoHanded() && grip != Grip.TWO_HANDED) {
            return Optional.of("The " + weapon.name() + " is two-handed and needs both hands to attack with");
        }
        if (grip == Grip.TWO_HANDED && !weapon.twoHanded() && !weapon.versatile()) {
            return Optional.of("The " + weapon.name() + " is neither two-handed nor versatile, so it isn't wielded in "
                    + "two hands");
        }
        return Optional.empty();
    }

    /**
     * The attack's numbers with the attacker's features: WeaponRules' ability, attack bonus and damage, then Martial
     * Arts' ability and die, a versatile weapon's two-handed damage, Archery, Dueling, Great Weapon Fighting and
     * Two-Weapon Fighting, and the critical range.
     *
     * @param proficient  whether the attacker adds its proficiency bonus
     * @param powerAttack taking Great Weapon Master's or Sharpshooter's trade, which the weapon must allow
     * @param offHand     an off-hand attack (PHB p.195)
     * @param grip        how the attacker holds its weapons, which {@link #gripRefusal} must allow
     * @throws IllegalArgumentException for the trade with a weapon that allows none
     */
    public static Numbers numbers(WeaponRules.Weapon weapon, Attacker attacker, boolean proficient,
                                  boolean powerAttack, boolean offHand, Grip grip) {
        if (powerAttack && WeaponRules.powerAttackFor(weapon).isEmpty()) {
            throw new IllegalArgumentException(weapon.name() + " allows neither Great Weapon Master nor Sharpshooter");
        }
        List<String> notes = new ArrayList<>();
        boolean unarmed = weapon == WeaponRules.UNARMED_STRIKE;
        boolean melee = !weapon.ranged();
        boolean martialArts = attacker.martialArtsLevel() >= 1 && (unarmed || monkWeapon(weapon));

        // Martial Arts lets an unarmed strike or monk weapon use DEX, as a finesse weapon does (PHB p.78)
        Ability ability = martialArts
                ? WeaponRules.ability(withFinesse(weapon), attacker.strength(), attacker.dexterity())
                : WeaponRules.ability(weapon, attacker.strength(), attacker.dexterity());
        int abilityModifier = Ability.modifier(ability == Ability.STR ? attacker.strength() : attacker.dexterity());

        int attackBonus = abilityModifier + (proficient ? attacker.proficiencyBonus() : 0)
                - (powerAttack ? WeaponRules.POWER_ATTACK_PENALTY : 0);
        if (weapon.ranged() && attacker.styles().contains(FightingStyle.ARCHERY)) {
            attackBonus += ARCHERY_BONUS;
            notes.add("Archery +" + ARCHERY_BONUS);
        }

        Dice dice = weapon.damage();
        if (grip == Grip.TWO_HANDED && weapon.versatile()) {
            dice = weapon.versatileDamage();
            notes.add("two-handed");
        }
        if (martialArts) {
            Dice die = martialArtsDie(attacker.martialArtsLevel());
            // The martial arts die in place of the weapon's damage when it is better; an unarmed strike's 1 always is
            if (unarmed || die.average() > dice.average()) {
                dice = die;
            }
            notes.add("Martial Arts");
        }

        boolean twoWeaponFighting = offHand && attacker.styles().contains(FightingStyle.TWO_WEAPON_FIGHTING);
        int modifier = dice.modifier()
                + (offHand && !twoWeaponFighting ? Math.min(0, abilityModifier) : abilityModifier)
                + (powerAttack ? WeaponRules.POWER_ATTACK_DAMAGE : 0);
        if (twoWeaponFighting && abilityModifier > 0) {
            notes.add("Two-Weapon Fighting");
        }
        if (melee && !unarmed && !offHand && grip == Grip.ONE_HANDED
                && attacker.styles().contains(FightingStyle.DUELING)) {
            modifier += DUELING_BONUS;
            notes.add("Dueling +" + DUELING_BONUS);
        }
        int rerollUpTo = 0;
        if (melee && grip == Grip.TWO_HANDED && (weapon.twoHanded() || weapon.versatile())
                && attacker.styles().contains(FightingStyle.GREAT_WEAPON_FIGHTING)) {
            rerollUpTo = GREAT_WEAPON_FIGHTING_REROLL;
            notes.add("Great Weapon Fighting");
        }
        int critRange = attacker.critRange();
        if (critRange < AttackRules.CRITICAL_D20) {
            notes.add("critical on " + critRange + "-20");
        }
        return new Numbers(ability, attackBonus, new Dice(dice.count(), dice.sides(), modifier), rerollUpTo,
                critRange, martialArts, notes);
    }

    // The weapon as a finesse weapon, which attacks with the higher of STR and DEX
    private static WeaponRules.Weapon withFinesse(WeaponRules.Weapon weapon) {
        return new WeaponRules.Weapon(weapon.name(), weapon.category(), weapon.ranged(), true, weapon.heavy(),
                weapon.light(), weapon.damage(), weapon.damageType(), weapon.twoHanded(), weapon.reach(),
                weapon.versatileDamage());
    }

    // ── Martial Arts and ki ──────────────────────────────────────

    /**
     * Whether the weapon is a monk weapon: a shortsword, or a simple melee weapon that is neither two-handed nor heavy
     * (PHB p.78). An unarmed strike isn't a weapon, though Martial Arts covers it too.
     */
    public static boolean monkWeapon(WeaponRules.Weapon weapon) {
        if (weapon == WeaponRules.UNARMED_STRIKE || weapon.ranged()) {
            return false;
        }
        if (SHORTSWORD.equals(weapon.name().trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return weapon.category() == WeaponRules.Category.SIMPLE && !weapon.twoHanded() && !weapon.heavy();
    }

    /**
     * The Martial Arts column of the Monk table (PHB p.77): 1d4 from 1st level, 1d6 from 5th, 1d8 from 11th and 1d10
     * from 17th.
     *
     * @throws IllegalArgumentException for a level outside 1 to 20
     */
    public static Dice martialArtsDie(int monkLevel) {
        requireLevel(monkLevel, "monk");
        int sides = monkLevel >= 17 ? 10 : monkLevel >= 11 ? 8 : monkLevel >= 5 ? 6 : 4;
        return new Dice(1, sides, 0);
    }

    /** The ki save DC: 8 + the proficiency bonus + the WIS modifier (PHB p.78). */
    public static int kiSaveDc(int proficiencyBonus, int wisdom) {
        return 8 + proficiencyBonus + Ability.modifier(wisdom);
    }

    // ── Critical hits ────────────────────────────────────────────

    /**
     * The lowest d20 that is a critical hit for a Champion's weapon attacks: 19 from 3rd level (Improved Critical, PHB
     * p.72) and 18 from 15th (Superior Critical, PHB p.73); 20 for anyone else, a Champion level of 0 included.
     */
    public static int critRange(int championLevel) {
        if (championLevel >= SUPERIOR_CRITICAL_LEVEL) return 18;
        if (championLevel >= IMPROVED_CRITICAL_LEVEL) return 19;
        return AttackRules.CRITICAL_D20;
    }

    // ── Sneak Attack ─────────────────────────────────────────────

    /**
     * Sneak Attack's dice, from the Rogue table (PHB p.95): 1d6 at 1st level and 1d6 more at every odd level, to 10d6
     * at 19th.
     *
     * @throws IllegalArgumentException for a level outside 1 to 20
     */
    public static Dice sneakAttack(int rogueLevel) {
        requireLevel(rogueLevel, "rogue");
        return new Dice((rogueLevel + 1) / 2, 6, 0);
    }

    /** Whether Sneak Attack can come with the weapon: a finesse or a ranged weapon (PHB p.96). */
    public static boolean sneakAttackWeapon(WeaponRules.Weapon weapon) {
        return weapon != WeaponRules.UNARMED_STRIKE && (weapon.finesse() || weapon.ranged());
    }

    /**
     * Whether an eligible attack gets Sneak Attack, from how it is finally rolled once every source of advantage and
     * disadvantage counts (PHB p.96, p.173). Never with disadvantage; always with advantage unless declined; otherwise
     * only when the player claims that another enemy of the target is within 5 feet of it and not incapacitated, which
     * the server can't see. Advantage and disadvantage that cancel leave neither, so the claim decides.
     *
     * @param claim true to claim the ally next to the target, false to decline Sneak Attack, null to leave it to the
     *              roll
     */
    public static boolean sneakAttackApplies(Boolean claim, RollMode finalMode) {
        if (Boolean.FALSE.equals(claim) || finalMode == RollMode.DISADVANTAGE) {
            return false;
        }
        return finalMode == RollMode.ADVANTAGE || Boolean.TRUE.equals(claim);
    }

    // ── Divine Smite ─────────────────────────────────────────────

    /**
     * Divine Smite's radiant damage for a slot of the level (PHB p.85): 2d8, and 1d8 for each level above 1st to 5d8,
     * with 1d8 more against an undead or a fiend. A critical hit doubles it, as it does all of a hit's dice (PHB p.196).
     *
     * @throws IllegalArgumentException for a slot level outside 1 to 9
     */
    public static Dice divineSmite(int slotLevel, boolean undeadOrFiend) {
        if (slotLevel < 1 || slotLevel > 9) {
            throw new IllegalArgumentException("A spell slot is level 1 to 9, not " + slotLevel);
        }
        int dice = Math.min(DIVINE_SMITE_MOST_DICE, slotLevel + 1) + (undeadOrFiend ? 1 : 0);
        return new Dice(dice, 8, 0);
    }

    /** Whether a creature of this type takes Divine Smite's extra 1d8: an undead or a fiend (PHB p.85). */
    public static boolean undeadOrFiend(String creatureType) {
        if (creatureType == null) {
            return false;
        }
        // A stat block's type may offer a choice, such as "celestial or fiend"
        return creatureType.toLowerCase(Locale.ROOT).matches(".*\\b(undead|fiend)\\b.*");
    }

    private static void requireLevel(int level, String className) {
        if (level < 1 || level > 20) {
            throw new IllegalArgumentException("A " + className + " is level 1 to 20, not " + level);
        }
    }
}
