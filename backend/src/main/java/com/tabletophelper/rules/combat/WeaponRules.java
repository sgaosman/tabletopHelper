package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Weapon attacks under the 2014 rules (D164): the ability a weapon attacks with, the attack bonus and the damage
 * (PHB p.146-149, p.194-196), and the trade Great Weapon Master and Sharpshooter offer, 5 off the attack roll for 10
 * more damage on a hit (PHB p.167, p.170). A critical hit's doubled dice are the {@link Resolver}'s.
 */
public final class WeaponRules {

    /** What a Great Weapon Master or Sharpshooter attack takes off the attack roll. */
    public static final int POWER_ATTACK_PENALTY = 5;

    /** What a Great Weapon Master or Sharpshooter attack adds to the damage. */
    public static final int POWER_ATTACK_DAMAGE = 10;

    /** The weapon categories a proficiency can name (PHB p.146). */
    public enum Category { SIMPLE, MARTIAL }

    /** The feats whose attacks can trade 5 off the attack roll for 10 more damage, by their names. */
    public enum PowerAttack {
        GREAT_WEAPON_MASTER("Great Weapon Master"),
        SHARPSHOOTER("Sharpshooter");

        private final String featName;

        PowerAttack(String featName) {
            this.featName = featName;
        }

        public String featName() {
            return featName;
        }
    }

    /**
     * A weapon as the rules need it.
     *
     * @param category its category; null for an unarmed strike, which isn't a weapon
     * @param ranged   a ranged weapon, which attacks with DEX; a melee weapon thrown is still a melee weapon and
     *                 uses the same ability (PHB p.147)
     * @param finesse  attacks with STR or DEX, whichever is higher
     * @param heavy    a heavy weapon, as Great Weapon Master needs
     * @param light    a light weapon, as two-weapon fighting needs (PHB p.195)
     * @param damage   its damage dice, or a flat amount
     * @param twoHanded       a two-handed weapon, which needs both hands to attack with (PHB p.147)
     * @param reach           a reach weapon, which attacks in melee from 10 feet (PHB p.147)
     * @param versatileDamage a versatile weapon's damage used with two hands, such as a longsword's 1d10 (PHB p.147);
     *                        null for a weapon that isn't versatile
     */
    public record Weapon(String name, Category category, boolean ranged, boolean finesse, boolean heavy,
                         boolean light, Dice damage, String damageType, boolean twoHanded, boolean reach,
                         Dice versatileDamage) {

        public Weapon {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(damage, "damage");
        }

        /** A weapon that is neither two-handed, reach nor versatile. */
        public Weapon(String name, Category category, boolean ranged, boolean finesse, boolean heavy, boolean light,
                      Dice damage, String damageType) {
            this(name, category, ranged, finesse, heavy, light, damage, damageType, false, false, null);
        }

        /** Whether it is versatile, used with one or two hands (PHB p.147). */
        public boolean versatile() {
            return versatileDamage != null;
        }
    }

    /**
     * An unarmed strike: a melee attack with STR, which everyone is proficient with, dealing 1 + the STR modifier
     * in bludgeoning damage (PHB p.195).
     */
    public static final Weapon UNARMED_STRIKE =
            new Weapon("Unarmed Strike", null, false, false, false, false, new Dice(0, 0, 1), "bludgeoning");

    /**
     * What an attack with a weapon comes to.
     *
     * @param ability     the ability it uses, for the attack roll and the damage alike
     * @param attackBonus that ability's modifier, plus the proficiency bonus when proficient, less 5 for the feats'
     *                    trade
     * @param damage      the weapon's dice plus that ability's modifier, and 10 more for the feats' trade
     */
    public record Numbers(Ability ability, int attackBonus, Dice damage) {
    }

    private WeaponRules() {
    }

    /**
     * The ability a weapon attacks with: STR for a melee weapon, DEX for a ranged one, and for a finesse weapon
     * whichever has the higher modifier, keeping the weapon's own on a tie.
     */
    public static Ability ability(Weapon weapon, int strength, int dexterity) {
        Ability own = weapon.ranged() ? Ability.DEX : Ability.STR;
        if (!weapon.finesse()) {
            return own;
        }
        Ability other = own == Ability.STR ? Ability.DEX : Ability.STR;
        int ownModifier = Ability.modifier(own == Ability.STR ? strength : dexterity);
        int otherModifier = Ability.modifier(other == Ability.STR ? strength : dexterity);
        return otherModifier > ownModifier ? other : own;
    }

    /**
     * The trade the weapon itself allows, whoever wields it: Great Weapon Master's for a heavy melee weapon,
     * Sharpshooter's for a ranged weapon. A melee weapon thrown, such as a javelin, allows neither.
     */
    public static Optional<PowerAttack> powerAttackFor(Weapon weapon) {
        if (weapon.ranged()) {
            return Optional.of(PowerAttack.SHARPSHOOTER);
        }
        if (weapon.heavy()) {
            return Optional.of(PowerAttack.GREAT_WEAPON_MASTER);
        }
        return Optional.empty();
    }

    /**
     * The trade an attacker may take with the weapon: the weapon allows it, the attacker has that feat, and they are
     * proficient with the weapon.
     */
    public static Optional<PowerAttack> powerAttack(Weapon weapon, boolean proficient, Collection<PowerAttack> feats) {
        return powerAttackFor(weapon).filter(trade -> proficient && feats.contains(trade));
    }

    /**
     * Whether the weapon can make an off-hand attack, the bonus action two-weapon fighting gives after the Attack
     * action: a light melee weapon (PHB p.195).
     */
    public static boolean offHandWeapon(Weapon weapon) {
        return weapon.light() && !weapon.ranged();
    }

    /**
     * An attack with the weapon.
     *
     * @param strength    the attacker's STR score
     * @param dexterity   the attacker's DEX score
     * @param proficient  whether the attacker adds their proficiency bonus
     * @param powerAttack taking the feats' trade, which {@link #powerAttack} must allow
     * @throws IllegalArgumentException for the trade with a weapon that allows none
     */
    public static Numbers attack(Weapon weapon, int strength, int dexterity, int proficiencyBonus, boolean proficient,
                                 boolean powerAttack) {
        return attack(weapon, strength, dexterity, proficiencyBonus, proficient, powerAttack, false);
    }

    /**
     * As {@link #attack(Weapon, int, int, int, boolean, boolean)}, and for an off-hand attack (two-weapon fighting,
     * PHB p.195) the damage adds the ability modifier only when it is negative. Class features and fighting styles
     * that change the numbers are {@link AttackFeatures}'.
     */
    public static Numbers attack(Weapon weapon, int strength, int dexterity, int proficiencyBonus, boolean proficient,
                                 boolean powerAttack, boolean offHand) {
        if (powerAttack && powerAttackFor(weapon).isEmpty()) {
            throw new IllegalArgumentException(weapon.name() + " allows neither Great Weapon Master nor Sharpshooter");
        }
        Ability ability = ability(weapon, strength, dexterity);
        int abilityModifier = Ability.modifier(ability == Ability.STR ? strength : dexterity);
        int attackBonus = abilityModifier + (proficient ? proficiencyBonus : 0)
                - (powerAttack ? POWER_ATTACK_PENALTY : 0);
        Dice dice = weapon.damage();
        Dice damage = new Dice(dice.count(), dice.sides(),
                dice.modifier() + (offHand ? Math.min(0, abilityModifier) : abilityModifier)
                        + (powerAttack ? POWER_ATTACK_DAMAGE : 0));
        return new Numbers(ability, attackBonus, damage);
    }
}
