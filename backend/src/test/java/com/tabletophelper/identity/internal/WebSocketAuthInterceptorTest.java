package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.internal.JwtTokenProvider.AccessTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** CONNECT records the connection's token expiry; SUBSCRIBE and SEND on an expired connection are refused (D238). */
class WebSocketAuthInterceptorTest {

    private static final String SESSION_ID = "session-1";

    private JwtTokenProvider jwtTokenProvider;
    private WebSocketSessions sessions;
    private WebSocketAuthInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        sessions = mock(WebSocketSessions.class);
        interceptor = new WebSocketAuthInterceptor(jwtTokenProvider, sessions);
    }

    @Test
    @DisplayName("CONNECT with a valid token records the session's user and token expiry")
    void connect_recordsUserAndExpiry() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-11-16T12:15:00Z");
        when(jwtTokenProvider.parseAccessToken("good"))
                .thenReturn(Optional.of(new AccessTokenClaims(userId, "aria", expiresAt)));

        interceptor.preSend(frame(StompCommand.CONNECT, "good"), channel);

        verify(sessions).authenticated(SESSION_ID, userId, null, expiresAt);
    }

    @Test
    @DisplayName("CONNECT with an invalid token is refused and records nothing")
    void connect_invalidToken_isRefused() {
        when(jwtTokenProvider.parseAccessToken("bad")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(frame(StompCommand.CONNECT, "bad"), channel));
        verify(sessions, never()).authenticated(any(), any(), any(), any());
    }

    @Test
    @DisplayName("SUBSCRIBE and SEND on a connection whose token has expired are refused")
    void subscribeAndSend_onExpiredSession_areRefused() {
        when(sessions.isExpired(SESSION_ID)).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null), channel));
        assertThrows(IllegalStateException.class,
                () -> interceptor.preSend(frame(StompCommand.SEND, null), channel));
    }

    @Test
    @DisplayName("SUBSCRIBE and SEND on a live connection pass through, as does DISCONNECT on an expired one")
    void frames_onLiveSession_pass() {
        when(sessions.isExpired(SESSION_ID)).thenReturn(false);
        for (StompCommand command : new StompCommand[] {StompCommand.SUBSCRIBE, StompCommand.SEND}) {
            Message<byte[]> message = frame(command, null);
            assertSame(message, interceptor.preSend(message, channel));
        }

        when(sessions.isExpired(SESSION_ID)).thenReturn(true);
        Message<byte[]> disconnect = frame(StompCommand.DISCONNECT, null);
        assertSame(disconnect, interceptor.preSend(disconnect, channel));
    }

    private static Message<byte[]> frame(StompCommand command, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        if (token != null) {
            accessor.addNativeHeader("Authorization", "Bearer " + token);
        }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
            accessor.setDestination("/app/encounter/x/join");
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
