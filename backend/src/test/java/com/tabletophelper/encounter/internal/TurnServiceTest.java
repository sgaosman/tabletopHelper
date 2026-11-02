package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The turn order as the DM moves through it. From the old CombatServiceTest: the turn cases 10.30 to 10.32,
 * condition expiry 10.27, only the DM advancing, an unknown encounter, and the Completed and Preparing
 * lifecycle case. Turn processing with end rules comes with R5 workstream E.
 */
class TurnServiceTest {

    private CombatFixture f;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
    }

    @Test
    @DisplayName("10.30 Advancing moves to the next participant in initiative order")
    void advanceTurnNextParticipant() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setIsCurrentTurn(true);
        EncounterParticipant wizard = f.player("Wizard", 20, 12);
        wizard.setActionUsed(true);
        f.monster("Goblin", 7, 15);

        f.turns.advanceTurn(f.encounterId(), f.dmUserId);

        assertFalse(fighter.getIsCurrentTurn());
        assertTrue(wizard.getIsCurrentTurn());
        assertFalse(wizard.getActionUsed());
        assertEquals(1, f.encounter.getCurrentTurnIndex());
        assertEquals(1, f.encounter.getRoundNumber());
        assertEquals(List.of("Turn passes to Wizard (Round 1)"), f.descriptions());
    }

    @Test
    @DisplayName("10.31 Advancing past the end of the order wraps and starts a new round")
    void advanceTurnWrapsRound() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        goblin.setIsCurrentTurn(true);
        f.encounter.setCurrentTurnIndex(1);

        f.turns.advanceTurn(f.encounterId(), f.dmUserId);

        assertTrue(fighter.getIsCurrentTurn());
        assertFalse(goblin.getIsCurrentTurn());
        assertEquals(0, f.encounter.getCurrentTurnIndex());
        assertEquals(2, f.encounter.getRoundNumber());
    }

    @Test
    @DisplayName("10.32 Going back moves to the previous participant")
    void previousTurnMovesBack() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        EncounterParticipant wizard = f.player("Wizard", 20, 12);
        wizard.setIsCurrentTurn(true);
        f.encounter.setCurrentTurnIndex(1);

        f.turns.previousTurn(f.encounterId(), f.dmUserId);

        assertTrue(fighter.getIsCurrentTurn());
        assertFalse(wizard.getIsCurrentTurn());
        assertEquals(0, f.encounter.getCurrentTurnIndex());
    }

    @Test
    @DisplayName("10.27 A condition expires on its creature's turn once its duration in rounds has passed")
    void conditionAutoExpires() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setIsCurrentTurn(true);
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);
        goblin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("blinded", 2, 1),
                ConditionEntry.manual("prone", null, 1))));
        f.encounter.setRoundNumber(3);

        f.turns.advanceTurn(f.encounterId(), f.dmUserId);

        assertEquals(List.of("prone"), CombatFixture.conditionNames(goblin), "Blinded should have expired after 2 rounds");
        assertTrue(f.descriptions().contains("blinded expires on Goblin"));
    }

    @Test
    @DisplayName("A new turn resets its participant's per-turn pools, such as legendary actions")
    void perTurnPoolsReset() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setIsCurrentTurn(true);
        EncounterParticipant dragon = f.monster("Dragon", 200, 19);
        dragon.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry("monster:legendary-actions",
                "Legendary Actions", "MONSTER", "Dragon", 3, "3", 0, "turn", null, null, "FREE", "zap", null))));

        f.turns.advanceTurn(f.encounterId(), f.dmUserId);

        assertEquals(3, dragon.getResourcePoolsCurrent().getFirst().currentUses());
    }

    @Test
    @DisplayName("Only the DM can advance the turn")
    void playerCannotAdvanceTurn() {
        EncounterParticipant rogue = f.player("Rogue", 20, 14);
        UUID playerUserId = f.playerControlling(rogue);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.turns.advanceTurn(f.encounterId(), playerUserId));

        assertEquals("Only the DM can perform this action", ex.getMessage());
    }

    @Test
    @DisplayName("An unknown encounter is a NotFoundException, and nothing is locked")
    void unknownEncounterIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(f.encounterRepository.findCampaignIdById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> f.turns.advanceTurn(unknownId, f.dmUserId));
        verify(f.encounterRepository, never()).lockById(any());
    }

    @Test
    @DisplayName("Turns in a Completed or Preparing encounter are a 409")
    void turnsOutsideActiveEncounterAreRefused() {
        f.monster("Goblin", 7, 15);

        f.encounter.setStatus(EncounterStatus.COMPLETED);
        ConflictException completed = assertThrows(ConflictException.class,
                () -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));
        f.encounter.setStatus(EncounterStatus.PREPARING);
        ConflictException preparing = assertThrows(ConflictException.class,
                () -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));

        assertEquals("The encounter has ended", completed.getMessage());
        assertEquals("The encounter hasn't started yet", preparing.getMessage());
        assertEquals(1, f.encounter.getRoundNumber());
        assertTrue(f.log.isEmpty());
    }

    @Test
    @DisplayName("The DM sets which monsters fight in their lair")
    void lairStatus() {
        UUID monsterId = UUID.randomUUID();

        f.turns.setLairStatus(f.encounterId(), List.of(monsterId), f.dmUserId);

        assertEquals(List.of(monsterId), f.encounter.getMonstersInLair());
    }
}
