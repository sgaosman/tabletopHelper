package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The class features' rules (M17), typed out from the 2014 PHB: the Barbarian table's Rage Damage column (p.46), what
 * Rage resists (p.48), Second Wind's healing (p.72) and what Lay on Hands can't touch (p.84).
 */
class ClassFeaturesTest {

    @Test
    @DisplayName("Rage Damage by barbarian level, as the Barbarian table gives it: +2 at 1-8, +3 at 9-15, +4 at 16-20")
    void rageDamageGolden() {
        // Levels 1 to 20, from the Rage Damage column of PHB p.46
        int[] expected = {2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4};
        for (int level = 1; level <= 20; level++) {
            assertEquals(expected[level - 1], ClassFeatures.rageDamage(level), "level " + level);
        }
        assertThrows(IllegalArgumentException.class, () -> ClassFeatures.rageDamage(0));
        assertThrows(IllegalArgumentException.class, () -> ClassFeatures.rageDamage(21));
    }

    @Test
    @DisplayName("Rage resists bludgeoning, piercing and slashing damage in any case, and nothing else")
    void rageResistances() {
        assertTrue(ClassFeatures.rageResists("bludgeoning"));
        assertTrue(ClassFeatures.rageResists("Piercing"));
        assertTrue(ClassFeatures.rageResists(" slashing "));
        for (String type : List.of("fire", "cold", "poison", "psychic", "force", "radiant")) {
            assertFalse(ClassFeatures.rageResists(type), type);
        }
        assertFalse(ClassFeatures.rageResists(null));
    }

    @Test
    @DisplayName("Rage's damage and Reckless Attack's advantage are for melee weapon attacks using Strength only")
    void meleeStrength() {
        assertTrue(ClassFeatures.meleeStrengthAttack(false, Ability.STR));
        assertFalse(ClassFeatures.meleeStrengthAttack(false, Ability.DEX), "a finesse weapon used with Dexterity");
        assertFalse(ClassFeatures.meleeStrengthAttack(true, Ability.DEX), "a ranged weapon");
    }

    @Test
    @DisplayName("Second Wind heals 1d10 + the fighter's level")
    void secondWind() {
        assertEquals(new Dice(1, 10, 1), ClassFeatures.secondWind(1));
        assertEquals(new Dice(1, 10, 5), ClassFeatures.secondWind(5));
        assertEquals(new Dice(1, 10, 20), ClassFeatures.secondWind(20));
        assertThrows(IllegalArgumentException.class, () -> ClassFeatures.secondWind(0));
    }

    @Test
    @DisplayName("Lay on Hands has no effect on undead or constructs; any other creature, or one without a type, is "
            + "fine")
    void layOnHands() {
        assertEquals(Optional.of("Lay on Hands has no effect on undead"), ClassFeatures.layOnHandsRefusal("Undead"));
        assertEquals(Optional.of("Lay on Hands has no effect on constructs"),
                ClassFeatures.layOnHandsRefusal("construct"));
        assertTrue(ClassFeatures.layOnHandsRefusal("humanoid").isEmpty());
        assertTrue(ClassFeatures.layOnHandsRefusal(null).isEmpty());
    }

    @Test
    @DisplayName("The markers are read from condition names in any case")
    void markers() {
        assertTrue(ClassFeatures.raging(List.of("prone", "Raging")));
        assertFalse(ClassFeatures.raging(List.of("prone")));
        assertTrue(ClassFeatures.reckless(List.of("reckless")));
        assertFalse(ClassFeatures.reckless(null));
    }
}
