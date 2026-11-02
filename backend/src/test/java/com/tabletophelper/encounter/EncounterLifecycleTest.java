package com.tabletophelper.encounter;

import com.tabletophelper.encounter.EncounterLifecycle.Transition;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The encounter lifecycle (D166), with every expected value typed out: Preparing → Active ⇄ Paused →
 * Completed, and Completed is final.
 */
class EncounterLifecycleTest {

    private static Encounter encounterIn(EncounterStatus status) {
        return Encounter.builder().id(UUID.randomUUID()).name("Goblin Ambush").status(status).build();
    }

    @ParameterizedTest(name = "{0} to {1}: {2}")
    @CsvSource({
            "PREPARING, PREPARING, false",
            "PREPARING, ACTIVE,    true",
            "PREPARING, PAUSED,    false",
            "PREPARING, COMPLETED, false",
            "ACTIVE,    PREPARING, false",
            "ACTIVE,    ACTIVE,    false",
            "ACTIVE,    PAUSED,    true",
            "ACTIVE,    COMPLETED, true",
            "PAUSED,    PREPARING, false",
            "PAUSED,    ACTIVE,    true",
            "PAUSED,    PAUSED,    false",
            "PAUSED,    COMPLETED, true",
            "COMPLETED, PREPARING, false",
            "COMPLETED, ACTIVE,    false",
            "COMPLETED, PAUSED,    false",
            "COMPLETED, COMPLETED, false",
    })
    @DisplayName("Every pair of statuses: allowed only for start, pause, resume and complete")
    void everyPairOfStatuses(EncounterStatus from, EncounterStatus to, boolean allowed) {
        assertEquals(allowed, EncounterLifecycle.allows(from, to));
    }

    @Test
    @DisplayName("Exactly five of the sixteen pairs are allowed, so the table above misses none")
    void exactlyFivePairsAreAllowed() {
        int allowed = 0;
        for (EncounterStatus from : EncounterStatus.values()) {
            for (EncounterStatus to : EncounterStatus.values()) {
                if (EncounterLifecycle.allows(from, to)) {
                    allowed++;
                }
            }
        }

        assertEquals(16, EncounterStatus.values().length * EncounterStatus.values().length);
        assertEquals(5, allowed);
    }

    @ParameterizedTest(name = "{0} from {1}: {2}")
    @CsvSource({
            "START,    PREPARING, ACTIVE",
            "START,    ACTIVE,    refused",
            "START,    PAUSED,    refused",
            "START,    COMPLETED, refused",
            "PAUSE,    PREPARING, refused",
            "PAUSE,    ACTIVE,    PAUSED",
            "PAUSE,    PAUSED,    refused",
            "PAUSE,    COMPLETED, refused",
            "RESUME,   PREPARING, refused",
            "RESUME,   ACTIVE,    refused",
            "RESUME,   PAUSED,    ACTIVE",
            "RESUME,   COMPLETED, refused",
            "COMPLETE, PREPARING, refused",
            "COMPLETE, ACTIVE,    COMPLETED",
            "COMPLETE, PAUSED,    COMPLETED",
            "COMPLETE, COMPLETED, refused",
    })
    @DisplayName("Each command from each status: the status it leaves, or a 409 that changes nothing")
    void eachCommandFromEachStatus(Transition transition, EncounterStatus from, String expected) {
        Encounter encounter = encounterIn(from);

        if (expected.equals("refused")) {
            assertThrows(ConflictException.class, () -> EncounterLifecycle.apply(encounter, transition));
            assertEquals(from, encounter.getStatus());
        } else {
            EncounterLifecycle.apply(encounter, transition);
            assertEquals(EncounterStatus.valueOf(expected), encounter.getStatus());
        }
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource(delimiter = '|', value = {
            "START    | ACTIVE    | The encounter is already active",
            "START    | PAUSED    | Cannot start an encounter that is paused",
            "START    | COMPLETED | Cannot start an encounter that is completed",
            "PAUSE    | PREPARING | Cannot pause an encounter that is still being prepared",
            "PAUSE    | PAUSED    | The encounter is already paused",
            "PAUSE    | COMPLETED | Cannot pause an encounter that is completed",
            "RESUME   | PREPARING | Cannot resume an encounter that is still being prepared",
            "RESUME   | ACTIVE    | The encounter is already active",
            "RESUME   | COMPLETED | Cannot resume an encounter that is completed",
            "COMPLETE | PREPARING | Cannot complete an encounter that is still being prepared",
            "COMPLETE | COMPLETED | The encounter is already completed",
    })
    @DisplayName("A refused command says why")
    void refusalSaysWhy(Transition transition, EncounterStatus from, String message) {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> EncounterLifecycle.apply(encounterIn(from), transition));

        assertEquals(message, ex.getMessage());
    }

    @Test
    @DisplayName("require checks a command without changing the status")
    void requireChangesNothing() {
        Encounter encounter = encounterIn(EncounterStatus.PREPARING);

        EncounterLifecycle.require(encounter, Transition.START);

        assertEquals(EncounterStatus.PREPARING, encounter.getStatus());
    }

    @Test
    @DisplayName("Combat goes ahead in an Active encounter")
    void combatInActiveEncounter() {
        assertDoesNotThrow(() -> EncounterLifecycle.requireActive(encounterIn(EncounterStatus.ACTIVE)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "PAUSED    | The encounter is paused until the DM resumes it",
            "PREPARING | The encounter hasn't started yet",
            "COMPLETED | The encounter has ended",
    })
    @DisplayName("Combat in any other status is a 409 that says why; a Paused encounter only resumes or completes")
    void combatNeedsActiveEncounter(EncounterStatus status, String message) {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> EncounterLifecycle.requireActive(encounterIn(status)));

        assertEquals(message, ex.getMessage());
    }
}
