package com.tabletophelper.reference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An upcast modifier's steps with and without maxSteps, and a target scaling that adds to or replaces its base (D225). */
class SpellTemplateScalingTest {

    @Test
    @DisplayName("Steps of two slot levels stop at maxSteps when it is set and go on without it")
    void modifierUpcastSteps() {
        var capped = new SpellTemplate.ModifierUpcast(2, 1, null, 2);
        var uncapped = new SpellTemplate.ModifierUpcast(2, 1, null, null);
        int[][] expected = {{2, 0, 0}, {3, 0, 0}, {4, 1, 1}, {6, 2, 2}, {8, 2, 3}, {9, 2, 3}};
        for (int[] row : expected) {
            assertEquals(row[1], capped.steps(2, row[0]), "capped, slot " + row[0]);
            assertEquals(row[2], uncapped.steps(2, row[0]), "uncapped, slot " + row[0]);
        }
        assertEquals(0, new SpellTemplate.ModifierUpcast(null, 1, null, null).steps(5, 3));
    }

    @Test
    @DisplayName("Targets per level add to the base, as Bless's (PHB p.219), or replace it when upcast, as "
            + "Etherealness's (PHB p.238)")
    void targetScaling() {
        var adds = new SpellTemplate.TargetScaling(1, false);
        assertEquals(3, adds.maxTargets(3, 1, 1));
        assertEquals(5, adds.maxTargets(3, 1, 3));
        var replaces = new SpellTemplate.TargetScaling(3, true);
        assertEquals(1, replaces.maxTargets(1, 7, 7));
        assertEquals(3, replaces.maxTargets(1, 7, 8));
        assertEquals(6, replaces.maxTargets(1, 7, 9));
        assertEquals(2, new SpellTemplate.TargetScaling(null, true).maxTargets(2, 1, 5));
    }
}
