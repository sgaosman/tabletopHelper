package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.AttackFeatures.Attacker;
import com.tabletophelper.rules.combat.AttackFeatures.FightingStyle;
import com.tabletophelper.rules.combat.AttackFeatures.Grip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class features that change weapon attacks, typed out from the 2014 PHB: the Fighting Styles (p.72), Martial Arts
 * and the Monk table (p.77-78), the ki save DC (p.78), Improved and Superior Critical (p.72-73), the Rogue table's Sneak
 * Attack (p.95-96) and Divine Smite (p.85).
 */
class AttackFeaturesTest {

    private static final WeaponRules.Weapon LONGSWORD = new WeaponRules.Weapon("Longsword",
            WeaponRules.Category.MARTIAL, false, false, false, false, Dice.parse("1d8"), "slashing", false, false,
            Dice.parse("1d10"));
    private static final WeaponRules.Weapon GREATSWORD = new WeaponRules.Weapon("Greatsword",
            WeaponRules.Category.MARTIAL, false, false, true, false, Dice.parse("2d6"), "slashing", true, false, null);
    private static final WeaponRules.Weapon LONGBOW = new WeaponRules.Weapon("Longbow", WeaponRules.Category.MARTIAL,
            true, false, true, false, Dice.parse("1d8"), "piercing", true, false, null);
    private static final WeaponRules.Weapon SHORTSWORD = new WeaponRules.Weapon("Shortsword",
            WeaponRules.Category.MARTIAL, false, true, false, true, Dice.parse("1d6"), "piercing");
    private static final WeaponRules.Weapon HANDAXE = new WeaponRules.Weapon("Handaxe", WeaponRules.Category.SIMPLE,
            false, false, false, true, Dice.parse("1d6"), "slashing");
    private static final WeaponRules.Weapon QUARTERSTAFF = new WeaponRules.Weapon("Quarterstaff",
            WeaponRules.Category.SIMPLE, false, false, false, false, Dice.parse("1d6"), "bludgeoning", false, false,
            Dice.parse("1d8"));
    private static final WeaponRules.Weapon GREATCLUB = new WeaponRules.Weapon("Greatclub", WeaponRules.Category.SIMPLE,
            false, false, false, false, Dice.parse("1d8"), "bludgeoning", true, false, null);
    private static final WeaponRules.Weapon GLAIVE = new WeaponRules.Weapon("Glaive", WeaponRules.Category.MARTIAL,
            false, false, true, false, Dice.parse("1d10"), "slashing", true, true, null);

    // STR 16 (+3), DEX 14 (+2), proficiency +2
    private static Attacker with(FightingStyle... styles) {
        return new Attacker(16, 14, 2, Set.of(styles), 0, 20);
    }

    @Nested
    @DisplayName("Fighting Styles (PHB p.72)")
    class Styles {

        @Test
        @DisplayName("The styles are read from the features \"Fighting Style: X\" in any case, ignoring the others")
        void fromFeatures() {
            assertEquals(Set.of(FightingStyle.ARCHERY, FightingStyle.GREAT_WEAPON_FIGHTING),
                    FightingStyle.of(List.of("Fighting Style: Archery", "fighting style: great weapon fighting",
                            "Second Wind", "Fighting Style: Blind Fighting", "Archery")));
        }

        @Test
        @DisplayName("Archery adds 2 to attack rolls with a ranged weapon, and nothing to a melee one")
        void archery() {
            AttackFeatures.Numbers bow = AttackFeatures.numbers(LONGBOW, with(FightingStyle.ARCHERY), true, false,
                    false, Grip.TWO_HANDED);
            AttackFeatures.Numbers sword = AttackFeatures.numbers(LONGSWORD, with(FightingStyle.ARCHERY), true, false,
                    false, Grip.ONE_HANDED);

            assertEquals(2 + 2 + 2, bow.attackBonus());
            assertEquals(List.of("Archery +2"), bow.notes());
            assertEquals(3 + 2, sword.attackBonus());
        }

