package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * What a combat command changed, as it was before (M14), stored in {@code combat_commands.before_state} (V32): each
 * participant it changed, cascades included, the encounter's turn when it moved it, and the log entries it changed.
 * Undo puts them back.
 *
 * @param participants the participants the command changed, as they were
 * @param encounter    the encounter's turn as it was; null when the command left it alone
 * @param logPatches   the log entries the command changed, as they were
 * @param createdParticipantIds the participants the command brought into the fight, such as a spell's summoned
 *                              creatures (D182), which undo removes; null for none, and in snapshots before V34
 */
public record CommandSnapshot(List<ParticipantCombatState> participants, EncounterTurnState encounter,
                              List<LogPatch> logPatches,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> createdParticipantIds) {

    /** A snapshot that brought nobody into the fight. */
    public CommandSnapshot(List<ParticipantCombatState> participants, EncounterTurnState encounter,
                           List<LogPatch> logPatches) {
        this(participants, encounter, logPatches, null);
    }

    /** A snapshot that changes nothing, as a barrier's does. */
    public static CommandSnapshot empty() {
        return new CommandSnapshot(List.of(), null, List.of());
    }

    /** Whether undoing it would change nothing. */
    @JsonIgnore
    public boolean isEmpty() {
        return (participants == null || participants.isEmpty()) && encounter == null
                && (logPatches == null || logPatches.isEmpty())
                && (createdParticipantIds == null || createdParticipantIds.isEmpty());
    }
}
