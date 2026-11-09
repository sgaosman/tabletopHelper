package com.tabletophelper.encounter.state;

import com.tabletophelper.encounter.Encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The encounter's turn as a command found it, which undo puts back (M14): the round, whose turn it was (D163) and
 * the monsters fighting in their lair.
 *
 * @param roundNumber              the round
 * @param currentTurnParticipantId whose turn it was; null on the Lair's turn
 * @param lairTurn                 it was the Lair's turn
 * @param monstersInLair           the monster participants fighting in their lair
 */
public record EncounterTurnState(Integer roundNumber, UUID currentTurnParticipantId, Boolean lairTurn,
                                 List<UUID> monstersInLair) {

    /** The encounter's turn now; no lair list reads as an empty one. */
    public static EncounterTurnState of(Encounter encounter) {
        return new EncounterTurnState(encounter.getRoundNumber(), encounter.getCurrentTurnParticipantId(),
                Boolean.TRUE.equals(encounter.getLairTurn()),
                encounter.getMonstersInLair() != null ? new ArrayList<>(encounter.getMonstersInLair()) : new ArrayList<>());
    }

    /** Puts the turn back on the encounter. */
    public void restore(Encounter encounter) {
        encounter.setRoundNumber(roundNumber);
        encounter.setCurrentTurnParticipantId(currentTurnParticipantId);
        encounter.setLairTurn(Boolean.TRUE.equals(lairTurn));
        encounter.setMonstersInLair(monstersInLair != null ? new ArrayList<>(monstersInLair) : null);
    }
}
