package com.tabletophelper.character;

import com.tabletophelper.character.dto.EligibleClassResponse;
import com.tabletophelper.reference.CharacterClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MulticlassValidatorTest {

    private PlayerCharacter characterWithScores(int str, int dex, int con, int intel, int wis, int cha) {
        return PlayerCharacter.builder()
                .strength(str).dexterity(dex).constitution(con)
                .intelligence(intel).wisdom(wis).charisma(cha)
                .build();
    }

    @Test
    @DisplayName("Null requirements means no prerequisites")
    void nullRequirements() {
        var pc = characterWithScores(10, 10, 10, 10, 10, 10);
        assertTrue(MulticlassValidator.meetsPrerequisites(null, pc));
    }

    @Test
    @DisplayName("Empty requirements means no prerequisites")
    void emptyRequirements() {
        var pc = characterWithScores(10, 10, 10, 10, 10, 10);
        assertTrue(MulticlassValidator.meetsPrerequisites("[]", pc));
    }

    @Test
    @DisplayName("AND prerequisites require all abilities to meet minimum")
    void andPrerequisitesAllMet() {
        var pc = characterWithScores(13, 13, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"AND\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"AND\"}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, pc));
    }

    @Test
    @DisplayName("AND prerequisites fail if any ability is below minimum")
    void andPrerequisitesOneFails() {
        var pc = characterWithScores(13, 12, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"AND\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"AND\"}]";
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, pc));
    }

    @Test
    @DisplayName("OR prerequisites pass if any ability meets minimum")
    void orPrerequisitesOneMet() {
        var pc = characterWithScores(13, 8, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, pc));
    }

    @Test
    @DisplayName("OR prerequisites fail if no ability meets minimum")
    void orPrerequisitesNoneMet() {
        var pc = characterWithScores(12, 8, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]";
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, pc));
    }

    @Test
    @DisplayName("parseMulticlassEntries handles null")
    void parseEntriesNull() {
        Map<String, Integer> result = MulticlassValidator.parseMulticlassEntries(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parseMulticlassEntries parses valid JSON")
    void parseEntriesValid() {
        String json = "[{\"classId\":\"abc-123\",\"className\":\"Fighter\",\"level\":5},{\"classId\":\"def-456\",\"className\":\"Wizard\",\"level\":3}]";
        Map<String, Integer> result = MulticlassValidator.parseMulticlassEntries(json);
        assertEquals(2, result.size());
        assertEquals(5, result.get("abc-123"));
        assertEquals(3, result.get("def-456"));
    }

    @Test
    @DisplayName("Single ability prerequisite (Paladin: STR 13 AND CHA 13)")
    void paladinPrereqs() {
        var passes = characterWithScores(14, 10, 10, 10, 10, 14);
        var fails = characterWithScores(14, 10, 10, 10, 10, 12);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13},{\"ability\":\"CHA\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, fails));
    }

    @Test
    @DisplayName("Ranger prerequisites: DEX 13 AND WIS 13")
    void rangerPrereqs() {
        var passes = characterWithScores(10, 14, 10, 10, 16, 10);
        var fails = characterWithScores(10, 14, 10, 10, 11, 10);
        String reqs = "[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, fails));
    }

    @Test
    @DisplayName("Monk prerequisites: DEX 13 AND WIS 13, exact threshold passes")
    void monkPrereqs() {
        var passes = characterWithScores(10, 13, 10, 10, 13, 10);
        var fails = characterWithScores(10, 12, 10, 10, 13, 10);
        String reqs = "[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, fails));
    }

    @Test
    @DisplayName("Bard/Sorcerer/Warlock: CHA 13 only, other scores irrelevant")
    void charismaOnlyPrereqs() {
        var passes = characterWithScores(8, 8, 8, 8, 8, 14);
        var fails = characterWithScores(20, 20, 20, 20, 20, 12);
        String reqs = "[{\"ability\":\"CHA\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, fails));
    }

    @Test
    @DisplayName("Score exactly at threshold passes, one below fails")
    void exactThresholdBoundary() {
        var atThreshold = characterWithScores(13, 10, 10, 10, 10, 10);
        var belowThreshold = characterWithScores(12, 10, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(reqs, atThreshold),
                "Score of exactly 13 should pass");
        assertFalse(MulticlassValidator.meetsPrerequisites(reqs, belowThreshold),
                "Score of 12 should fail");
    }

    @Test
    @DisplayName("getEligibleClasses excludes current class from eligible multiclass options")
    void getEligibleClassesExcludesCurrentClass() {
        UUID fId = UUID.randomUUID();
        UUID rogueId = UUID.randomUUID();
        UUID bardId = UUID.randomUUID();

        var pc = PlayerCharacter.builder()
                .strength(16).dexterity(14).constitution(14)
                .intelligence(10).wisdom(12).charisma(8)
                .multiclassEntries("[{\"classId\":\"" + fId + "\",\"className\":\"Fighter\",\"level\":3}]")
                .build();

        CharacterClass fighterClass = CharacterClass.builder()
                .id(fId).name("Fighter").hitDice(10)
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13}]")
                .build();

        CharacterClass rogueClass = CharacterClass.builder()
                .id(rogueId).name("Rogue").hitDice(8)
                .multiclassRequirements("[{\"ability\":\"DEX\",\"minimum\":13}]")
                .build();

        CharacterClass bardClass = CharacterClass.builder()
                .id(bardId).name("Bard").hitDice(8)
                .multiclassRequirements("[{\"ability\":\"CHA\",\"minimum\":13}]")
                .build();

        List<EligibleClassResponse> results = MulticlassValidator.getEligibleClasses(pc,
                List.of(fighterClass, rogueClass, bardClass));

        // Fighter should be marked as current class
        EligibleClassResponse fighterResult = results.stream()
                .filter(r -> r.getClassName().equals("Fighter")).findFirst().orElseThrow();
        assertTrue(fighterResult.isCurrentClass());

        // Rogue should be eligible (DEX 14 >= 13)
        EligibleClassResponse rogueResult = results.stream()
                .filter(r -> r.getClassName().equals("Rogue")).findFirst().orElseThrow();
        assertFalse(rogueResult.isCurrentClass());
        assertTrue(rogueResult.isMeetsPrerequisites());

        // Bard should not be eligible (CHA 8 < 13)
        EligibleClassResponse bardResult = results.stream()
                .filter(r -> r.getClassName().equals("Bard")).findFirst().orElseThrow();
        assertFalse(bardResult.isCurrentClass());
        assertFalse(bardResult.isMeetsPrerequisites());
    }
}
