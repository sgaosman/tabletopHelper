package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ability score maximum (PHB p.15) and Primal Champion's (the Barbarian, PHB p.46), and the part of an increase
 * that applies under them.
 */
class AbilityScoresTest {

    @Test
    @DisplayName("Every ability's maximum is 20, and Primal Champion raises Strength's and Constitution's to 24")
    void maximums() {
        for (Ability ability : Ability.values()) {
            assertEquals(20, AbilityScores.maximum(ability, false), ability.name());
        }
        assertEquals(24, AbilityScores.maximum(Ability.STR, true));
        assertEquals(24, AbilityScores.maximum(Ability.CON, true));
        assertEquals(20, AbilityScores.maximum(Ability.DEX, true));
        assertEquals(20, AbilityScores.maximum(Ability.INT, true));
        assertEquals(20, AbilityScores.maximum(Ability.WIS, true));
        assertEquals(20, AbilityScores.maximum(Ability.CHA, true));
    }

    @ParameterizedTest(name = "{0} {1} + {2}, Primal Champion {3}: {4} applies")
    @CsvSource({
            "STR, 16, 2, false, 2",
            // Only what reaches the maximum applies
            "STR, 19, 2, false, 1",
            "WIS, 20, 1, false, 0",
            "DEX, 20, 2, true, 0",
            // Primal Champion's own increase of 4 takes 20 to 24
            "STR, 20, 4, true, 4",
            "CON, 22, 4, true, 2",
            "CON, 24, 2, true, 0",
            // A score already above the maximum gains nothing and keeps what it has
            "CHA, 22, 2, false, 0",
            "DEX, 15, 0, false, 0",
    })
    @DisplayName("An increase applies only up to the ability's maximum")
    void increaseAppliesUpToTheMaximum(Ability ability, int score, int amount, boolean primalChampion, int applied) {
        assertEquals(applied, AbilityScores.increase(ability, score, amount, primalChampion));
    }

    @Test
    @DisplayName("A negative increase is refused")
    void negativeIncrease() {
        assertThrows(IllegalArgumentException.class, () -> AbilityScores.increase(Ability.STR, 16, -2, false));
    }
}
