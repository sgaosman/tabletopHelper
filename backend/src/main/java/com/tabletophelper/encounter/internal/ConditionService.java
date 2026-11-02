package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Conditions by hand: the DM on anyone, a player on their own character (D028). They end when removed, or
 * after their duration, and every removal is logged (D162).
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
}
