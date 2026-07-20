package com.tabletophelper.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

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
}
