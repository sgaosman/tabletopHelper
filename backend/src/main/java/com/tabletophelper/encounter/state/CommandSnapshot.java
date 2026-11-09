package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * What a combat command changed, as it was before (M14), stored in {@code combat_commands.before_state} (V32): each
 * participant it changed, cascades included, the encounter's turn when it moved it, and the log entries it changed.
 * Undo puts them back.
 *
 * @param participants the participants the command changed, as they were
 * @param encounter    the encounter's turn as it was; null when the command left it alone
 * @param logPatches   the log entries the command changed, as they were
 */
public record CommandSnapshot(List<ParticipantCombatState> participants, EncounterTurnState encounter,
                              List<LogPatch> logPatches) {

    /** A snapshot that changes nothing, as a barrier's does. */
    public static CommandSnapshot empty() {
        return new CommandSnapshot(List.of(), null, List.of());
    }

    /** Whether undoing it would change nothing. */
    @JsonIgnore
    public boolean isEmpty() {
        return (participants == null || participants.isEmpty()) && encounter == null
                && (logPatches == null || logPatches.isEmpty());
    }
}
