package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The DM's turn commands (D163): the next turn, which {@link TurnProcessor} ends and starts, going back a
 * turn, which only moves the marker, and which monsters fight in their lair, which gives the Lair a turn on
 * initiative 20 while any of them is alive.
 */
@Service
@RequiredArgsConstructor
public class TurnService {

    private final CombatAccess access;
    private final TurnProcessor turnProcessor;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse advanceTurn(UUID encounterId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        turnProcessor.advance(encounter);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse previousTurn(UUID encounterId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        turnProcessor.back(encounter);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * Which monster participants are fighting in their lair (D136, D163): the whole list, which replaces the
     * last. A participant that isn't one of the encounter's monsters is a 400.
     */
    @Transactional
    public EncounterResponse setLairStatus(UUID encounterId, List<UUID> monsterIds, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        List<UUID> inLair = new ArrayList<>(new LinkedHashSet<>(monsterIds != null ? monsterIds : List.of()));
        for (UUID id : inLair) {
            EncounterParticipant participant = CombatAccess.participant(encounter, id);
            if (participant.getParticipantType() != ParticipantType.MONSTER) {
                throw new ValidationException("Only monsters fight in their lair");
            }
        }
        encounter.setMonstersInLair(inLair);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
