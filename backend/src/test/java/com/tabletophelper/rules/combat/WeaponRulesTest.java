package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.WeaponRules.Category;
import com.tabletophelper.rules.combat.WeaponRules.Numbers;
import com.tabletophelper.rules.combat.WeaponRules.PowerAttack;
import com.tabletophelper.rules.combat.WeaponRules.Weapon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Weapon attacks under the 2014 rules (D164), with the expected values worked from the PHB: the ability and
 * attack bonus (p.146-149, p.194), unarmed strikes (p.195), critical hits (p.196), Great Weapon Master (p.167) and
 * Sharpshooter (p.170). The weapons are the PHB's, as the items table holds them.
 */
class WeaponRulesTest {

    private static final Weapon LONGSWORD = new Weapon("Longsword", Category.MARTIAL, false, false, false,
            Dice.parse("1d8"), "slashing");
    private static final Weapon RAPIER = new Weapon("Rapier", Category.MARTIAL, false, true, false,
            Dice.parse("1d8"), "piercing");
    private static final Weapon GREATSWORD = new Weapon("Greatsword", Category.MARTIAL, false, false, true,
            Dice.parse("2d6"), "slashing");
    private static final Weapon LONGBOW = new Weapon("Longbow", Category.MARTIAL, true, false, true,
            Dice.parse("1d8"), "piercing");
    private static final Weapon DART = new Weapon("Dart", Category.SIMPLE, true, true, false,
            Dice.parse("1d4"), "piercing");
    private static final Weapon JAVELIN = new Weapon("Javelin", Category.SIMPLE, false, false, false,
            Dice.parse("1d6"), "piercing");
    private static final Weapon BLOWGUN = new Weapon("Blowgun", Category.MARTIAL, true, false, false,
            Dice.parse("1"), "piercing");

    @Test
    @DisplayName("A melee weapon attacks with STR and a ranged one with DEX, whichever is higher")
    void abilityByWeapon() {
        // STR 16 (+3), DEX 14 (+2), proficient at +2
        assertEquals(new Numbers(Ability.STR, 5, Dice.parse("1d8+3")), WeaponRules.attack(LONGSWORD, 16, 14, 2, true, false));
        // STR 18 (+4) doesn't help a longbow: DEX 12 (+1)
        assertEquals(new Numbers(Ability.DEX, 3, Dice.parse("1d8+1")), WeaponRules.attack(LONGBOW, 18, 12, 2, true, false));
        // A thrown javelin is still a melee weapon, with STR (PHB p.147)
        assertEquals(Ability.STR, WeaponRules.ability(JAVELIN, 8, 18));
    }

    @Test
    @DisplayName("A finesse weapon attacks with the higher of STR and DEX, keeping its own on a tie")
    void finesse() {
        assertEquals(new Numbers(Ability.DEX, 7, Dice.parse("1d8+4")), WeaponRules.attack(RAPIER, 10, 18, 3, true, false));
        assertEquals(Ability.STR, WeaponRules.ability(RAPIER, 18, 10));
        assertEquals(Ability.STR, WeaponRules.ability(RAPIER, 14, 15));
        // A dart is a ranged finesse weapon: a strong thrower uses STR
        assertEquals(Ability.STR, WeaponRules.ability(DART, 16, 12));
        assertEquals(Ability.DEX, WeaponRules.ability(DART, 12, 13));
    }

    @Test
    @DisplayName("Without proficiency the attack bonus is the ability modifier alone; damage never adds proficiency")
    void proficiency() {
        Numbers proficient = WeaponRules.attack(LONGSWORD, 16, 10, 4, true, false);
        Numbers not = WeaponRules.attack(LONGSWORD, 16, 10, 4, false, false);

        assertEquals(7, proficient.attackBonus());
        assertEquals(3, not.attackBonus());
        assertEquals(Dice.parse("1d8+3"), proficient.damage());
        assertEquals(Dice.parse("1d8+3"), not.damage());
        // A low score is a penalty to both
        assertEquals(new Numbers(Ability.STR, 1, Dice.parse("1d8-1")), WeaponRules.attack(LONGSWORD, 8, 10, 2, true, false));
    }

    @Test
    @DisplayName("An unarmed strike is proficient, with STR, for 1 + the STR modifier bludgeoning, and no dice")
    void unarmedStrike() {
        Numbers strike = WeaponRules.attack(WeaponRules.UNARMED_STRIKE, 16, 18, 2, true, false);

        assertEquals(new Numbers(Ability.STR, 5, new Dice(0, 0, 4)), strike);
        assertEquals("bludgeoning", WeaponRules.UNARMED_STRIKE.damageType());
        assertEquals(new Dice(0, 0, 0), WeaponRules.attack(WeaponRules.UNARMED_STRIKE, 8, 10, 2, true, false).damage());
        assertEquals(Optional.empty(), WeaponRules.powerAttackFor(WeaponRules.UNARMED_STRIKE));
    }

