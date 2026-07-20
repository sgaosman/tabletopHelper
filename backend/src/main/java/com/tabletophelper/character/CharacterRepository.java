package com.tabletophelper.character;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<PlayerCharacter, UUID> {

    @EntityGraph(attributePaths = {"user", "campaign", "raceRef", "classRef", "subclassRef", "backgroundRef"})
    List<PlayerCharacter> findByUserIdAndIsActiveTrue(UUID userId);

    @EntityGraph(attributePaths = {"user", "campaign", "raceRef", "classRef", "subclassRef", "backgroundRef"})
    List<PlayerCharacter> findByCampaignIdAndIsActiveTrue(UUID campaignId);
}
