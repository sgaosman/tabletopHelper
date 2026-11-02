package com.tabletophelper.campaign;

import com.tabletophelper.campaign.dto.CampaignCreateRequest;
import com.tabletophelper.campaign.dto.CampaignResponse;
import com.tabletophelper.campaign.dto.CampaignSettingsRequest;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import com.tabletophelper.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignMemberRepository campaignMemberRepository;
    @Mock private UserRepository userRepository;

    private CampaignService campaignService;

    private UUID dmUserId;
    private UUID playerUserId;
    private User dmUser;
    private User playerUser;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(campaignRepository, campaignMemberRepository, userRepository,
                new AccessPolicy(campaignRepository, campaignMemberRepository));

        dmUserId = UUID.randomUUID();
        playerUserId = UUID.randomUUID();

        dmUser = User.builder()
                .id(dmUserId)
                .username("dm_user")
                .email("dm@test.com")
                .passwordHash("hash")
                .displayName("DM User")
                .build();

        playerUser = User.builder()
                .id(playerUserId)
                .username("player_user")
                .email("player@test.com")
                .passwordHash("hash")
                .displayName("Player User")
                .build();
    }

    @Test
    @DisplayName("Create campaign auto-adds DM as member with invite code")
    void createCampaign_autoAddsDmAsMember() {
        CampaignCreateRequest request = new CampaignCreateRequest();
        request.setName("Mines of Phandelver");
        request.setDescription("A classic adventure");

        when(userRepository.findById(dmUserId)).thenReturn(Optional.of(dmUser));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> {
            Campaign camp = invocation.getArgument(0);
            camp.setId(UUID.randomUUID());
            return camp;
        });
        when(campaignMemberRepository.save(any(CampaignMember.class))).thenAnswer(invocation -> {
            CampaignMember member = invocation.getArgument(0);
            member.setId(UUID.randomUUID());
            return member;
        });
        when(campaignRepository.findByInviteCode(anyString())).thenReturn(Optional.empty());

        CampaignResponse response = campaignService.createCampaign(request, dmUserId);

        assertNotNull(response);
        assertEquals("Mines of Phandelver", response.getName());
        assertEquals(dmUserId, response.getDmUserId());
        assertNotNull(response.getInviteCode());
        assertEquals(8, response.getInviteCode().length());
        // DM should be auto-added as a member
        assertEquals(1, response.getMembers().size());
        assertEquals(dmUserId, response.getMembers().get(0).getUserId());
        assertEquals("DM", response.getMembers().get(0).getRole());
    }

    @Test
    @DisplayName("Join campaign adds player as member")
    void joinCampaign_addsPlayerAsMember() {
        UUID campaignId = UUID.randomUUID();
        String inviteCode = "ABC12345";

        Campaign campaign = Campaign.builder()
                .id(campaignId)
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode(inviteCode)
                .members(new ArrayList<>())
                .build();

        when(campaignRepository.findByInviteCode(inviteCode)).thenReturn(Optional.of(campaign));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUserId)).thenReturn(false);
        when(userRepository.findById(playerUserId)).thenReturn(Optional.of(playerUser));
        when(campaignMemberRepository.save(any(CampaignMember.class))).thenAnswer(invocation -> {
            CampaignMember member = invocation.getArgument(0);
            member.setId(UUID.randomUUID());
            return member;
        });

        CampaignResponse response = campaignService.joinCampaign(inviteCode, playerUserId);

        assertNotNull(response);
        assertEquals("Test Campaign", response.getName());
        verify(campaignMemberRepository).save(any(CampaignMember.class));
    }

    @Test
    @DisplayName("Join campaign with invalid invite code fails")
    void joinCampaign_invalidCodeFails() {
        String invalidCode = "ZZZZZ999";

        when(campaignRepository.findByInviteCode(invalidCode)).thenReturn(Optional.empty());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> campaignService.joinCampaign(invalidCode, playerUserId));

        assertEquals("Invalid invite code", ex.getMessage());
    }

    @Test
    @DisplayName("Joining a campaign twice is a conflict")
    void joinCampaign_alreadyMemberConflicts() {
        Campaign campaign = Campaign.builder()
                .id(UUID.randomUUID())
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode("ABC12345")
                .members(new ArrayList<>())
                .build();

        when(campaignRepository.findByInviteCode("ABC12345")).thenReturn(Optional.of(campaign));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), playerUserId)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> campaignService.joinCampaign("ABC12345", playerUserId));

        assertEquals("Already a member of this campaign", ex.getMessage());
        verify(campaignMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("getCampaign refuses a user who is not a member")
    void getCampaign_nonMemberIsForbidden() {
        UUID campaignId = UUID.randomUUID();
        Campaign campaign = Campaign.builder()
                .id(campaignId)
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode("ABC12345")
                .members(new ArrayList<>())
                .build();

        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> campaignService.getCampaign(campaignId, playerUserId));

        assertEquals("You are not a member of this campaign", ex.getMessage());
    }

    @Test
    @DisplayName("getMyCampaigns returns only campaigns where user is a member")
    void getMyCampaigns_returnsOnlyMemberCampaigns() {
        Campaign campaign1 = Campaign.builder()
                .id(UUID.randomUUID())
                .name("Campaign 1")
                .dm(dmUser)
                .inviteCode("CODE0001")
                .members(new ArrayList<>())
                .build();

        Campaign campaign2 = Campaign.builder()
                .id(UUID.randomUUID())
                .name("Campaign 2")
                .dm(dmUser)
                .inviteCode("CODE0002")
                .members(new ArrayList<>())
                .build();

        CampaignMember membership1 = CampaignMember.builder()
                .id(UUID.randomUUID())
                .campaign(campaign1)
                .user(playerUser)
                .role(UserRole.PLAYER)
                .build();

        CampaignMember membership2 = CampaignMember.builder()
                .id(UUID.randomUUID())
                .campaign(campaign2)
                .user(playerUser)
                .role(UserRole.PLAYER)
                .build();

        when(campaignMemberRepository.findByUserId(playerUserId)).thenReturn(List.of(membership1, membership2));

        List<CampaignResponse> responses = campaignService.getMyCampaigns(playerUserId);

        assertEquals(2, responses.size());
        assertTrue(responses.stream().anyMatch(r -> r.getName().equals("Campaign 1")));
        assertTrue(responses.stream().anyMatch(r -> r.getName().equals("Campaign 2")));
    }

    // ── Settings (D157) ──────────────────────────────────────────

    private Campaign savedCampaign() {
        Campaign campaign = Campaign.builder()
                .id(UUID.randomUUID())
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode("ABC12345")
                .members(new ArrayList<>())
                .build();
        lenient().when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        lenient().when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return campaign;
    }

    @Test
    @DisplayName("A new campaign hides exact monster HP from players")
    void newCampaign_hidesExactMonsterHp() {
        assertFalse(savedCampaign().getShowExactMonsterHp());
    }

    @Test
    @DisplayName("The DM can turn on showing exact monster HP")
    void updateSettings_dmTurnsOnExactMonsterHp() {
        Campaign campaign = savedCampaign();

        CampaignResponse response = campaignService.updateSettings(campaign.getId(),
                new CampaignSettingsRequest(true), dmUserId);

        assertTrue(response.getShowExactMonsterHp());
        assertTrue(campaign.getShowExactMonsterHp());
    }

    @Test
    @DisplayName("A setting left out of the request keeps its value")
    void updateSettings_missingSettingIsKept() {
        Campaign campaign = savedCampaign();
        campaign.setShowExactMonsterHp(true);

        CampaignResponse response = campaignService.updateSettings(campaign.getId(),
                new CampaignSettingsRequest(null), dmUserId);

        assertTrue(response.getShowExactMonsterHp());
    }

    @Test
    @DisplayName("Only the DM can change campaign settings")
    void updateSettings_refusesPlayer() {
        Campaign campaign = savedCampaign();

        ForbiddenException ex = assertThrows(ForbiddenException.class, () ->
                campaignService.updateSettings(campaign.getId(), new CampaignSettingsRequest(true), playerUserId));

        assertEquals("Only the DM can perform this action", ex.getMessage());
        assertFalse(campaign.getShowExactMonsterHp());
        verify(campaignRepository, never()).save(any());
    }
}
