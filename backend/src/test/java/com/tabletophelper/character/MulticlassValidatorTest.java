package com.tabletophelper.character;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.EligibleClassResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MulticlassValidatorTest {

    private static final ObjectMapper MAPPER = JacksonDefaults.mapper();

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
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, null, pc));
    }

    @Test
    @DisplayName("Empty requirements means no prerequisites")
    void emptyRequirements() {
        var pc = characterWithScores(10, 10, 10, 10, 10, 10);
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, "[]", pc));
    }

    @Test
    @DisplayName("AND prerequisites require all abilities to meet minimum")
    void andPrerequisitesAllMet() {
        var pc = characterWithScores(13, 13, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"AND\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"AND\"}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, pc));
    }

    @Test
    @DisplayName("AND prerequisites fail if any ability is below minimum")
    void andPrerequisitesOneFails() {
        var pc = characterWithScores(13, 12, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"AND\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"AND\"}]";
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, pc));
    }

    @Test
    @DisplayName("OR prerequisites pass if any ability meets minimum")
    void orPrerequisitesOneMet() {
        var pc = characterWithScores(13, 8, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, pc));
    }

    @Test
    @DisplayName("OR prerequisites fail if no ability meets minimum")
    void orPrerequisitesNoneMet() {
        var pc = characterWithScores(12, 8, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]";
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, pc));
    }

    @Test
    @DisplayName("classLevels handles null")
    void classLevelsNull() {
        Map<UUID, Integer> result = MulticlassValidator.classLevels(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("classLevels reads each class's level, by class ID")
    void classLevelsValid() {
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();
        List<ClassEntry> entries = List.of(new ClassEntry(fighterId, "Fighter", null, null, 5),
                new ClassEntry(wizardId, "Wizard", null, null, 3));
        Map<UUID, Integer> result = MulticlassValidator.classLevels(entries);
        assertEquals(2, result.size());
        assertEquals(5, result.get(fighterId));
        assertEquals(3, result.get(wizardId));
    }

    @Test
    @DisplayName("Single ability prerequisite (Paladin: STR 13 AND CHA 13)")
    void paladinPrereqs() {
        var passes = characterWithScores(14, 10, 10, 10, 10, 14);
        var fails = characterWithScores(14, 10, 10, 10, 10, 12);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13},{\"ability\":\"CHA\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, fails));
    }

    @Test
    @DisplayName("Ranger prerequisites: DEX 13 AND WIS 13")
    void rangerPrereqs() {
        var passes = characterWithScores(10, 14, 10, 10, 16, 10);
        var fails = characterWithScores(10, 14, 10, 10, 11, 10);
        String reqs = "[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, fails));
    }

    @Test
    @DisplayName("Monk prerequisites: DEX 13 AND WIS 13, exact threshold passes")
    void monkPrereqs() {
        var passes = characterWithScores(10, 13, 10, 10, 13, 10);
        var fails = characterWithScores(10, 12, 10, 10, 13, 10);
        String reqs = "[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, fails));
    }

    @Test
    @DisplayName("Bard/Sorcerer/Warlock: CHA 13 only, other scores irrelevant")
    void charismaOnlyPrereqs() {
        var passes = characterWithScores(8, 8, 8, 8, 8, 14);
        var fails = characterWithScores(20, 20, 20, 20, 20, 12);
        String reqs = "[{\"ability\":\"CHA\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, passes));
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, fails));
    }

    @Test
    @DisplayName("Score exactly at threshold passes, one below fails")
    void exactThresholdBoundary() {
        var atThreshold = characterWithScores(13, 10, 10, 10, 10, 10);
        var belowThreshold = characterWithScores(12, 10, 10, 10, 10, 10);
        String reqs = "[{\"ability\":\"STR\",\"minimum\":13}]";
        assertTrue(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, atThreshold),
                "Score of exactly 13 should pass");
        assertFalse(MulticlassValidator.meetsPrerequisites(MAPPER, reqs, belowThreshold),
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
                .multiclassEntries(List.of(new ClassEntry(fId, "Fighter", null, null, 3)))
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

        List<EligibleClassResponse> results = MulticlassValidator.getEligibleClasses(MAPPER, pc,
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

    @Test
    @DisplayName("Requirements that can't be read are a 409, never no prerequisites (D155)")
    void unreadableRequirementsAreConflict() {
        var pc = characterWithScores(10, 10, 10, 10, 10, 10);

        assertThrows(ConflictException.class, () -> MulticlassValidator.meetsPrerequisites(MAPPER, "{not json", pc));
        assertThrows(ConflictException.class,
                () -> MulticlassValidator.meetsPrerequisites(MAPPER, "[{\"ability\":\"Luck\",\"minimum\":13}]", pc));
    }

    @Test
    @DisplayName("unmetPrerequisites names the new class, then each current class the scores miss; none for a class the character has")
    void unmetPrerequisitesOfEveryClass() {
        var pc = characterWithScores(16, 14, 12, 10, 10, 12);
        CharacterClass fighter = CharacterClass.builder().id(UUID.randomUUID()).name("Fighter")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},"
                        + "{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]").build();
        CharacterClass paladin = CharacterClass.builder().id(UUID.randomUUID()).name("Paladin")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13},{\"ability\":\"CHA\",\"minimum\":13}]")
                .build();
        CharacterClass bard = CharacterClass.builder().id(UUID.randomUUID()).name("Bard")
                .multiclassRequirements("[{\"ability\":\"CHA\",\"minimum\":13}]").build();

        assertEquals(List.of("Bard", "Paladin"),
                MulticlassValidator.unmetPrerequisites(MAPPER, pc, bard, List.of(fighter, paladin)));
        assertEquals(List.of(), MulticlassValidator.unmetPrerequisites(MAPPER, pc, paladin, List.of(fighter, paladin)));
    }
}