        @Test
        @DisplayName("Dueling adds 2 damage with a melee weapon in one hand, not two-handed, with two weapons or off-hand")
        void dueling() {
            Attacker duelist = with(FightingStyle.DUELING);

            assertEquals(Dice.parse("1d8+5"),
                    AttackFeatures.numbers(LONGSWORD, duelist, true, false, false, Grip.ONE_HANDED).damage());
            assertEquals(Dice.parse("1d10+3"),
                    AttackFeatures.numbers(LONGSWORD, duelist, true, false, false, Grip.TWO_HANDED).damage());
            assertEquals(Dice.parse("1d8+3"),
                    AttackFeatures.numbers(LONGSWORD, duelist, true, false, false, Grip.TWO_WEAPONS).damage());
            assertEquals(Dice.parse("1d6"),
                    AttackFeatures.numbers(HANDAXE, duelist, true, false, true, Grip.TWO_WEAPONS).damage());
            assertEquals(Dice.parse("4"), AttackFeatures.numbers(WeaponRules.UNARMED_STRIKE, duelist, true, false,
                    false, Grip.ONE_HANDED).damage());
        }

        @Test
        @DisplayName("Great Weapon Fighting rerolls 1s and 2s for a two-handed or versatile melee weapon in two hands")
        void greatWeaponFighting() {
            Attacker fighter = with(FightingStyle.GREAT_WEAPON_FIGHTING);

            assertEquals(2, AttackFeatures.numbers(GREATSWORD, fighter, true, false, false, Grip.TWO_HANDED)
                    .rerollUpTo());
            assertEquals(2, AttackFeatures.numbers(LONGSWORD, fighter, true, false, false, Grip.TWO_HANDED)
                    .rerollUpTo());
            assertEquals(0, AttackFeatures.numbers(LONGSWORD, fighter, true, false, false, Grip.ONE_HANDED)
                    .rerollUpTo());
            assertEquals(0, AttackFeatures.numbers(LONGBOW, fighter, true, false, false, Grip.TWO_HANDED)
                    .rerollUpTo());
            assertEquals(0, AttackFeatures.numbers(GREATSWORD, with(), true, false, false, Grip.TWO_HANDED)
                    .rerollUpTo());
        }

        @Test
        @DisplayName("Two-Weapon Fighting adds the ability modifier to an off-hand attack's damage; without it only a "
                + "negative modifier counts (PHB p.195)")
        void twoWeaponFighting() {
            assertEquals(Dice.parse("1d6+3"), AttackFeatures.numbers(HANDAXE, with(FightingStyle.TWO_WEAPON_FIGHTING),
                    true, false, true, Grip.TWO_WEAPONS).damage());
            assertEquals(Dice.parse("1d6"),
                    AttackFeatures.numbers(HANDAXE, with(), true, false, true, Grip.TWO_WEAPONS).damage());
            Attacker weak = new Attacker(8, 10, 2, Set.of(), 0, 20);
            assertEquals(Dice.parse("1d6-1"),
                    AttackFeatures.numbers(HANDAXE, weak, true, false, true, Grip.TWO_WEAPONS).damage());
        }
    }

    @Nested
    @DisplayName("Grips (PHB p.147, p.195)")
    class Grips {

        @Test
        @DisplayName("Two-handed weapons default to two hands, off-hand attacks to two weapons, the rest to one hand")
        void defaults() {
            assertEquals(Grip.TWO_HANDED, AttackFeatures.defaultGrip(GREATSWORD, false));
            assertEquals(Grip.ONE_HANDED, AttackFeatures.defaultGrip(LONGSWORD, false));
            assertEquals(Grip.TWO_WEAPONS, AttackFeatures.defaultGrip(HANDAXE, true));
        }

