package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Reads an encounter's combat log, oldest entry first. The log endpoint and {@link EncounterBroadcaster}
 * both read it here, so the log a client fetches matches the one the WebSocket sends (D171).
 */
@Component
@RequiredArgsConstructor
public class CombatLogReader {

    private final CombatLogRepository combatLogRepository;

    /** The whole log, as the DM reads it; {@link EncounterViews#playerLog} derives what players read. */
    public List<CombatLogResponse> dmLog(UUID encounterId) {
        return combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId).stream()
                .map(CombatLogReader::toResponse)
                .toList();
    }

    private static CombatLogResponse toResponse(CombatLog log) {
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
                .createdAt(log.getCreatedAt())
                .build();
    }
}
