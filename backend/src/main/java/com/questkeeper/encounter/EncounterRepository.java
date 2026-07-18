package com.questkeeper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    List<Encounter> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    Optional<Encounter> findBySessionCode(String sessionCode);
}