        @Test
        @DisplayName("A two-handed weapon needs both hands, only a two-handed or versatile one is held in two, and an "
                + "off-hand attack has a weapon in each hand")
        void refusals() {
            assertTrue(AttackFeatures.gripRefusal(GREATSWORD, Grip.ONE_HANDED, false).isPresent());
            assertTrue(AttackFeatures.gripRefusal(HANDAXE, Grip.TWO_HANDED, false).isPresent());
            assertTrue(AttackFeatures.gripRefusal(HANDAXE, Grip.ONE_HANDED, true).isPresent());
            assertEquals(Optional.empty(), AttackFeatures.gripRefusal(LONGSWORD, Grip.TWO_HANDED, false));
            assertEquals(Optional.empty(), AttackFeatures.gripRefusal(WeaponRules.UNARMED_STRIKE, Grip.TWO_HANDED,
                    false));
        }

        @Test
        @DisplayName("A versatile weapon in two hands deals its larger die (PHB p.147)")
        void versatile() {
            AttackFeatures.Numbers numbers = AttackFeatures.numbers(LONGSWORD, with(), true, false, false,
                    Grip.TWO_HANDED);
            assertEquals(Dice.parse("1d10+3"), numbers.damage());
            assertEquals(List.of("two-handed"), numbers.notes());
        }
    }

    @Nested
    @DisplayName("Martial Arts (PHB p.77-78)")
    class MartialArts {

        @ParameterizedTest(name = "Monk {0}: 1d{1}")
        @CsvSource({"1, 4", "4, 4", "5, 6", "10, 6", "11, 8", "16, 8", "17, 10", "20, 10"})
        @DisplayName("The Martial Arts column of the Monk table")
        void die(int level, int sides) {
            assertEquals(new Dice(1, sides, 0), AttackFeatures.martialArtsDie(level));
        }

        @Test
        @DisplayName("Monk weapons are shortswords and simple melee weapons that are neither two-handed nor heavy")
        void monkWeapons() {
            assertTrue(AttackFeatures.monkWeapon(SHORTSWORD));
            assertTrue(AttackFeatures.monkWeapon(QUARTERSTAFF));
            assertTrue(AttackFeatures.monkWeapon(HANDAXE));
            assertFalse(AttackFeatures.monkWeapon(GREATCLUB));
            assertFalse(AttackFeatures.monkWeapon(LONGSWORD));
            assertFalse(AttackFeatures.monkWeapon(LONGBOW));
            assertFalse(AttackFeatures.monkWeapon(WeaponRules.UNARMED_STRIKE));
        }

        @Test
        @DisplayName("A Monk 5 with DEX 16 strikes unarmed with DEX for 1d6 + 3, and a quarterstaff keeps its own die "
                + "when larger; armour (a Martial Arts level of 0) leaves an unarmed strike at 1 + STR")
        void numbers() {
            Attacker monk = new Attacker(10, 16, 3, Set.of(), 5, 20);

            AttackFeatures.Numbers unarmed = AttackFeatures.numbers(WeaponRules.UNARMED_STRIKE, monk, true, false,
                    false, Grip.ONE_HANDED);
            assertEquals(Ability.DEX, unarmed.ability());
            assertEquals(6, unarmed.attackBonus());
            assertEquals(Dice.parse("1d6+3"), unarmed.damage());
            assertTrue(unarmed.martialArts());
            assertEquals(Dice.parse("1d8+3"), AttackFeatures.numbers(QUARTERSTAFF, monk, true, false, false,
                    Grip.TWO_HANDED).damage());
            assertEquals(Dice.parse("1d6+3"), AttackFeatures.numbers(QUARTERSTAFF, monk, true, false, false,
                    Grip.ONE_HANDED).damage());
            assertFalse(AttackFeatures.numbers(LONGSWORD, monk, true, false, false, Grip.ONE_HANDED).martialArts());

            Attacker armoured = new Attacker(10, 16, 3, Set.of(), 0, 20);
            AttackFeatures.Numbers plain = AttackFeatures.numbers(WeaponRules.UNARMED_STRIKE, armoured, true, false,
                    false, Grip.ONE_HANDED);
            assertEquals(Ability.STR, plain.ability());
            assertEquals(Dice.parse("1"), plain.damage());
        }

        @Test
        @DisplayName("The ki save DC is 8 + proficiency + WIS: 13 for a Monk 5 with WIS 14")
        void kiSaveDc() {
            assertEquals(13, AttackFeatures.kiSaveDc(3, 14));
        }
    }

