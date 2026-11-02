package com.tabletophelper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    List<Encounter> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    Optional<Encounter> findBySessionCode(String sessionCode);

    // Reads only the foreign key, so the subscription check loads no entity and needs no transaction
    @Query("SELECT e.campaign.id FROM Encounter e WHERE e.id = :encounterId")
    Optional<UUID> findCampaignIdById(@Param("encounterId") UUID encounterId);
}
