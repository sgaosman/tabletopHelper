package com.tabletophelper.campaign;

import com.tabletophelper.campaign.dto.CampaignCreateRequest;
import com.tabletophelper.campaign.dto.CampaignResponse;
import com.tabletophelper.campaign.dto.CampaignSettingsRequest;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserRole;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
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
    private final UserService userService;
    private final AccessPolicy accessPolicy;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Transactional
    public CampaignResponse createCampaign(CampaignCreateRequest request, UUID dmUserId) {
        User dm = userService.getUser(dmUserId);

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
                .orElseThrow(() -> new ValidationException("Invalid invite code"));

        if (campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), userId)) {
            throw new ConflictException("Already a member of this campaign");
        }

        User user = userService.getUser(userId);

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
        accessPolicy.requireMember(campaignId, userId);

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new NotFoundException("Campaign not found"));
        return toResponse(campaign);
    }

    // A live encounter's Player View picks up a change with its next update (D157)
    @Transactional
    public CampaignResponse updateSettings(UUID campaignId, CampaignSettingsRequest request, UUID userId) {
        accessPolicy.requireDm(campaignId, userId);

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new NotFoundException("Campaign not found"));
        if (request.showExactMonsterHp() != null) {
            campaign.setShowExactMonsterHp(request.showExactMonsterHp());
        }
        if (request.promptPlayerRepeatSaves() != null) {
            campaign.setPromptPlayerRepeatSaves(request.promptPlayerRepeatSaves());
        }
        if (request.allowPlayerManualModifiers() != null) {
            campaign.setAllowPlayerManualModifiers(request.allowPlayerManualModifiers());
        }
        return toResponse(campaignRepository.save(campaign));
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
                .showExactMonsterHp(campaign.getShowExactMonsterHp())
                .promptPlayerRepeatSaves(campaign.getPromptPlayerRepeatSaves())
                .allowPlayerManualModifiers(campaign.getAllowPlayerManualModifiers())
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
