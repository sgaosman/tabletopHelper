package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Conditions by hand: the DM on anyone, a player on their own character (D028). They end when removed, or as
 * their end rules say at turn boundaries, and every removal is logged (D162). A player the campaign prompts
 * rolls their character's Repeat Saves here.
 */
@Service
@RequiredArgsConstructor
public class ConditionService {

    private final CombatAccess access;
    private final EffectApplier effectApplier;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse addCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getTargetId(), userId);
        effectApplier.addCondition(encounter, CombatAccess.participant(encounter, request.getTargetId()),
                request.getCondition(), request.getDuration());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse removeCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getTargetId(), userId);
        effectApplier.removeCondition(encounter, CombatAccess.participant(encounter, request.getTargetId()),
                request.getCondition());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * A Repeat Save left for the player to roll at the end of their character's turn (D162), by the player or
     * the DM. The server rolls it, logged as REPEAT_SAVE, and a success ends the condition. An unknown
     * condition is a 400; one with no roll waiting is a 409.
     */
    @Transactional
    public EncounterResponse rollRepeatSave(UUID encounterId, RepeatSaveRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        ConditionEntry condition = (participant.getActiveConditions() != null
                ? participant.getActiveConditions() : List.<ConditionEntry>of()).stream()
                .filter(c -> request.conditionId().equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Condition not found"));
        if (condition.repeatSave() == null || !condition.repeatSave().awaitingRoll()) {
            throw new ConflictException("No Repeat Save against " + condition.name() + " is waiting for a roll");
        }
        effectApplier.repeatSave(encounter, participant, condition.id());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
