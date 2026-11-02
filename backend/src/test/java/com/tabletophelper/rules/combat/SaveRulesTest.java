package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveRulesTest {

    @ParameterizedTest(name = "{0} save while {1}")
    @CsvSource({
            "STR, unconscious", "DEX, unconscious",
            "STR, paralyzed", "DEX, paralyzed",
            "STR, stunned", "DEX, stunned",
            "STR, petrified", "DEX, petrified",
    })
    @DisplayName("Strength and Dexterity saves fail automatically while unconscious, paralyzed, stunned or petrified")
    void strengthAndDexteritySavesFail(Ability ability, String condition) {
        assertTrue(SaveRules.autoFails(ability, List.of(condition)));
    }

    @ParameterizedTest(name = "{0} save while {1}")
    @CsvSource({
            "CON, unconscious", "INT, paralyzed", "WIS, stunned", "CHA, petrified",
    })
    @DisplayName("Other saves are rolled as usual under the same conditions")
    void otherSavesAreRolled(Ability ability, String condition) {
        assertFalse(SaveRules.autoFails(ability, List.of(condition)));
    }

    @Test
    @DisplayName("Without one of those conditions no save fails automatically")
    void otherConditionsDontFailSaves() {
        assertFalse(SaveRules.autoFails(Ability.DEX, List.of()));
        assertFalse(SaveRules.autoFails(Ability.DEX, List.of("prone", "poisoned", "restrained", "incapacitated")));
    }

    @Test
    @DisplayName("Condition names match whatever their case and surrounding spaces")
    void conditionNamesIgnoreCase() {
        assertTrue(SaveRules.autoFails(Ability.STR, List.of("Paralyzed")));
        assertTrue(SaveRules.autoFails(Ability.DEX, List.of(" STUNNED ")));
    }
}