    @Nested
    @DisplayName("Critical hits, Sneak Attack and Divine Smite")
    class Riders {

        @ParameterizedTest(name = "Champion {0}: {1}")
        @CsvSource({"0, 20", "2, 20", "3, 19", "14, 19", "15, 18", "20, 18"})
        @DisplayName("Improved Critical at Champion 3 and Superior Critical at 15 (PHB p.72-73)")
        void critRange(int level, int range) {
            assertEquals(range, AttackFeatures.critRange(level));
        }

        @ParameterizedTest(name = "Rogue {0}: {1}d6")
        @CsvSource({"1, 1", "2, 1", "3, 2", "5, 3", "10, 5", "11, 6", "19, 10", "20, 10"})
        @DisplayName("The Rogue table's Sneak Attack (PHB p.95)")
        void sneakAttackDice(int level, int dice) {
            assertEquals(new Dice(dice, 6, 0), AttackFeatures.sneakAttack(level));
        }

        @Test
        @DisplayName("Sneak Attack needs a finesse or ranged weapon (PHB p.96)")
        void sneakAttackWeapons() {
            assertTrue(AttackFeatures.sneakAttackWeapon(SHORTSWORD));
            assertTrue(AttackFeatures.sneakAttackWeapon(LONGBOW));
            assertFalse(AttackFeatures.sneakAttackWeapon(LONGSWORD));
            assertFalse(AttackFeatures.sneakAttackWeapon(WeaponRules.UNARMED_STRIKE));
        }

        @ParameterizedTest(name = "claim {0}, rolled with {1}: {2}")
        @CsvSource(nullValues = "null", value = {
                "null, ADVANTAGE, true", "null, NORMAL, false", "null, DISADVANTAGE, false",
                "true, ADVANTAGE, true", "true, NORMAL, true", "true, DISADVANTAGE, false",
                "false, ADVANTAGE, false", "false, NORMAL, false"})
        @DisplayName("Sneak Attack comes with advantage unless declined, with a claimed ally otherwise, never with "
                + "disadvantage")
        void sneakAttackApplies(Boolean claim, RollMode mode, boolean applies) {
            assertEquals(applies, AttackFeatures.sneakAttackApplies(claim, mode));
        }

        @ParameterizedTest(name = "level {0} slot, undead or fiend {1}: {2}d8")
        @CsvSource({"1, false, 2", "2, false, 3", "3, false, 4", "4, false, 5", "5, false, 5", "9, false, 5",
                "1, true, 3", "4, true, 6", "5, true, 6"})
        @DisplayName("Divine Smite: 2d8, +1d8 per slot level above 1st to 5d8, +1d8 against undead or fiends (PHB p.85)")
        void divineSmite(int slot, boolean undeadOrFiend, int dice) {
            assertEquals(new Dice(dice, 8, 0), AttackFeatures.divineSmite(slot, undeadOrFiend));
        }

        @Test
        @DisplayName("Undead and fiends are told by the stat block's type, a choice of types included")
        void undeadOrFiend() {
            assertTrue(AttackFeatures.undeadOrFiend("undead"));
            assertTrue(AttackFeatures.undeadOrFiend("Fiend"));
            assertTrue(AttackFeatures.undeadOrFiend("celestial or fiend"));
            assertFalse(AttackFeatures.undeadOrFiend("humanoid"));
            assertFalse(AttackFeatures.undeadOrFiend(null));
            assertThrows(IllegalArgumentException.class, () -> AttackFeatures.divineSmite(0, false));
        }

        @Test
        @DisplayName("A Champion's range shows in the numbers and the notes")
        void championNumbers() {
            AttackFeatures.Numbers numbers = AttackFeatures.numbers(GLAIVE, new Attacker(16, 14, 2, Set.of(), 0, 19),
                    true, false, false, Grip.TWO_HANDED);
            assertEquals(19, numbers.critRange());
            assertEquals(List.of("critical on 19-20"), numbers.notes());
        }
    }
}
