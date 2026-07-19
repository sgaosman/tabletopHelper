package com.tabletophelper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterParticipantRepository extends JpaRepository<EncounterParticipant, UUID> {

    List<EncounterParticipant> findByEncounterIdOrderBySortOrder(UUID encounterId);

    boolean existsByCharacter_IdAndEncounter_StatusIn(UUID characterId, List<EncounterStatus> statuses);
}
