package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.TurnOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Writes combat log entries, each stamped with the encounter's round and whose turn it is. */
@Component
@RequiredArgsConstructor
public class CombatLogWriter {

    private final CombatLogRepository combatLogRepository;

    /** An entry for the encounter's current round and turn, to fill in and {@link #save}. */
    public CombatLog.CombatLogBuilder entry(Encounter encounter, CombatActionType type, String description) {
        return CombatLog.builder()
                .encounter(encounter)
                .roundNumber(encounter.getRoundNumber())
                .turnParticipantName(currentTurnName(encounter))
                .actionType(type)
                .description(description);
    }

    /** Names the entry's actor and target; either may be null. */
    public static CombatLog.CombatLogBuilder about(CombatLog.CombatLogBuilder entry, EncounterParticipant actor,
                                                   EncounterParticipant target) {
        return entry
                .actorId(actor != null ? actor.getId() : null)
                .actorName(actor != null ? actor.getDisplayName() : null)
                .targetId(target != null ? target.getId() : null)
                .targetName(target != null ? target.getDisplayName() : null);
    }

    /** Saves the entry, stamped with the combat command being recorded, if any, which undo marks undone (M14). */
    public CombatLog save(CombatLog.CombatLogBuilder entry) {
        Optional<CommandRecorder.Recording> command = CommandRecorder.current();
        command.ifPresent(recording -> entry.commandId(recording.id()));
        CombatLog saved = combatLogRepository.save(entry.build());
        command.ifPresent(recording -> recording.written(saved));
        return saved;
    }

    /** Writes an entry about an actor and a target, either of which may be null. */
    public CombatLog write(Encounter encounter, CombatActionType type, String description,
                           EncounterParticipant actor, EncounterParticipant target) {
        return save(about(entry(encounter, type, description), actor, target));
    }

    // Whose turn it is: the participant the encounter's marker names, the Lair's, or nobody's (D163)
    private static String currentTurnName(Encounter encounter) {
        if (EncounterTurns.isLairTurn(encounter)) {
            return TurnOrder.LAIR_ACTIONS;
        }
        return EncounterTurns.currentParticipant(encounter).map(EncounterParticipant::getDisplayName).orElse(null);
    }
}
