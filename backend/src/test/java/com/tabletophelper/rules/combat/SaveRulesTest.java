package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @ParameterizedTest(name = "{0} save while {1} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            DEX | dodging            | ADVANTAGE
            DEX | Dodging prone      | ADVANTAGE
            STR | dodging            | NORMAL
            CON | dodging            | NORMAL
            DEX | -                  | NORMAL
            DEX | dodging incapacitated | NORMAL
            DEX | dodging restrained | DISADVANTAGE
            """)
    @DisplayName("A dodging creature has advantage on DEX saves while it keeps the benefit (PHB p.192)")
    void dodgingDexSaves(Ability ability, String conditions, RollMode expected) {
        List<String> names = "-".equals(conditions) ? List.of() : List.of(conditions.split("\\s+"));
        assertEquals(expected, SaveRules.rollMode(ability, names));
    }

    @ParameterizedTest(name = "{0} save while {1} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            DEX | restrained          | DISADVANTAGE
            DEX | Restrained          | DISADVANTAGE
            STR | restrained          | NORMAL
            CON | restrained          | NORMAL
            DEX | grappled            | NORMAL
            """)
    @DisplayName("A restrained creature has disadvantage on DEX saves (PHB p.292)")
    void restrainedDexSaves(Ability ability, String conditions, RollMode expected) {
        assertEquals(expected, SaveRules.rollMode(ability, List.of(conditions)));
    }

    @Test
    @DisplayName("Restrained and armour worn without proficiency still give one disadvantage on DEX saves (PHB p.173)")
    void restrainedAndArmour() {
        assertEquals(RollMode.DISADVANTAGE, SaveRules.rollMode(Ability.DEX, List.of("restrained"), true));
    }

    @Test
    @DisplayName("Condition names match whatever their case and surrounding spaces")
    void conditionNamesIgnoreCase() {
        assertTrue(SaveRules.autoFails(Ability.STR, List.of("Paralyzed")));
        assertTrue(SaveRules.autoFails(Ability.DEX, List.of(" STUNNED ")));
    }

    @Test
    @DisplayName("Armour worn without proficiency gives disadvantage on STR and DEX saves only (PHB p.144)")
    void armourPenaltyOnStrengthAndDexteritySaves() {
        assertEquals(RollMode.DISADVANTAGE, SaveRules.rollMode(Ability.STR, List.of(), true));
        assertEquals(RollMode.DISADVANTAGE, SaveRules.rollMode(Ability.DEX, List.of(), true));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.CON, List.of(), true));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.WIS, List.of(), true));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.DEX, List.of(), false));
    }

    @Test
    @DisplayName("The Dodge's advantage on DEX saves and the armour's disadvantage cancel (PHB p.173)")
    void dodgeAndArmourCancel() {
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.DEX, List.of(StandardActions.DODGING), true));
    }

    @Test
    @DisplayName("A raging creature has advantage on STR saves only (PHB p.48), cancelled by armour's disadvantage")
    void ragingStrengthSaves() {
        assertEquals(RollMode.ADVANTAGE, SaveRules.rollMode(Ability.STR, List.of(ClassFeatures.RAGING)));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.DEX, List.of(ClassFeatures.RAGING)));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.CON, List.of(ClassFeatures.RAGING)));
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.STR, List.of(ClassFeatures.RAGING), true));
    }
}
