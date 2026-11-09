package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterLifecycle;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.LogPatch;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The DM's undo of the newest combat commands (M14). Each command's before-state is put back, newest first, which is
 * right for several at once because each holds only what its own command changed. The commands are marked undone, so
 * the DM's log keeps their entries dimmed and the players' drops them, and an UNDO entry is written for each. Undo is
 * not itself recorded, so there is no redo, and it never goes past a barrier: the encounter's start, or a
 * participant's removal mid-fight. Dice are not part of the state, so a command taken again rolls them afresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UndoService {

    private final EncounterCommands encounterCommands;
    private final CommandRecorder commandRecorder;
    private final CombatLogWriter combatLog;
    private final CombatLogRepository combatLogRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse undo(UUID encounterId, UndoRequest request, UUID userId) {
        // Not through CombatAccess, which would record the undo as a command of its own
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.requireActive(encounter);
        int steps = request.steps() != null ? request.steps() : 1;

        List<CombatCommand> undoing = commandRecorder.undoable(encounterId, steps);
        if (undoing.isEmpty()) {
            throw new ConflictException("Nothing to undo");
        }
        // A player may have acted while the DM read the dialog
        if (!undoing.getFirst().getId().equals(request.expectedCommandId())) {
            throw new ConflictException(CommandRecorder.STALE);
        }
        if (undoing.size() < steps) {
            throw new ConflictException("Only " + undoing.size() + (undoing.size() == 1 ? " action" : " actions")
                    + " can be undone");
        }

        Instant now = Instant.now();
        for (CombatCommand command : undoing) {
            restore(encounter, command);
            command.setUndoneAt(now);
            command.setUndoneBy(userId);
        }
        for (CombatCommand command : undoing) {
            combatLog.save(combatLog.entry(encounter, CombatActionType.UNDO,
                            "The DM undoes: " + CommandRecorder.summaryOf(command))
                    .undoesCommandId(command.getId()));
        }
        log.info("DM {} undid {} combat command(s) in encounter {}", userId, undoing.size(), encounterId);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // Puts back what the command changed: its participants, the encounter's turn and the log entries it changed
    private void restore(Encounter encounter, CombatCommand command) {
        CommandSnapshot before = command.getBeforeState();
        if (before == null) {
            throw new ConflictException("That action can no longer be undone");
        }
        for (ParticipantCombatState state : before.participants() != null ? before.participants()
                : List.<ParticipantCombatState>of()) {
            EncounterParticipant participant = encounter.getParticipants().stream()
                    .filter(p -> p.getId().equals(state.id()))
                    .findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "A creature that action changed has left the encounter, so it can't be undone"));
            state.restore(participant);
        }
        if (before.encounter() != null) {
            before.encounter().restore(encounter);
        }
        for (LogPatch patch : before.logPatches() != null ? before.logPatches() : List.<LogPatch>of()) {
            combatLogRepository.findById(patch.logId()).ifPresent(entry -> entry.setLrResolved(patch.lrResolved()));
        }
    }
}
