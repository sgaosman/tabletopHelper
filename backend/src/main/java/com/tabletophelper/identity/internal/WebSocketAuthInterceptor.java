package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.internal.JwtTokenProvider.AccessTokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Authenticates CONNECT with an access token and records the connection's user and token expiry in
 * {@link WebSocketSessions}. SUBSCRIBE and SEND frames on a connection whose token has expired are refused, which
 * closes it; the expiry sweep closes such a connection even if it sends nothing (D238).
 */
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final WebSocketSessions webSocketSessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            // Only an access token is accepted; the client fetches a fresh one before each connect (D156)
            AccessTokenClaims claims = jwtTokenProvider.parseAccessToken(extractToken(accessor))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or missing JWT token for WebSocket connection"));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claims.userId(), null, Collections.emptyList());
            auth.setDetails(claims.username());
            accessor.setUser(auth);
            webSocketSessions.authenticated(accessor.getSessionId(), claims.userId(), claims.issuedAt(),
                    claims.expiresAt());
        }
        else if ((StompCommand.SUBSCRIBE.equals(command) || StompCommand.SEND.equals(command))
                && webSocketSessions.isExpired(accessor.getSessionId())) {
            throw new IllegalStateException("The access token for this WebSocket connection has expired");
        }
        return message;
    }

    private String extractToken(StompHeaderAccessor accessor) {
        List<String> authHeaders = accessor.getNativeHeader("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String header = authHeaders.get(0);
            if (header.startsWith("Bearer ")) {
                return header.substring(7);
            }
            return header;
        }

        List<String> tokenHeaders = accessor.getNativeHeader("token");
        if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
            return tokenHeaders.get(0);
        }

        return null;
    }
}
