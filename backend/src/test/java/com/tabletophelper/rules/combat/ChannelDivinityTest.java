package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ChannelDivinity.Option;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Channel Divinity's options and tables (PHB p.58–63, p.85–88), Bardic Inspiration's die (p.53–54) and Disciple of Life. */
class ChannelDivinityTest {

    @ParameterizedTest(name = "Cleric {0}: destroys CR {1} or lower")
    @CsvSource({"4, -1", "5, 0.5", "7, 0.5", "8, 1", "10, 1", "11, 2", "13, 2", "14, 3", "16, 3", "17, 4", "20, 4"})
    @DisplayName("Destroy Undead's challenge ratings by cleric level (PHB p.59)")
    void destroyUndeadTable(int level, double rating) {
        OptionalDouble highest = ChannelDivinity.destroyUndeadRating(level);
        if (rating < 0) {
            assertTrue(highest.isEmpty());
        } else {
            assertEquals(rating, highest.getAsDouble());
        }
    }

    @ParameterizedTest(name = "Cleric {0} against CR {1}: destroyed {2}")
    @CsvSource({"5, 1/2, true", "5, 1/4, true", "5, 1, false", "8, 1, true", "8, 2, false", "17, 4, true",
            "17, 5, false", "4, 1/8, false", "20, unknown, false"})
    @DisplayName("Whether Destroy Undead destroys an undead that failed its save")
    void destroys(int level, String rating, boolean destroyed) {
        assertEquals(destroyed, ChannelDivinity.destroys(level, rating));
    }

    @Test
    @DisplayName("Challenge ratings read as fractions and whole numbers")
    void challengeRatings() {
        assertEquals(0.125, ChannelDivinity.challengeRating("1/8").getAsDouble());
        assertEquals(0.5, ChannelDivinity.challengeRating(" 1/2 ").getAsDouble());
        assertEquals(12, ChannelDivinity.challengeRating("12").getAsDouble());
        assertTrue(ChannelDivinity.challengeRating(null).isEmpty());
        assertTrue(ChannelDivinity.challengeRating("?").isEmpty());
    }

    @Test
    @DisplayName("Each subclass gets its own options at its levels, and every cleric Turn Undead")
    void availableOptions() {
        assertEquals(List.of(Option.TURN_UNDEAD), ChannelDivinity.available("Cleric", null, 2));
        assertEquals(List.of(), ChannelDivinity.available("Cleric", "Life Domain", 1));
        assertEquals(List.of(Option.TURN_UNDEAD, Option.PRESERVE_LIFE),
                ChannelDivinity.available("Cleric", "Life Domain", 2));
        assertEquals(List.of(Option.TURN_UNDEAD, Option.GUIDED_STRIKE),
                ChannelDivinity.available("Cleric", "War Domain", 5));
        assertEquals(List.of(Option.TURN_UNDEAD, Option.GUIDED_STRIKE, Option.WAR_GODS_BLESSING),
                ChannelDivinity.available("Cleric", "War Domain", 6));
        assertEquals(List.of(Option.TURN_UNDEAD, Option.KNOWLEDGE_OF_THE_AGES, Option.READ_THOUGHTS),
                ChannelDivinity.available("Cleric", "Knowledge Domain", 6));
        assertEquals(List.of(), ChannelDivinity.available("Paladin", "Oath of Devotion", 2));
        assertEquals(List.of(Option.SACRED_WEAPON, Option.TURN_THE_UNHOLY),
                ChannelDivinity.available("Paladin", "Oath of Devotion", 3));
        assertEquals(List.of(Option.NATURES_WRATH, Option.TURN_THE_FAITHLESS),
                ChannelDivinity.available("Paladin", "Oath of the Ancients", 3));
        assertEquals(List.of(Option.ABJURE_ENEMY, Option.VOW_OF_ENMITY),
                ChannelDivinity.available("Paladin", "Oath of Vengeance", 20));
        assertEquals(List.of(), ChannelDivinity.available("Wizard", "Life Domain", 20));
    }

    @Test
    @DisplayName("Options by id, and what each costs from the turn")
    void options() {
        assertEquals(Optional.of(Option.VOW_OF_ENMITY), ChannelDivinity.option("Vow-of-Enmity"));
        assertTrue(ChannelDivinity.option("path-to-the-grave").isEmpty());
        assertEquals(ActionCost.BONUS_ACTION, Option.VOW_OF_ENMITY.cost());
        assertEquals(ActionCost.REACTION, Option.WAR_GODS_BLESSING.cost());
        assertEquals(ActionCost.FREE, Option.GUIDED_STRIKE.cost());
        assertEquals(ActionCost.ACTION, Option.TURN_UNDEAD.cost());
    }

