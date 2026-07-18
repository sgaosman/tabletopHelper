package com.questkeeper.campaign;

import com.questkeeper.campaign.dto.CampaignCreateRequest;
import com.questkeeper.campaign.dto.CampaignResponse;
import com.questkeeper.user.User;
import com.questkeeper.user.UserRepository;
import com.questkeeper.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignMemberRepository campaignMemberRepository;
    private final UserRepository userRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Transactional
    public CampaignResponse createCampaign(CampaignCreateRequest request, UUID dmUserId) {
        User dm = userRepository.findById(dmUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Campaign campaign = Campaign.builder()
                .name(request.getName())
                .description(request.getDescription())
                .dm(dm)
                .inviteCode(generateInviteCode())
                .build();

        campaign = campaignRepository.save(campaign);

        CampaignMember dmMember = CampaignMember.builder()
                .campaign(campaign)
                .user(dm)
                .role(UserRole.DM)
                .build();
        campaignMemberRepository.save(dmMember);

        campaign.getMembers().add(dmMember);

        return toResponse(campaign);
    }

    @Transactional
    public CampaignResponse joinCampaign(String inviteCode, UUID userId) {
        Campaign campaign = campaignRepository.findByInviteCode(inviteCode.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid invite code"));

        if (campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), userId)) {
            throw new IllegalArgumentException("Already a member of this campaign");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        CampaignMember member = CampaignMember.builder()
                .campaign(campaign)
                .user(user)
                .role(UserRole.PLAYER)
                .build();
        campaignMemberRepository.save(member);

        campaign.getMembers().add(member);

        return toResponse(campaign);
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> getMyCampaigns(UUID userId) {
        List<CampaignMember> memberships = campaignMemberRepository.findByUserId(userId);
        return memberships.stream()
                .map(m -> toResponse(m.getCampaign()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CampaignResponse getCampaign(UUID campaignId, UUID userId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            throw new IllegalArgumentException("You are not a member of this campaign");
        }

        return toResponse(campaign);
    }

    private CampaignResponse toResponse(Campaign campaign) {
        List<CampaignResponse.MemberResponse> memberResponses = campaign.getMembers().stream()
                .map(m -> CampaignResponse.MemberResponse.builder()
                        .userId(m.getUser().getId())
                        .username(m.getUser().getUsername())
                        .displayName(m.getUser().getDisplayName())
                        .role(m.getRole().name())
                        .joinedAt(m.getJoinedAt())
                        .build())
                .toList();

        return CampaignResponse.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .description(campaign.getDescription())
                .dmUserId(campaign.getDm().getId())
                .dmDisplayName(campaign.getDm().getDisplayName())
                .inviteCode(campaign.getInviteCode())
                .isActive(campaign.getIsActive())
                .members(memberResponses)
                .createdAt(campaign.getCreatedAt())
                .build();
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        String code = sb.toString();
        if (campaignRepository.findByInviteCode(code).isPresent()) {
            return generateInviteCode();
        }
        return code;
    }
}
