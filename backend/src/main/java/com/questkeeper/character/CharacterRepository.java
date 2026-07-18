package com.questkeeper.character;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<PlayerCharacter, UUID> {

    List<PlayerCharacter> findByUserIdAndIsActiveTrue(UUID userId);

    List<PlayerCharacter> findByCampaignIdAndIsActiveTrue(UUID campaignId);
}
