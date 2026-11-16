package com.tabletophelper.identity.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The open STOMP connections, each with the user and access-token expiry it was authenticated with at CONNECT, so
 * the server can close a connection that outlives its token or its user's logout (D238).
 *
 * <p>Thread safety: WebSocket threads open, authenticate and remove sessions, the expiry sweep and logout requests
 * close them. The map is concurrent, and each entry's authentication is one immutable record behind a volatile field,
 * so a reader sees the user and the expiry together or neither. A session is removed from the map before it is
 * closed, so two threads never both close it.
 */
@Slf4j
@Component
public class WebSocketSessions {

    /** Code 1008: the connection's access token expired. The client reconnects with a fresh token. */
    static final CloseStatus TOKEN_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Access token expired");
    /** Code 1008: the user logged out. */
    static final CloseStatus SIGNED_OUT = CloseStatus.POLICY_VIOLATION.withReason("Signed out");

    private final Map<String, OpenSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebSocketSessions() {
        this(Clock.systemUTC());
    }

    WebSocketSessions(Clock clock) {
        this.clock = clock;
    }

    /** A socket opened; it has not sent CONNECT yet. */
    void opened(WebSocketSession session) {
        sessions.put(session.getId(), new OpenSession(session));
    }

    /** A socket closed, however it closed. */
    void closed(String sessionId) {
        sessions.remove(sessionId);
    }

    /** CONNECT was accepted with a token for this user that expires at the given instant. */
    void authenticated(String sessionId, UUID userId, Instant expiresAt) {
        OpenSession open = sessions.get(sessionId);
        if (open != null) {
            open.auth = new Auth(userId, expiresAt);
        }
    }

    /** True when the session's token has expired; false for an unknown or not yet authenticated session. */
    boolean isExpired(String sessionId) {
        OpenSession open = sessionId == null ? null : sessions.get(sessionId);
        Auth auth = open == null ? null : open.auth;
        return auth != null && isExpired(auth, clock.instant());
    }

    /** Closes every session whose token has expired; returns how many. */
    int closeExpired() {
        Instant now = clock.instant();
        return closeWhere(auth -> isExpired(auth, now), TOKEN_EXPIRED);
    }

    /**
     * Closes every session of the user, on every device. A device or browser that is still signed in reconnects at
     * once with a fresh token from its own login, so this is harmless to it (D238).
     */
    int closeAllFor(UUID userId) {
        return closeWhere(auth -> auth.userId().equals(userId), SIGNED_OUT);
    }

    int openCount() {
        return sessions.size();
    }

    private int closeWhere(Predicate<Auth> matches, CloseStatus status) {
        int closed = 0;
        for (var entry : sessions.entrySet()) {
            OpenSession open = entry.getValue();
            Auth auth = open.auth;
            // remove(key, value) succeeds for one thread only, so each session is closed once
            if (auth != null && matches.test(auth) && sessions.remove(entry.getKey(), open)) {
                close(open.session, auth, status);
                closed++;
            }
        }
        return closed;
    }

    private static void close(WebSocketSession session, Auth auth, CloseStatus status) {
        log.info("Closing WebSocket session {} of user {}: {}", session.getId(), auth.userId(), status.getReason());
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("WebSocket session {} did not close cleanly", session.getId(), e);
        }
    }

    private static boolean isExpired(Auth auth, Instant now) {
        return !auth.expiresAt().isAfter(now);
    }

    private record Auth(UUID userId, Instant expiresAt) {}

    private static final class OpenSession {
        private final WebSocketSession session;
        private volatile Auth auth;

        private OpenSession(WebSocketSession session) {
            this.session = session;
        }
    }
}