    @Test
    @DisplayName("Turning options affect their creature types, a stat block's choice of types included")
    void creatureTypes() {
        assertTrue(ChannelDivinity.ofType("undead", Option.TURN_UNDEAD.turnedTypes()));
        assertFalse(ChannelDivinity.ofType("fiend", Option.TURN_UNDEAD.turnedTypes()));
        assertTrue(ChannelDivinity.ofType("fiend (demon)", Option.TURN_THE_UNHOLY.turnedTypes()));
        assertTrue(ChannelDivinity.ofType("Fey", Option.TURN_THE_FAITHLESS.turnedTypes()));
        assertTrue(ChannelDivinity.ofType("swarm of Tiny beasts", Option.CHARM_ANIMALS_AND_PLANTS.turnedTypes()));
        assertFalse(ChannelDivinity.ofType("humanoid", Set.of("undead")));
        assertFalse(ChannelDivinity.ofType(null, Set.of("undead")));
        assertTrue(ChannelDivinity.resistsTurning("[{\"name\": \"Turn Defiance\"}]"));
        assertFalse(ChannelDivinity.resistsTurning("[{\"name\": \"Sunlight Sensitivity\"}]"));
    }

    @Test
    @DisplayName("Save DCs, Radiance of the Dawn's damage and Sacred Weapon's bonus")
    void numbers() {
        assertEquals(14, ChannelDivinity.saveDc(3, 16));
        assertEquals(Ability.WIS, ChannelDivinity.saveAbility("Cleric"));
        assertEquals(Ability.CHA, ChannelDivinity.saveAbility("Paladin"));
        assertEquals(new Dice(2, 10, 7), ChannelDivinity.radianceOfTheDawn(7));
        assertEquals(1, ChannelDivinity.sacredWeaponBonus(8));
        assertEquals(1, ChannelDivinity.sacredWeaponBonus(12));
        assertEquals(4, ChannelDivinity.sacredWeaponBonus(18));
    }

    @Test
    @DisplayName("Preserve Life stops at half the maximum, rounded down, and has no effect on undead or constructs")
    void preserveLife() {
        assertTrue(ChannelDivinity.preserveLifeRefusal("Vex", null, 10, 41, 10).isEmpty());
        assertTrue(ChannelDivinity.preserveLifeRefusal("Vex", null, 10, 41, 11).isPresent());
        assertTrue(ChannelDivinity.preserveLifeRefusal("Vex", null, 25, 40, 1).isPresent());
        assertTrue(ChannelDivinity.preserveLifeRefusal("Golem", "construct", 1, 40, 1).isPresent());
        assertTrue(ChannelDivinity.preserveLifeRefusal("Zombie", "undead", 1, 40, 1).isPresent());
    }

    @ParameterizedTest(name = "Bard {0}: d{1}")
    @CsvSource({"1, 6", "4, 6", "5, 8", "9, 8", "10, 10", "14, 10", "15, 12", "20, 12"})
    @DisplayName("The Bardic Inspiration die by bard level (PHB p.53–54)")
    void bardicInspirationDie(int level, int sides) {
        assertEquals(sides, ClassFeatures.bardicInspirationDie(level));
    }

    @Test
    @DisplayName("Supreme Healing uses each die's highest number: 2d6 restores 12, as the PHB's example (p.60)")
    void supremeHealing() {
        assertEquals(new Dice(0, 0, 12), ClassFeatures.supremeHealing(new Dice(2, 6, 0)));
        assertEquals(new Dice(0, 0, 20), ClassFeatures.supremeHealing(new Dice(2, 8, 4)));
        assertEquals(new Dice(0, 0, 70), ClassFeatures.supremeHealing(new Dice(0, 0, 70)));
    }

    @Test
    @DisplayName("Disciple of Life adds 2 + the spell's level, nothing to a cantrip (PHB p.60)")
    void discipleOfLife() {
        assertEquals(0, ClassFeatures.discipleOfLife(0));
        assertEquals(3, ClassFeatures.discipleOfLife(1));
        assertEquals(11, ClassFeatures.discipleOfLife(9));
        assertThrows(IllegalArgumentException.class, () -> ClassFeatures.bardicInspirationDie(0));
    }
}
