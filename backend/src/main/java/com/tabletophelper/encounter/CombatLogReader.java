package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads an encounter's combat log, oldest entry first. The log endpoint and {@link EncounterBroadcaster}
 * both read it here, so the log a client fetches matches the one the WebSocket sends (D171).
 */
@Component
@RequiredArgsConstructor
public class CombatLogReader {

    private final CombatLogRepository combatLogRepository;
    private final CombatCommandRepository combatCommandRepository;

    /**
     * The whole log, as the DM reads it, the entries of commands the DM undid included and marked undone (M14);
     * {@link EncounterViews#playerLog} derives what players read.
     */
    public List<CombatLogResponse> dmLog(UUID encounterId) {
        Set<UUID> undone = combatCommandRepository.undoneIds(encounterId);
        return combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId).stream()
                .map(log -> toResponse(log, undone))
                .toList();
    }

    private static CombatLogResponse toResponse(CombatLog log, Set<UUID> undoneCommands) {
        return CombatLogResponse.builder()
                .id(log.getId())
                .roundNumber(log.getRoundNumber())
                .actorId(log.getActorId())
                .actorName(log.getActorName())
                .targetId(log.getTargetId())
                .targetName(log.getTargetName())
                .actionType(log.getActionType().name())
                .description(log.getDescription())
                .rollValue(log.getRollValue())
                .rollTotal(log.getRollTotal())
                .damageDealt(log.getDamageDealt())
                .healingDone(log.getHealingDone())
                .turnParticipantName(log.getTurnParticipantName())
                .legendaryResistanceEligible(log.getLegendaryResistanceEligible())
                .lrTargetId(log.getLrTargetId())
                .lrDamageDealt(log.getLrDamageDealt())
                .lrConditionsApplied(log.getLrConditionsApplied())
                .lrResolved(log.getLrResolved())
                .lrSuccessDamage(log.getLrSuccessDamage())
                .lrConditionIds(log.getLrConditionIds())
                .commandId(log.getCommandId())
                .undone(log.getCommandId() != null && undoneCommands.contains(log.getCommandId()))
                .undoesCommandId(log.getUndoesCommandId())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
