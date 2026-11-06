package com.tabletophelper.campaign;

import com.tabletophelper.identity.User;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessPolicyTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignMemberRepository campaignMemberRepository;

    private AccessPolicy accessPolicy;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID unknownCampaignId = UUID.randomUUID();
    private final UUID dmId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final UUID otherPlayerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);

        Campaign campaign = Campaign.builder()
                .id(campaignId)
                .name("Lost Mine")
                .dm(User.builder().id(dmId).username("dm").build())
                .inviteCode("ABCD2345")
                .build();
        lenient().when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        lenient().when(campaignRepository.findById(unknownCampaignId)).thenReturn(Optional.empty());
    }

    private static void assertForbidden(String message, Executable check) {
        ForbiddenException ex = assertThrows(ForbiddenException.class, check);
        assertEquals(message, ex.getMessage());
    }

    @Nested
    @DisplayName("requireMember")
    class RequireMember {

        @Test
        @DisplayName("A member passes")
        void memberPasses() {
            when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerId)).thenReturn(true);

            assertDoesNotThrow(() -> accessPolicy.requireMember(campaignId, playerId));
        }

        @Test
        @DisplayName("A non-member of an existing campaign is refused with 403")
        void nonMemberIsForbidden() {
            assertForbidden("You are not a member of this campaign",
                    () -> accessPolicy.requireMember(campaignId, otherPlayerId));
        }

        @Test
        @DisplayName("A campaign that does not exist is a 404, not a 403")
        void unknownCampaignIsNotFound() {
            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> accessPolicy.requireMember(unknownCampaignId, playerId));
            assertEquals("Campaign not found", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("requireDm")
    class RequireDm {

        @Test
        @DisplayName("The campaign's DM passes")
        void dmPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireDm(campaignId, dmId));
        }

        @Test
        @DisplayName("A player is refused")
        void playerIsForbidden() {
            assertForbidden("Only the DM can perform this action", () -> accessPolicy.requireDm(campaignId, playerId));
        }

        @Test
        @DisplayName("A campaign that does not exist is a 404")
        void unknownCampaignIsNotFound() {
            assertThrows(NotFoundException.class, () -> accessPolicy.requireDm(unknownCampaignId, dmId));
        }
    }

    @Nested
    @DisplayName("requireCharacterOwner")
    class RequireCharacterOwner {

        @Test
        @DisplayName("The owner passes")
        void ownerPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireCharacterOwner(playerId, playerId));
        }

        @Test
        @DisplayName("Anyone else is refused, the DM included, who adjusts a character only through DM Adjustments (D158)")
        void othersAreForbidden() {
            assertForbidden("You do not own this character", () -> accessPolicy.requireCharacterOwner(playerId, otherPlayerId));
            assertForbidden("You do not own this character", () -> accessPolicy.requireCharacterOwner(playerId, dmId));
            verifyNoInteractions(campaignRepository, campaignMemberRepository);
        }
    }

    @Nested
    @DisplayName("requireCharacterDm (D158)")
    class RequireCharacterDm {

        @Test
        @DisplayName("The DM of the character's campaign passes")
        void dmPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireCharacterDm(campaignId, dmId));
        }

        @Test
        @DisplayName("Anyone else is refused, the character's owner included")
        void othersAreForbidden() {
            assertForbidden("Only the DM of this character's campaign can adjust it",
                    () -> accessPolicy.requireCharacterDm(campaignId, playerId));
            assertForbidden("Only the DM of this character's campaign can adjust it",
                    () -> accessPolicy.requireCharacterDm(campaignId, otherPlayerId));
        }

        @Test
        @DisplayName("A character in no campaign has no DM, so nobody may adjust it")
        void noCampaignHasNoDm() {
            assertForbidden("This character isn't in a campaign, so it has no DM to adjust it",
                    () -> accessPolicy.requireCharacterDm(null, dmId));
            verifyNoInteractions(campaignRepository, campaignMemberRepository);
        }

        @Test
        @DisplayName("Another DM command, such as a revival, is refused in its own words")
        void otherCommandsUseTheirOwnWords() {
            assertDoesNotThrow(() -> accessPolicy.requireCharacterDm(campaignId, dmId, "revive"));
            assertForbidden("Only the DM of this character's campaign can revive it",
                    () -> accessPolicy.requireCharacterDm(campaignId, playerId, "revive"));
            assertForbidden("This character isn't in a campaign, so it has no DM to revive it",
                    () -> accessPolicy.requireCharacterDm(null, dmId, "revive"));
        }
    }

    @Nested
    @DisplayName("requireOwnerOrDm")
    class RequireOwnerOrDm {

        @Test
        @DisplayName("The owner passes, with or without a campaign")
        void ownerPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireOwnerOrDm(playerId, campaignId, playerId));
            assertDoesNotThrow(() -> accessPolicy.requireOwnerOrDm(playerId, null, playerId));
        }

        @Test
        @DisplayName("The DM of the character's campaign passes")
        void campaignDmPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireOwnerOrDm(playerId, campaignId, dmId));
        }

        @Test
        @DisplayName("Another player in the same campaign is refused")
        void otherPlayerIsForbidden() {
            assertForbidden("You do not have access to this character",
                    () -> accessPolicy.requireOwnerOrDm(playerId, campaignId, otherPlayerId));
        }

        @Test
        @DisplayName("Without a campaign only the owner passes")
        void noCampaignMeansOwnerOnly() {
            assertForbidden("You do not have access to this character",
                    () -> accessPolicy.requireOwnerOrDm(playerId, null, dmId));
        }
    }

    @Nested
    @DisplayName("requireOwnerIsMember")
    class RequireOwnerIsMember {

        @Test
        @DisplayName("A character whose owner is a member passes")
        void memberOwnerPasses() {
            when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerId)).thenReturn(true);

            assertDoesNotThrow(() -> accessPolicy.requireOwnerIsMember(campaignId, playerId));
        }

        @Test
        @DisplayName("A character whose owner never joined is refused")
        void nonMemberOwnerIsForbidden() {
            assertForbidden("This character's owner is not a member of this campaign",
                    () -> accessPolicy.requireOwnerIsMember(campaignId, otherPlayerId));
        }
    }

    @Nested
    @DisplayName("requireControls (D028)")
    class RequireControls {

        @Test
        @DisplayName("The DM may act for any participant, including monsters")
        void dmControlsEveryone() {
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, null, dmId));
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, playerId, dmId));
        }

        @Test
        @DisplayName("A player may act for the participant they control")
        void playerControlsOwnParticipant() {
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, playerId, playerId));
        }

        @Test
        @DisplayName("A player may not act for someone else's participant or a monster")
        void playerCannotControlOthers() {
            assertForbidden("You do not have permission to perform this action",
                    () -> accessPolicy.requireControls(campaignId, otherPlayerId, playerId));
            assertForbidden("You do not have permission to perform this action",
                    () -> accessPolicy.requireControls(campaignId, null, playerId));
        }

        @Test
        @DisplayName("On-turn actions: the DM may act at any time")
        void dmIgnoresTurnOrder() {
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, playerId, false, dmId));
        }

        @Test
        @DisplayName("On-turn actions: a player may act on their participant's turn")
        void playerOnTheirTurnPasses() {
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, playerId, true, playerId));
        }

        @Test
        @DisplayName("On-turn actions: a player acting out of turn gets 403")
        void playerOutOfTurnIsForbidden() {
            assertForbidden("You can only attack on your turn",
                    () -> accessPolicy.requireControls(campaignId, playerId, false, playerId));
        }

        @Test
        @DisplayName("On-turn actions: a command other than an attack or spell is refused out of turn with its own "
                + "message, and the DM never is")
        void playerOutOfTurnWithItsOwnMessage() {
            assertForbidden("You can only take that action on your turn", () -> accessPolicy.requireControls(
                    campaignId, playerId, false, "You can only take that action on your turn", playerId));
            assertDoesNotThrow(() -> accessPolicy.requireControls(campaignId, playerId, false,
                    "You can only take that action on your turn", dmId));
        }

        @Test
        @DisplayName("On-turn actions: a player may not act for someone else even on that participant's turn")
        void playerCannotControlOthersOnTurn() {
            assertForbidden("You do not have permission to perform this action",
                    () -> accessPolicy.requireControls(campaignId, otherPlayerId, true, playerId));
        }
    }
}
