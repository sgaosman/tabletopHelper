package com.tabletophelper.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class CharacterServiceStaticTest {

    @Test
    @DisplayName("abilityMod calculates floor((score - 10) / 2)")
    void abilityModCalculation() {
        assertEquals(-5, CharacterService.abilityMod(1));
        assertEquals(-1, CharacterService.abilityMod(8));
        assertEquals(0, CharacterService.abilityMod(10));
        assertEquals(0, CharacterService.abilityMod(11));
        assertEquals(1, CharacterService.abilityMod(12));
        assertEquals(2, CharacterService.abilityMod(14));
        assertEquals(5, CharacterService.abilityMod(20));
    }

    @Test
    @DisplayName("abilityMod handles null")
    void abilityModNull() {
        assertEquals(0, CharacterService.abilityMod(null));
    }

    @Test
    @DisplayName("proficiencyBonus follows 5e table")
    void proficiencyBonusTable() {
        assertEquals(2, CharacterService.proficiencyBonusForLevel(1));
        assertEquals(2, CharacterService.proficiencyBonusForLevel(4));
        assertEquals(3, CharacterService.proficiencyBonusForLevel(5));
        assertEquals(3, CharacterService.proficiencyBonusForLevel(8));
        assertEquals(4, CharacterService.proficiencyBonusForLevel(9));
        assertEquals(4, CharacterService.proficiencyBonusForLevel(12));
        assertEquals(5, CharacterService.proficiencyBonusForLevel(13));
        assertEquals(5, CharacterService.proficiencyBonusForLevel(16));
        assertEquals(6, CharacterService.proficiencyBonusForLevel(17));
        assertEquals(6, CharacterService.proficiencyBonusForLevel(20));
    }

    @Test
    @DisplayName("Rogue expertise at level 1 and 6")
    void rogueExpertise() {
        assertTrue(CharacterService.isExpertiseLevel("Rogue", 1));
        assertTrue(CharacterService.isExpertiseLevel("Rogue", 6));
        assertFalse(CharacterService.isExpertiseLevel("Rogue", 3));
    }

    @Test
    @DisplayName("Bard expertise at level 3 and 10")
    void bardExpertise() {
        assertTrue(CharacterService.isExpertiseLevel("Bard", 3));
        assertTrue(CharacterService.isExpertiseLevel("Bard", 10));
        assertFalse(CharacterService.isExpertiseLevel("Bard", 1));
    }

    @Test
    @DisplayName("Other classes have no expertise levels")
    void noExpertise() {
        assertFalse(CharacterService.isExpertiseLevel("Fighter", 1));
        assertFalse(CharacterService.isExpertiseLevel("Fighter", 6));
        assertFalse(CharacterService.isExpertiseLevel("Wizard", 3));
    }

    @Test
    @DisplayName("getAbilityMod extracts correct modifier from character")
    void getAbilityModFromCharacter() {
        PlayerCharacter pc = PlayerCharacter.builder()
                .strength(16).dexterity(14).constitution(12)
                .intelligence(10).wisdom(8).charisma(18)
                .build();
        assertEquals(3, CharacterService.getAbilityMod("STR", pc));
        assertEquals(2, CharacterService.getAbilityMod("DEX", pc));
        assertEquals(1, CharacterService.getAbilityMod("CON", pc));
        assertEquals(0, CharacterService.getAbilityMod("INT", pc));
        assertEquals(-1, CharacterService.getAbilityMod("WIS", pc));
        assertEquals(4, CharacterService.getAbilityMod("CHA", pc));
    }

    @Test
    @DisplayName("getAbilityMod handles null ability")
    void getAbilityModNull() {
        PlayerCharacter pc = PlayerCharacter.builder()
                .strength(10).dexterity(10).constitution(10)
                .intelligence(10).wisdom(10).charisma(10)
                .build();
        assertEquals(0, CharacterService.getAbilityMod(null, pc));
    }

    @Test
    @DisplayName("abilityMod extreme values: score 1 gives -5, score 30 gives +10")
    void abilityModExtremeValues() {
        assertEquals(-5, CharacterService.abilityMod(1));
        assertEquals(10, CharacterService.abilityMod(30));
    }

    @Test
    @DisplayName("proficiencyBonus at boundary transitions: 4->5 and 8->9")
    void proficiencyBonusBoundaryTransitions() {
        // Level 4 is the last level with +2
        assertEquals(2, CharacterService.proficiencyBonusForLevel(4));
        // Level 5 transitions to +3
        assertEquals(3, CharacterService.proficiencyBonusForLevel(5));
        // Level 8 is the last level with +3
        assertEquals(3, CharacterService.proficiencyBonusForLevel(8));
        // Level 9 transitions to +4
        assertEquals(4, CharacterService.proficiencyBonusForLevel(9));
    }
}
