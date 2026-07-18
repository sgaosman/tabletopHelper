package com.questkeeper.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CampaignMemberRepository extends JpaRepository<CampaignMember, UUID> {

    List<CampaignMember> findByUserId(UUID userId);

    boolean existsByCampaignIdAndUserId(UUID campaignId, UUID userId);
}
