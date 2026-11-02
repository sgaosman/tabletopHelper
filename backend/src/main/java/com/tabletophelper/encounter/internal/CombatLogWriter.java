package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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

    public CombatLog save(CombatLog.CombatLogBuilder entry) {
        return combatLogRepository.save(entry.build());
    }

    /** Writes an entry about an actor and a target, either of which may be null. */
    public CombatLog write(Encounter encounter, CombatActionType type, String description,
                           EncounterParticipant actor, EncounterParticipant target) {
        return save(about(entry(encounter, type, description), actor, target));
    }

    // The participant at the current turn index, counting in sort order with unsorted participants last
    private static String currentTurnName(Encounter encounter) {
        List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                .sorted(Comparator.comparingInt(p -> p.getSortOrder() != null ? p.getSortOrder() : 999))
                .toList();
        int turnIndex = encounter.getCurrentTurnIndex() != null ? encounter.getCurrentTurnIndex() : 0;
        return turnIndex < sorted.size() ? sorted.get(turnIndex).getDisplayName() : null;
    }
}
