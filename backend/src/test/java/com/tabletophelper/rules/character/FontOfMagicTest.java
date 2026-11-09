package com.tabletophelper.rules.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Font of Magic's Flexible Casting (PHB p.101): the Creating Spell Slots table and the sorcery points a slot gives.
 */
class FontOfMagicTest {

    @Test
    @DisplayName("Creating a slot costs 2, 3, 5, 6 and 7 sorcery points for the 1st to the 5th level, as the table gives")
    void creationCostGolden() {
        int[] expected = {2, 3, 5, 6, 7};
        for (int level = 1; level <= 5; level++) {
            assertEquals(expected[level - 1], FontOfMagic.creationCost(level), "level " + level);
            assertTrue(FontOfMagic.canCreate(level));
        }
    }

    @Test
    @DisplayName("No slot above the 5th level, nor of level 0, can be created")
    void creationLimits() {
        assertFalse(FontOfMagic.canCreate(0));
        assertFalse(FontOfMagic.canCreate(6));
        assertFalse(FontOfMagic.canCreate(9));
        assertThrows(IllegalArgumentException.class, () -> FontOfMagic.creationCost(0));
        assertThrows(IllegalArgumentException.class, () -> FontOfMagic.creationCost(6));
    }

    @Test
    @DisplayName("Expending a slot gives points equal to its level, never past the sorcerer's maximum")
    void pointsGained() {
        assertEquals(1, FontOfMagic.pointsGained(1, 0, 5));
        assertEquals(3, FontOfMagic.pointsGained(3, 0, 5));
        assertEquals(9, FontOfMagic.pointsGained(9, 0, 20));
        // 4 of 5 points: a 3rd-level slot gives only the 1 that fits
        assertEquals(1, FontOfMagic.pointsGained(3, 4, 5));
        assertEquals(0, FontOfMagic.pointsGained(2, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> FontOfMagic.pointsGained(0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> FontOfMagic.pointsGained(10, 0, 5));
    }
}
