package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.resourcepool.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The turn order, as the DM moves through it: the next turn resets its participant's action economy, expires
 * conditions whose duration has run out, and resets per-turn pools and rolls recharges. Turn processing with
 * end rules, Repeat Saves and the Lair's turn comes with R5 workstream E (D162, D163).
 */
@Service
@RequiredArgsConstructor
public class TurnService {

    private final CombatAccess access;
    private final EffectApplier effectApplier;
    private final ResourcePoolService resourcePoolService;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse advanceTurn(UUID encounterId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        List<EncounterParticipant> sorted = inTurnOrder(encounter);
        if (sorted.isEmpty()) {
            return encounterService.toResponse(encounter);
        }

        int currentIndex = currentTurn(sorted);
        int nextIndex = (currentIndex + 1) % sorted.size();
        if (nextIndex <= currentIndex || currentIndex == -1) {
            encounter.setRoundNumber(encounter.getRoundNumber() + 1);
        }
        encounter.setCurrentTurnIndex(nextIndex);

        EncounterParticipant next = sorted.get(nextIndex);
        next.setIsCurrentTurn(true);
        next.setActionUsed(false);
        next.setBonusActionUsed(false);
        next.setReactionUsed(false);

        effectApplier.expireConditions(encounter, next);
        resetTurnPools(encounter, next);

        combatLog.write(encounter, CombatActionType.TURN_ADVANCE,
                "Turn passes to " + next.getDisplayName() + " (Round " + encounter.getRoundNumber() + ")", null, next);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse previousTurn(UUID encounterId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        List<EncounterParticipant> sorted = inTurnOrder(encounter);
        if (sorted.isEmpty()) {
            return encounterService.toResponse(encounter);
        }

        int currentIndex = currentTurn(sorted);
        int previousIndex;
        if (currentIndex <= 0) {
            previousIndex = sorted.size() - 1;
            if (encounter.getRoundNumber() > 1) {
                encounter.setRoundNumber(encounter.getRoundNumber() - 1);
            }
        } else {
            previousIndex = currentIndex - 1;
        }
        encounter.setCurrentTurnIndex(previousIndex);

        EncounterParticipant previous = sorted.get(previousIndex);
        previous.setIsCurrentTurn(true);

        combatLog.write(encounter, CombatActionType.TURN_BACK,
                "Turn returns to " + previous.getDisplayName() + " (Round " + encounter.getRoundNumber() + ")",
                null, previous);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /** Which monsters are fighting in their lair (D136); the Lair's turn uses it from R5 workstream E. */
    @Transactional
    public EncounterResponse setLairStatus(UUID encounterId, List<UUID> monsterIds, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        encounter.setMonstersInLair(monsterIds != null ? new ArrayList<>(monsterIds) : null);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private static List<EncounterParticipant> inTurnOrder(Encounter encounter) {
        return encounter.getParticipants().stream()
                .sorted(Comparator.comparing(p -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE))
                .toList();
    }

    // The participant whose turn it is, which loses the turn marker, or -1 when no one has it
    private static int currentTurn(List<EncounterParticipant> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            if (Boolean.TRUE.equals(sorted.get(i).getIsCurrentTurn())) {
                sorted.get(i).setIsCurrentTurn(false);
                return i;
            }
        }
        return -1;
    }

    /**
     * Resets the per-turn pools of the participant whose turn starts, such as legendary actions, and rolls its
     * recharges (M12), logging each recharge roll.
     */
    private void resetTurnPools(Encounter encounter, EncounterParticipant participant) {
        List<ResourcePoolEntry> pools = participant.getResourcePoolsCurrent();
        if (pools == null || pools.isEmpty()) {
            return;
        }
        ResourcePoolService.ResetPoolsResult reset = resourcePoolService.resetPools(pools, "turn", Map.of());
        participant.setResourcePoolsCurrent(reset.pools());

        for (ResourcePoolService.RechargeLogEntry recharge : reset.rechargeResults()) {
            String dice = recharge.checkExpression() != null
                    ? recharge.checkExpression().replaceAll(">=.*", "") : "1d6";
            String name = participant.getDisplayName();
            String description = name + " rolls " + dice + " (" + recharge.rollValue() + "). "
                    + (recharge.success() ? name + " recharges " + recharge.displayName() + "!"
                    : name + " fails to recharge " + recharge.displayName() + ".");
            combatLog.save(CombatLogWriter.about(combatLog.entry(encounter,
                    recharge.success() ? CombatActionType.RECHARGE_SUCCESS : CombatActionType.RECHARGE_FAILURE,
                    description), participant, participant).rollValue(recharge.rollValue()));
        }
    }
}
