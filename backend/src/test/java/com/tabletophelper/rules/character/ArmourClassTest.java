package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.ArmourClass.Armour;
import com.tabletophelper.rules.character.ArmourClass.Bonus;
import com.tabletophelper.rules.character.ArmourClass.Category;
import com.tabletophelper.rules.character.ArmourClass.Part;
import com.tabletophelper.rules.character.ArmourClass.Shield;
import com.tabletophelper.rules.character.ArmourClass.Unarmoured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Armour Class rules (M13.5), with every expected value typed out from the 2014 books: the Armor table (PHB p.145),
 * shields (PHB p.144), Unarmored Defense (PHB p.48, p.78, p.164), Draconic Resilience (PHB p.102), natural armour
 * (VGM p.113, GGR p.19, TTP p.4) and Integrated Protection (ERLW p.36).
 */
class ArmourClassTest {

    private static final Shield SHIELD = new Shield("Shield", 2, 0);

    /** Modifiers DEX, CON and WIS; the others 0. */
    private static Map<Ability, Integer> modifiers(int dex, int con, int wis) {
        Map<Ability, Integer> modifiers = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) {
            modifiers.put(ability, 0);
        }
        modifiers.put(Ability.DEX, dex);
        modifiers.put(Ability.CON, con);
        modifiers.put(Ability.WIS, wis);
        return modifiers;
    }

    private static ArmourClass.Result worn(Armour armour, Shield shield, Map<Ability, Integer> modifiers,
                                           List<Unarmoured> formulas) {
        return ArmourClass.of(armour, shield, modifiers, formulas, List.of());
    }

    @Nested
    @DisplayName("The Armor table, PHB p.145")
    class ArmorTable {

        @ParameterizedTest(name = "{0}: base {1}, with DEX +3 AC {2}, with DEX -1 AC {3}")
        @CsvSource({
                // Light armour: base + DEX
                "Padded Armor, LIGHT, 11, 14, 10",
                "Leather Armor, LIGHT, 11, 14, 10",
                "Studded Leather Armor, LIGHT, 12, 15, 11",
                // Medium armour: base + DEX, at most +2; a negative DEX still counts
                "Hide Armor, MEDIUM, 12, 14, 11",
                "Chain Shirt, MEDIUM, 13, 15, 12",
                "Scale Mail, MEDIUM, 14, 16, 13",
                "Breastplate, MEDIUM, 14, 16, 13",
                "Half Plate Armor, MEDIUM, 15, 17, 14",
                // Heavy armour: base alone
                "Ring Mail, HEAVY, 14, 14, 14",
                "Chain Mail, HEAVY, 16, 16, 16",
                "Splint Armor, HEAVY, 17, 17, 17",
                "Plate Armor, HEAVY, 18, 18, 18"
        })
        @DisplayName("Each armour's AC with a high and a negative DEX modifier")
        void everyArmour(String name, Category category, int base, int withDexThree, int withDexMinusOne) {
            Armour armour = new Armour(name, category, base, 0);

            assertThat(worn(armour, null, modifiers(3, 0, 0), List.of()).total()).isEqualTo(withDexThree);
            assertThat(worn(armour, null, modifiers(-1, 0, 0), List.of()).total()).isEqualTo(withDexMinusOne);
        }

        @Test
        @DisplayName("Medium armour's breakdown shows the capped DEX: Half Plate 15 + DEX (max 2) 2 = 17")
        void mediumBreakdown() {
            ArmourClass.Result result = worn(new Armour("Half Plate Armor", Category.MEDIUM, 15, 0), null,
                    modifiers(4, 0, 0), List.of());

            assertThat(result.parts()).containsExactly(new Part("Half Plate Armor", 15), new Part("DEX (max 2)", 2));
            assertThat(result.total()).isEqualTo(17);
        }

        @Test
        @DisplayName("Heavy armour's breakdown is the armour alone: Chain Mail 16")
        void heavyBreakdown() {
            assertThat(worn(new Armour("Chain Mail", Category.HEAVY, 16, 0), null, modifiers(2, 0, 0), List.of())
                    .parts()).containsExactly(new Part("Chain Mail", 16));
        }
    }

    @Nested
    @DisplayName("Shields and magic bonuses")
    class ShieldsAndBonuses {

        @Test
        @DisplayName("A shield adds 2 (PHB p.144): Chain Mail 16 + Shield 2 = 18")
        void shield() {
            ArmourClass.Result result = worn(new Armour("Chain Mail", Category.HEAVY, 16, 0), SHIELD,
                    modifiers(1, 0, 0), List.of());

            assertThat(result.parts()).containsExactly(new Part("Chain Mail", 16), new Part("Shield", 2));
            assertThat(result.total()).isEqualTo(18);
        }

        @Test
        @DisplayName("A shield adds 2 without armour too: 10 + DEX 2 + Shield 2 = 14")
        void shieldWithoutArmour() {
            assertThat(worn(null, SHIELD, modifiers(2, 0, 0), List.of()).total()).isEqualTo(14);
        }

        @Test
        @DisplayName("A magic armour's and shield's bonuses add to their AC: Efreeti Chain 16 + 3, Arrow-Catching Shield 2 + 2")
        void magicBonuses() {
            ArmourClass.Result result = worn(new Armour("Efreeti Chain", Category.HEAVY, 16, 3),
                    new Shield("Arrow-Catching Shield", 2, 2), modifiers(0, 0, 0), List.of());

            assertThat(result.parts()).containsExactly(new Part("Efreeti Chain", 19),
                    new Part("Arrow-Catching Shield", 4));
            assertThat(result.total()).isEqualTo(23);
        }

        @Test
        @DisplayName("A magic light armour adds DEX on top of its bonus: Glamoured Studded Leather 12 + 1 + DEX 4 = 17")
        void magicLightArmour() {
            assertThat(worn(new Armour("Glamoured Studded Leather", Category.LIGHT, 12, 1), null,
                    modifiers(4, 0, 0), List.of()).total()).isEqualTo(17);
        }
    }

    @Nested
    @DisplayName("Without armour")
    class WithoutArmour {

        @Test
        @DisplayName("10 + DEX (PHB p.14): with DEX +2, 12")
        void tenPlusDex() {
            ArmourClass.Result result = worn(null, null, modifiers(2, 0, 0), List.of());

            assertThat(result.parts()).containsExactly(new Part("Unarmoured", 10), new Part("DEX", 2));
            assertThat(result.total()).isEqualTo(12);
        }

        @Test
        @DisplayName("Barbarian Unarmored Defense, 10 + DEX + CON (PHB p.48): DEX 2, CON 3 is 15")
        void barbarian() {
            ArmourClass.Result result = worn(null, null, modifiers(2, 3, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence()));

            assertThat(result.parts()).containsExactly(new Part("Unarmoured Defence", 10), new Part("DEX", 2),
                    new Part("CON", 3));
            assertThat(result.total()).isEqualTo(15);
        }

        @Test
        @DisplayName("A Barbarian keeps Unarmored Defense with a shield (PHB p.48): 15 + 2 = 17")
        void barbarianWithShield() {
            ArmourClass.Result result = worn(null, SHIELD, modifiers(2, 3, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence()));

            assertThat(result.total()).isEqualTo(17);
            assertThat(result.parts()).first().isEqualTo(new Part("Unarmoured Defence", 10));
        }

        @Test
        @DisplayName("A Barbarian in armour uses the armour: Chain Shirt 13 + DEX 2 = 15, not 10 + 2 + 4")
        void barbarianInArmour() {
            assertThat(worn(new Armour("Chain Shirt", Category.MEDIUM, 13, 0), null, modifiers(2, 4, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence())).total()).isEqualTo(15);
        }

        @Test
        @DisplayName("Monk Unarmored Defense, 10 + DEX + WIS (PHB p.78): DEX 3, WIS 2 is 15")
        void monk() {
            ArmourClass.Result result = worn(null, null, modifiers(3, 0, 2),
                    List.of(ArmourClass.monkUnarmouredDefence()));

            assertThat(result.parts()).containsExactly(new Part("Unarmoured Defence", 10), new Part("DEX", 3),
                    new Part("WIS", 2));
            assertThat(result.total()).isEqualTo(15);
        }

        @Test
        @DisplayName("A Monk with a shield loses Unarmored Defense (PHB p.78): 10 + DEX 3 + Shield 2 = 15")
        void monkWithShield() {
            ArmourClass.Result result = worn(null, SHIELD, modifiers(3, 0, 2),
                    List.of(ArmourClass.monkUnarmouredDefence()));

            assertThat(result.parts()).containsExactly(new Part("Unarmoured", 10), new Part("DEX", 3),
                    new Part("Shield", 2));
            assertThat(result.total()).isEqualTo(15);
        }

        @Test
        @DisplayName("Draconic Resilience, 13 + DEX (PHB p.102): DEX 2 is 15, and 17 with a shield")
        void draconicResilience() {
            Unarmoured draconic = new Unarmoured("Draconic Bloodline", 13, List.of(Ability.DEX), true);

            assertThat(worn(null, null, modifiers(2, 0, 0), List.of(draconic)).total()).isEqualTo(15);
            assertThat(worn(null, SHIELD, modifiers(2, 0, 0), List.of(draconic)).total()).isEqualTo(17);
        }

        @Test
        @DisplayName("A Tortle's flat 17, which DEX doesn't change (TTP p.4), and 19 with a shield")
        void tortle() {
            Unarmoured tortle = new Unarmoured("Tortle natural armour", 17, List.of(), true);

            ArmourClass.Result result = worn(null, null, modifiers(3, 0, 0), List.of(tortle));
            assertThat(result.parts()).containsExactly(new Part("Tortle natural armour", 17));
            assertThat(result.total()).isEqualTo(17);
            assertThat(worn(null, SHIELD, modifiers(-1, 0, 0), List.of(tortle)).total()).isEqualTo(19);
        }

        @Test
        @DisplayName("A Loxodon's natural armour adds CON, not DEX (GGR p.19): 12 + CON 3 = 15 with DEX 1")
        void loxodon() {
            Unarmoured loxodon = new Unarmoured("Loxodon natural armour", 12, List.of(Ability.CON), true, true);

            ArmourClass.Result result = worn(null, null, modifiers(1, 3, 0), List.of(loxodon));
            assertThat(result.parts()).containsExactly(new Part("Loxodon natural armour", 12), new Part("CON", 3));
            assertThat(result.total()).isEqualTo(15);
        }

        @Test
        @DisplayName("Natural armour that may stand in for armour does when the armour gives less (VGM p.113): Lizardfolk in Leather, 13 + 1 beats 11 + 1")
        void naturalArmourOverWeakerArmour() {
            Unarmoured lizardfolk = new Unarmoured("Lizardfolk natural armour", 13, List.of(Ability.DEX), true, true);

            ArmourClass.Result weaker = worn(new Armour("Leather Armor", Category.LIGHT, 11, 0), SHIELD,
                    modifiers(1, 0, 0), List.of(lizardfolk));
            assertThat(weaker.parts()).containsExactly(new Part("Lizardfolk natural armour", 13), new Part("DEX", 1),
                    new Part("Shield", 2));
            assertThat(weaker.total()).isEqualTo(16);

            assertThat(worn(new Armour("Plate Armor", Category.HEAVY, 18, 0), null, modifiers(1, 0, 0),
                    List.of(lizardfolk)).total()).isEqualTo(18);
        }

        @Test
        @DisplayName("A formula that applies only without armour doesn't stand in for armour: Draconic Resilience in Leather is 11 + DEX")
        void unarmouredOnlyFormulaIgnoredInArmour() {
            Unarmoured draconic = new Unarmoured("Draconic Bloodline", 13, List.of(Ability.DEX), true);

            assertThat(worn(new Armour("Leather Armor", Category.LIGHT, 11, 0), null, modifiers(2, 0, 0),
                    List.of(draconic)).total()).isEqualTo(13);
        }

        @Test
        @DisplayName("The highest formula wins, and they don't stack: a Barbarian Draconic Sorcerer with DEX 2, CON 2 has 14 + 1 = 15, not 13 + 2 + 2 + 2")
        void highestWins() {
            Unarmoured draconic = new Unarmoured("Draconic Bloodline", 13, List.of(Ability.DEX), true);

            ArmourClass.Result lowCon = worn(null, null, modifiers(2, 1, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence(), draconic));
            assertThat(lowCon.parts()).first().isEqualTo(new Part("Draconic Bloodline", 13));
            assertThat(lowCon.total()).isEqualTo(15);

            ArmourClass.Result highCon = worn(null, null, modifiers(2, 4, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence(), draconic));
            assertThat(highCon.parts()).first().isEqualTo(new Part("Unarmoured Defence", 10));
            assertThat(highCon.total()).isEqualTo(16);
        }

        @Test
        @DisplayName("10 + DEX stays when a formula would give less: a Barbarian with CON -1 has 10 + DEX")
        void defaultWhenFormulaIsLower() {
            ArmourClass.Result result = worn(null, null, modifiers(2, -1, 0),
                    List.of(ArmourClass.barbarianUnarmouredDefence()));

            assertThat(result.parts()).first().isEqualTo(new Part("Unarmoured", 10));
            assertThat(result.total()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("Flat bonuses")
    class FlatBonuses {

        @Test
        @DisplayName("Warforged Integrated Protection adds 1 in armour and out (ERLW p.36)")
        void warforged() {
            List<Bonus> warforged = List.of(new Bonus("Warforged", 1));

            ArmourClass.Result armoured = ArmourClass.of(new Armour("Chain Mail", Category.HEAVY, 16, 0), SHIELD,
                    modifiers(0, 0, 0), List.of(), warforged);
            assertThat(armoured.parts()).containsExactly(new Part("Chain Mail", 16), new Part("Shield", 2),
                    new Part("Warforged", 1));
            assertThat(armoured.total()).isEqualTo(19);

            assertThat(ArmourClass.of(null, null, modifiers(2, 0, 0), List.of(), warforged).total()).isEqualTo(13);
        }
    }

    @Nested
    @DisplayName("Heavy armour's Strength, PHB p.144")
    class Strength {

        private final Armour chainMail = new Armour("Chain Mail", Category.HEAVY, 16, 0);

        @Test
        @DisplayName("Below the requirement takes 10 feet off the speed; at it, nothing")
        void belowRequirement() {
            assertThat(ArmourClass.speedPenalty(chainMail, 13, 12, false)).isEqualTo(10);
            assertThat(ArmourClass.speedPenalty(chainMail, 13, 13, false)).isZero();
        }

        @Test
        @DisplayName("A dwarf isn't slowed (PHB p.20)")
        void dwarf() {
            assertThat(ArmourClass.speedPenalty(chainMail, 13, 8, true)).isZero();
        }

        @Test
        @DisplayName("Armour without a requirement, and medium armour, never slow")
        void noRequirement() {
            assertThat(ArmourClass.speedPenalty(new Armour("Ring Mail", Category.HEAVY, 14, 0), null, 3, false))
                    .isZero();
            assertThat(ArmourClass.speedPenalty(new Armour("Half Plate Armor", Category.MEDIUM, 15, 0), 15, 3,
                    false)).isZero();
            assertThat(ArmourClass.speedPenalty(null, null, 3, false)).isZero();
        }
    }
}
