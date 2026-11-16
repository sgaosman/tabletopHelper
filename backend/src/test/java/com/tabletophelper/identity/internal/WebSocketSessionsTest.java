package com.tabletophelper.identity.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The registry of open WebSockets that closes them at token expiry and at logout (D238). */
class WebSocketSessionsTest {

    private static final Instant START = Instant.parse("2026-11-16T12:00:00Z");

    private MutableClock clock;
    private WebSocketSessions sessions;
    private final UUID aria = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        sessions = new WebSocketSessions(clock);
    }

    @Test
    @DisplayName("The sweep closes only sessions whose token has expired, with 1008, and forgets them")
    void closeExpired_closesOnlyExpiredSessions() throws IOException {
        var expiring = open("s1", aria, START.plusSeconds(60));
        var lasting = open("s2", bram, START.plusSeconds(600));

        clock.advance(Duration.ofSeconds(60));
        int closed = sessions.closeExpired();

        assertEquals(1, closed);
        verify(expiring).close(WebSocketSessions.TOKEN_EXPIRED);
        verify(lasting, never()).close(any());
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), WebSocketSessions.TOKEN_EXPIRED.getCode());
        assertEquals(1, sessions.openCount());
        // Already closed, so a second sweep closes nothing
        assertEquals(0, sessions.closeExpired());
    }

    @Test
    @DisplayName("The sweep leaves alone a socket that has not sent CONNECT yet")
    void closeExpired_ignoresUnauthenticatedSessions() throws IOException {
        WebSocketSession pending = session("s1");
        sessions.opened(pending);

        clock.advance(Duration.ofDays(1));

        assertEquals(0, sessions.closeExpired());
        verify(pending, never()).close(any());
    }

    @Test
    @DisplayName("Logout closes every session of that user, and no other user's")
    void closeAllFor_closesOnlyThatUsersSessions() throws IOException {
        var ariaTab = open("s1", aria, START.plusSeconds(600));
        var ariaPhone = open("s2", aria, START.plusSeconds(600));
        var bramTab = open("s3", bram, START.plusSeconds(600));

        assertEquals(2, sessions.closeAllFor(aria));

        verify(ariaTab).close(WebSocketSessions.SIGNED_OUT);
        verify(ariaPhone).close(WebSocketSessions.SIGNED_OUT);
        verify(bramTab, never()).close(any());
        assertEquals(1, sessions.openCount());
    }

    @Test
    @DisplayName("A token counts as expired from its expiry instant; unknown sessions are not expired")
    void isExpired_fromExpiryInstant() {
        open("s1", aria, START.plusSeconds(30));

        assertFalse(sessions.isExpired("s1"));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(sessions.isExpired("s1"));
        assertFalse(sessions.isExpired("unknown"));
        assertFalse(sessions.isExpired(null));
    }

    @Test
    @DisplayName("A closed socket is forgotten, and authenticating an unknown session does nothing")
    void closed_forgetsTheSession() throws IOException {
        var socket = open("s1", aria, START.plusSeconds(30));

        sessions.closed("s1");
        sessions.authenticated("gone", aria, START.plusSeconds(30));
        clock.advance(Duration.ofMinutes(1));

        assertEquals(0, sessions.openCount());
        assertEquals(0, sessions.closeExpired());
        verify(socket, never()).close(any());
    }

    @Test
    @DisplayName("A socket that fails to close is still forgotten, and the others are still closed")
    void close_failureIsSwallowed() throws IOException {
        var broken = open("s1", aria, START.plusSeconds(10));
        var fine = open("s2", aria, START.plusSeconds(10));
        doThrow(new IOException("gone")).when(broken).close(any());

        clock.advance(Duration.ofSeconds(10));

        assertEquals(2, sessions.closeExpired());
        verify(fine).close(WebSocketSessions.TOKEN_EXPIRED);
        assertEquals(0, sessions.openCount());
    }

    @Test
    @DisplayName("Sweeps and a logout racing close each session exactly once")
    void concurrentClosesCloseEachSessionOnce() throws Exception {
        int count = 200;
        WebSocketSession[] sockets = new WebSocketSession[count];
        for (int i = 0; i < count; i++) {
            sockets[i] = open("s" + i, aria, START.plusSeconds(1));
        }
        clock.advance(Duration.ofSeconds(1));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var sweep = pool.submit(() -> {
                go.await();
                return sessions.closeExpired();
            });
            var logout = pool.submit(() -> {
                go.await();
                return sessions.closeAllFor(aria);
            });
            var secondSweep = pool.submit(() -> {
                go.await();
                return sessions.closeExpired();
            });
            go.countDown();
            int total = sweep.get(5, TimeUnit.SECONDS) + logout.get(5, TimeUnit.SECONDS)
                    + secondSweep.get(5, TimeUnit.SECONDS);
            assertEquals(count, total);
        } finally {
            pool.shutdownNow();
        }
        for (WebSocketSession socket : sockets) {
            verify(socket, times(1)).close(any());
        }
    }

    private WebSocketSession open(String id, UUID userId, Instant expiresAt) {
        WebSocketSession socket = session(id);
        sessions.opened(socket);
        sessions.authenticated(id, userId, expiresAt);
        return socket;
    }

    private static WebSocketSession session(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        return socket;
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
