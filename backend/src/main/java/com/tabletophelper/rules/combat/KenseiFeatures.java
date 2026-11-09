package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.Collection;

/**
 * The Way of the Kensei monk's features that change a fight (XGE p.34-35), beyond its kensei weapons being monk weapons
 * ({@link AttackFeatures.Attacker#kenseiWeapon}).
 * <ul>
 *   <li>Agile Parry (Monk 3): an unarmed strike made as part of the Attack action on the monk's turn, while it holds a
 *       melee kensei weapon, gives +2 AC until the start of its next turn, while it isn't incapacitated.</li>
 *   <li>Kensei's Shot (Monk 3): a bonus action; for the rest of the turn a ranged attack with a kensei weapon deals an
 *       extra 1d4 damage of the weapon's type on a hit.</li>
 *   <li>Deft Strike (Monk 6, part of One with the Blade): once on each of the monk's turns, after a hit with a kensei
 *       weapon, 1 ki point for extra damage equal to its Martial Arts die. One with the Blade's Magic Kensei Weapons
 *       only matters against resistance to nonmagical attacks, which monsters' damage defences don't separate.</li>
 *   <li>Sharpen the Blade (Monk 11): a bonus action spending 1 to 3 ki points, giving one kensei weapon a bonus to
 *       attack and damage rolls equal to the points spent, for 1 minute or until the feature is used again; it has no
 *       effect on a magic weapon that already has such a bonus.</li>
 *   <li>Unerring Accuracy (Monk 17): once on each of the monk's turns, a missed attack roll with a monk weapon may be
 *       rolled again.</li>
 * </ul>
 */
public final class KenseiFeatures {

    /** The class and subclass, as the class data names them. */
    public static final String MONK = "Monk";
    public static final String SUBCLASS = "Way of the Kensei";

    /** The Monk levels of the features (XGE p.34-35). */
    public static final int AGILE_PARRY_LEVEL = 3;
    public static final int KENSEIS_SHOT_LEVEL = 3;
    public static final int DEFT_STRIKE_LEVEL = 6;
    public static final int SHARPEN_THE_BLADE_LEVEL = 11;
    public static final int UNERRING_ACCURACY_LEVEL = 17;

    /** The entries the features leave among the monk's conditions. */
    public static final String AGILE_PARRY = "agile parry";
    public static final String KENSEIS_SHOT = "kensei's shot";
    public static final String SHARPEN_THE_BLADE = "sharpen the blade";

    /** Agile Parry's bonus to AC (XGE p.34). */
    public static final int AGILE_PARRY_BONUS = 2;
    /** Kensei's Shot's extra damage (XGE p.34). */
    public static final Dice KENSEIS_SHOT_DAMAGE = new Dice(1, 4, 0);
    /** The most ki points Sharpen the Blade spends, and so its largest bonus (XGE p.35). */
    public static final int SHARPEN_THE_BLADE_MOST_KI = 3;
    /** Sharpen the Blade's duration, 1 minute, in rounds (XGE p.35). */
    public static final int SHARPEN_THE_BLADE_ROUNDS = 10;

    private KenseiFeatures() {
    }

    /**
     * The AC bonus an Agile Parry entry gives while it lasts: +2 unless its creature is incapacitated (XGE p.34), whose
     * conditions are given, as Dodge's protection is read (PHB p.192).
     */
    public static int agileParryBonus(Collection<String> conditions) {
        return ActionEconomy.incapacitated(conditions) ? 0 : AGILE_PARRY_BONUS;
    }

    /**
     * Whether Agile Parry follows an attack (XGE p.34): an unarmed strike made as part of the Attack action on the
     * monk's own turn, while it holds a melee kensei weapon.
     */
    public static boolean agileParry(boolean unarmedStrike, boolean attackAction, boolean ownTurn,
                                     boolean holdsMeleeKenseiWeapon) {
        return unarmedStrike && attackAction && ownTurn && holdsMeleeKenseiWeapon;
    }

    /**
     * Deft Strike's extra damage (XGE p.34): the Martial Arts die of the monk's level (PHB p.77), its dice doubled on a
     * critical hit as every die of a hit's damage is (PHB p.196).
     */
    public static Dice deftStrike(int monkLevel, boolean critical) {
        Dice die = AttackFeatures.martialArtsDie(monkLevel);
        return critical ? die.doubled() : die;
    }

    /**
     * Why Sharpen the Blade can't spend that many ki points, or null: it spends 1 to 3 (XGE p.35).
     */
    public static String sharpenTheBladeRefusal(int ki) {
        return ki < 1 || ki > SHARPEN_THE_BLADE_MOST_KI
                ? "Sharpen the Blade spends 1 to " + SHARPEN_THE_BLADE_MOST_KI + " ki points, not " + ki : null;
    }
}
