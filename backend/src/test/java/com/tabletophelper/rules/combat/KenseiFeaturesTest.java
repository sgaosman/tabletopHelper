package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Way of the Kensei's numbers (XGE p.34-35). */
class KenseiFeaturesTest {

    @Test
    @DisplayName("Agile Parry gives +2 AC unless the monk is incapacitated, by any condition that incapacitates")
    void agileParryBonus() {
        assertEquals(2, KenseiFeatures.agileParryBonus(List.of("prone")));
        assertEquals(0, KenseiFeatures.agileParryBonus(List.of("stunned")));
        assertEquals(0, KenseiFeatures.agileParryBonus(List.of("unconscious")));
    }

    @Test
    @DisplayName("Agile Parry follows only an unarmed strike of the Attack action on the monk's turn with a melee "
            + "kensei weapon held")
    void agileParry() {
        assertTrue(KenseiFeatures.agileParry(true, true, true, true));
        assertFalse(KenseiFeatures.agileParry(false, true, true, true));
        assertFalse(KenseiFeatures.agileParry(true, false, true, true));
        assertFalse(KenseiFeatures.agileParry(true, true, false, true));
        assertFalse(KenseiFeatures.agileParry(true, true, true, false));
    }

    @Test
    @DisplayName("Deft Strike is the martial arts die, doubled on a critical hit")
    void deftStrike() {
        assertEquals(new Dice(1, 6, 0), KenseiFeatures.deftStrike(6, false));
        assertEquals(new Dice(2, 8, 0), KenseiFeatures.deftStrike(11, true));
        assertEquals(new Dice(1, 10, 0), KenseiFeatures.deftStrike(20, false));
    }

    @Test
    @DisplayName("Sharpen the Blade spends 1 to 3 ki points")
    void sharpenTheBlade() {
        assertNull(KenseiFeatures.sharpenTheBladeRefusal(1));
        assertNull(KenseiFeatures.sharpenTheBladeRefusal(3));
        assertEquals("Sharpen the Blade spends 1 to 3 ki points, not 0", KenseiFeatures.sharpenTheBladeRefusal(0));
        assertEquals("Sharpen the Blade spends 1 to 3 ki points, not 4", KenseiFeatures.sharpenTheBladeRefusal(4));
    }
}