    @Test
    @DisplayName("A critical hit rolls the weapon's dice twice and adds the modifier once")
    void criticalHitDoublesTheDice() {
        Numbers greatsword = WeaponRules.attack(GREATSWORD, 18, 10, 3, true, false);
        Effect effect = new Effect("Greatsword", new Effect.Attack(greatsword.attackBonus(), RollMode.NORMAL, true, false),
                List.of(new Effect.DamagePart(greatsword.damage(), "slashing")), null, List.of(), false, null);
        Resolver.Target goblin = new Resolver.Target(UUID.randomUUID(), 15, 0, List.of());

        // A natural 20, then four d6s: 3 + 4 + 5 + 6 + 4
        Resolver.TargetOutcome critical = new Resolver(Rng.scripted(20, 3, 4, 5, 6)).resolve(effect, List.of(goblin))
                .getFirst();
        // A flat amount has no dice to double
        Effect strike = new Effect("Unarmed Strike", new Effect.Attack(5, RollMode.NORMAL, true, false),
                List.of(new Effect.DamagePart(WeaponRules.attack(WeaponRules.UNARMED_STRIKE, 16, 10, 2, true, false)
                        .damage(), "bludgeoning")), null, List.of(), false, null);
        Resolver.TargetOutcome strikeCritical = new Resolver(Rng.scripted(20)).resolve(strike, List.of(goblin))
                .getFirst();

        assertEquals(Outcome.CRITICAL, critical.outcome());
        assertEquals(List.of(new Resolver.Damage("slashing", 22)), critical.damage());
        assertEquals(List.of(new Resolver.Damage("bludgeoning", 4)), strikeCritical.damage());
    }

    @Test
    @DisplayName("Great Weapon Master needs a heavy melee weapon and Sharpshooter a ranged one; a thrown melee weapon has neither")
    void whichTradeAWeaponAllows() {
        assertEquals(Optional.of(PowerAttack.GREAT_WEAPON_MASTER), WeaponRules.powerAttackFor(GREATSWORD));
        assertEquals(Optional.of(PowerAttack.SHARPSHOOTER), WeaponRules.powerAttackFor(LONGBOW));
        assertEquals(Optional.of(PowerAttack.SHARPSHOOTER), WeaponRules.powerAttackFor(DART));
        assertEquals(Optional.empty(), WeaponRules.powerAttackFor(LONGSWORD));
        assertEquals(Optional.empty(), WeaponRules.powerAttackFor(JAVELIN));
        assertEquals("Great Weapon Master", PowerAttack.GREAT_WEAPON_MASTER.featName());
        assertEquals("Sharpshooter", PowerAttack.SHARPSHOOTER.featName());
    }

    @Test
    @DisplayName("The trade needs the feat and proficiency with the weapon, not just a weapon that allows it")
    void whoMayTakeTheTrade() {
        Set<PowerAttack> both = Set.of(PowerAttack.GREAT_WEAPON_MASTER, PowerAttack.SHARPSHOOTER);

        assertEquals(Optional.of(PowerAttack.GREAT_WEAPON_MASTER), WeaponRules.powerAttack(GREATSWORD, true, both));
        assertEquals(Optional.empty(), WeaponRules.powerAttack(GREATSWORD, false, both));
        assertEquals(Optional.empty(), WeaponRules.powerAttack(GREATSWORD, true, Set.of(PowerAttack.SHARPSHOOTER)));
        assertEquals(Optional.of(PowerAttack.SHARPSHOOTER), WeaponRules.powerAttack(LONGBOW, true, both));
        assertEquals(Optional.empty(), WeaponRules.powerAttack(LONGSWORD, true, both));
    }

    @Test
    @DisplayName("The trade takes 5 off the attack bonus and adds 10 to the damage, not doubled on a critical hit")
    void theTrade() {
        assertEquals(new Numbers(Ability.STR, 2, Dice.parse("2d6+14")), WeaponRules.attack(GREATSWORD, 18, 10, 3, true, true));
        assertEquals(new Numbers(Ability.DEX, 1, Dice.parse("1d8+14")), WeaponRules.attack(LONGBOW, 10, 18, 2, true, true));
        assertEquals(new Dice(0, 0, 13), WeaponRules.attack(BLOWGUN, 10, 14, 2, true, true).damage());
        assertEquals(Dice.parse("2d6+14").doubled(), Dice.parse("4d6+14"));
        assertThrows(IllegalArgumentException.class, () -> WeaponRules.attack(LONGSWORD, 16, 10, 2, true, true));
    }
}
