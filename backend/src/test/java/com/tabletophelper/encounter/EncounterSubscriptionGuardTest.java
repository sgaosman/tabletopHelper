package com.tabletophelper.encounter;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncounterSubscriptionGuardTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private AccessPolicy accessPolicy;

    private EncounterSubscriptionGuard guard;

    private final UUID userId = UUID.randomUUID();
    private final UUID encounterId = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new EncounterSubscriptionGuard(encounterRepository, accessPolicy);
        lenient().when(encounterRepository.findCampaignIdById(encounterId)).thenReturn(Optional.of(campaignId));
    }

    private Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void send(StompCommand command, String destination) {
        guard.preSend(frame(command, destination), null);
    }

    @Test
    @DisplayName("A campaign member may subscribe to the encounter's Player View")
    void memberSubscribesToState() {
        var message = frame(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId + "/state");

        assertSame(message, guard.preSend(message, null));
        verify(accessPolicy).requireMember(campaignId, userId);
        verify(accessPolicy, never()).requireDm(any(), any());
    }

    @Test
    @DisplayName("Subscribing to the Player View is refused for a user outside the campaign")
    void nonMemberIsRefusedOnState() {
        doThrow(new ForbiddenException("You are not a member of this campaign"))
                .when(accessPolicy).requireMember(campaignId, userId);

        var ex = assertThrows(ForbiddenException.class,
                () -> send(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId + "/state"));

        assertEquals("You are not a member of this campaign", ex.getMessage());
    }

    @Test
    @DisplayName("The DM View needs the campaign's DM, not just a member")
    void dmStateNeedsTheDm() {
        doThrow(new ForbiddenException("Only the DM can perform this action"))
                .when(accessPolicy).requireDm(campaignId, userId);

        assertThrows(ForbiddenException.class,
                () -> send(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId + "/dm-state"));
        verify(accessPolicy, never()).requireMember(any(), any());
    }

    @Test
    @DisplayName("The DM may subscribe to the DM View")
    void dmSubscribesToDmState() {
        send(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId + "/dm-state");

        verify(accessPolicy).requireDm(campaignId, userId);
    }

    @Test
    @DisplayName("Anyone signed in may subscribe to their own error queue")
    void errorQueueIsAllowed() {
        send(StompCommand.SUBSCRIBE, "/user/queue/encounter/errors");

        verifyNoInteractions(encounterRepository, accessPolicy);
    }

    @Test
    @DisplayName("Subscribing to an encounter that does not exist is refused")
    void unknownEncounterIsRefused() {
        UUID unknownId = UUID.randomUUID();
        when(encounterRepository.findCampaignIdById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> send(StompCommand.SUBSCRIBE, "/topic/encounter/" + unknownId + "/state"));
        verifyNoInteractions(accessPolicy);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "/topic/**",
            "/topic/encounter/**",
            "/topic/encounter/*/state",
            "/topic/encounter/{id}/state",
            "/topic/encounter/not-a-uuid/state",
            "/topic/encounter/state",
            "/queue/encounter/errors",
            "/user/queue/encounter/errors/**",
            "/app/encounter/join",
            ""})
    @DisplayName("Any other subscription, wildcards included, is refused before any lookup")
    void otherSubscriptionsAreRefused(String destination) {
        assertThrows(ForbiddenException.class, () -> send(StompCommand.SUBSCRIBE, destination));
        verifyNoInteractions(encounterRepository, accessPolicy);
    }

    @Test
    @DisplayName("A subscription must match exactly, so a trailing segment or an upper-case ID is refused")
    void subscriptionMustMatchExactly() {
        assertThrows(ForbiddenException.class,
                () -> send(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId + "/state/extra"));
        assertThrows(ForbiddenException.class,
                () -> send(StompCommand.SUBSCRIBE, "/topic/encounter/" + encounterId.toString().toUpperCase() + "/state"));
        verifyNoInteractions(encounterRepository, accessPolicy);
    }

    @Test
    @DisplayName("A subscription without a signed-in user is refused")
    void subscriptionWithoutUserIsRefused() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/encounter/errors");
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(ForbiddenException.class, () -> guard.preSend(message, null));
    }

    @Test
    @DisplayName("A client may send to the application's handlers under /app")
    void sendToAppIsAllowed() {
        var message = frame(StompCommand.SEND, "/app/encounter/" + encounterId + "/join");

        assertSame(message, guard.preSend(message, null));
        verifyNoInteractions(encounterRepository, accessPolicy);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "/topic/encounter/00000000-0000-0000-0000-000000000000/state",
            "/queue/encounter/errors",
            "/user/someone/queue/encounter/errors",
            "/app/encounter/*/join",
            "/application/encounter/join",
            ""})
    @DisplayName("Sending anywhere but /app, or to a pattern, is refused")
    void otherSendsAreRefused(String destination) {
        assertThrows(ForbiddenException.class, () -> send(StompCommand.SEND, destination));
    }

    @Test
    @DisplayName("Frames other than SUBSCRIBE and SEND pass unchecked")
    void otherFramesPass() {
        send(StompCommand.UNSUBSCRIBE, null);
        send(StompCommand.DISCONNECT, null);
        send(StompCommand.CONNECT, null);

        verifyNoInteractions(encounterRepository, accessPolicy);
    }
}
