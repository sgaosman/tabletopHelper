package com.tabletophelper.rules.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Tasha's spirit's statistics at each slot level, typed out from the stat blocks (TCE p.109–114, D182), never
 * worked out from the data file: AC 11 + the spell's level (12 + it for the fey), hit points by variant, half the
 * spell's level of attacks rounded down, and damage plus the spell's level.
 */
class SpiritScalingTest {

    // spell, variant, AC base, HP base, HP per level, base level, then AC and HP at each slot level from the base to 9th
    @ParameterizedTest(name = "{0} ({1}) at level {6}: AC {7}, HP {8}")
    @CsvSource({
            // Summon Beast: AC 11 + level; Air 20 + 5 per level above 2nd, Land and Water 30 + 5 (TCE p.109)
            "Beast,Air,11,20,5,2,2,13,20", "Beast,Air,11,20,5,2,3,14,25", "Beast,Air,11,20,5,2,9,20,55",
            "Beast,Land,11,30,5,2,2,13,30", "Beast,Land,11,30,5,2,4,15,40", "Beast,Land,11,30,5,2,9,20,65",
            "Beast,Water,11,30,5,2,3,14,35", "Beast,Water,11,30,5,2,7,18,55",
            // Summon Fey: AC 12 + level; 30 + 10 per level above 3rd (TCE p.112)
            "Fey,Fuming,12,30,10,3,3,15,30", "Fey,Mirthful,12,30,10,3,6,18,60", "Fey,Tricksy,12,30,10,3,9,21,90",
            // Summon Shadowspawn: AC 11 + level; 35 + 15 per level above 3rd (TCE p.114)
            "Shadow,Fury,11,35,15,3,3,14,35", "Shadow,Despair,11,35,15,3,5,16,65", "Shadow,Fear,11,35,15,3,9,20,125",
            // Summon Undead: AC 11 + level; Ghostly and Putrid 30 + 10, Skeletal 20 + 10 per level above 3rd (p.114)
            "Undead,Ghostly,11,30,10,3,3,14,30", "Undead,Putrid,11,30,10,3,8,19,80",
            "Undead,Skeletal,11,20,10,3,3,14,20", "Undead,Skeletal,11,20,10,3,9,20,80"})
    @DisplayName("AC and hit points at each slot level")
    void armourClassAndHitPoints(String spirit, String variant, int acBase, int hpBase, int hpPerLevel, int baseLevel,
                                 int slot, int ac, int hp) {
        assertEquals(ac, SpiritScaling.armourClass(acBase, slot));
        assertEquals(hp, SpiritScaling.hitPoints(hpBase, hpPerLevel, baseLevel, slot));
    }

    @ParameterizedTest(name = "level {0}: {1} attacks")
    @CsvSource({"2,1", "3,1", "4,2", "5,2", "6,3", "7,3", "8,4", "9,4"})
    @DisplayName("Multiattack: half the spell's level, rounded down")
    void attacks(int slot, int attacks) {
        assertEquals(attacks, SpiritScaling.attacks(slot));
    }

    @ParameterizedTest(name = "{0} at level {1} is {2}")
    @CsvSource({"1d8 + 4 + LEVEL,2,1d8 + 6", "1d8 + 4 + LEVEL,4,1d8 + 8", "1d6 + 3 + LEVEL,3,1d6 + 6",
            "1d12 + 3 + LEVEL,9,1d12 + 12", "2d4+3+LEVEL,5,2d4 + 8", "1d6,5,1d6"})
    @DisplayName("Damage adds the spell's level to its flat part")
    void damage(String expression, int slot, String expected) {
        assertEquals(expected, SpiritScaling.damage(expression, slot));
    }

    @Test
    @DisplayName("A slot below the spell's own level counts as its own")
    void hitPoints_belowBase() {
        assertEquals(30, SpiritScaling.hitPoints(30, 10, 3, 2));
    }
}
