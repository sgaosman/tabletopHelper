package com.questkeeper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterParticipantRepository extends JpaRepository<EncounterParticipant, UUID> {

    List<EncounterParticipant> findByEncounterIdOrderBySortOrder(UUID encounterId);
}
