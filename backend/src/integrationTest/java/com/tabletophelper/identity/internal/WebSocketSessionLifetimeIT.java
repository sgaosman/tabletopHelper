package com.tabletophelper.identity.internal;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A STOMP connection lives no longer than the access token it was opened with, nor than its user's login (D238).
 * The it profile sweeps for expired connections every half second.
 */
class WebSocketSessionLifetimeIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void createClient() {
        stompClient = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void disconnect() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }

    @Test
    @DisplayName("A connection opened with a short-lived token is closed once the token expires; "
            + "another user's connection stays open")
    void connectionClosesWhenTokenExpires() throws Exception {
        var aria = register("aria");
        var bram = register("bram");
        // Signed with the server's key, so the server accepts it, but valid for only two seconds
        String shortLived = new JwtTokenProvider(jwtSecret, 2000).generateAccessToken(aria.id(), "aria");

        WatchedSession ariaSession = connect(shortLived);
        WatchedSession bramSession = connect(bram.accessToken());
        assertThat(ariaSession.session().isConnected()).isTrue();

        assertThat(ariaSession.connectionLost().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ConnectionLostException.class);
        // A sweep or two later, the other user's connection is still open
        TimeUnit.SECONDS.sleep(1);
        assertThat(bramSession.connectionLost()).isNotDone();
        assertThat(bramSession.session().isConnected()).isTrue();
    }

    @Test
    @DisplayName("Logout closes the user's connection; another user's connection stays open")
    void logoutClosesTheUsersConnection() throws Exception {
        var aria = register("aria");
        var bram = register("bram");
        WatchedSession ariaSession = connect(aria.accessToken());
        WatchedSession bramSession = connect(bram.accessToken());

        logout(aria.refreshToken());

        assertThat(ariaSession.connectionLost().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ConnectionLostException.class);
        TimeUnit.SECONDS.sleep(1);
        assertThat(bramSession.connectionLost()).isNotDone();
        assertThat(bramSession.session().isConnected()).isTrue();
    }

    @Test
    @DisplayName("Logout with a refresh token that was already revoked closes nothing")
    void secondLogoutClosesNothing() throws Exception {
        var aria = register("aria");
        logout(aria.refreshToken());
        // Logout revokes no access token, so this one still connects until it expires; the cookie presented again
        // below names a login that has already ended
        WatchedSession ariaSession = connect(aria.accessToken());

        logout(aria.refreshToken());

        TimeUnit.SECONDS.sleep(1);
        assertThat(ariaSession.connectionLost()).isNotDone();
        assertThat(ariaSession.session().isConnected()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private record WatchedSession(StompSession session, CompletableFuture<Throwable> connectionLost) {}

    private WatchedSession connect(String accessToken) throws Exception {
        var connectionLost = new CompletableFuture<Throwable>();
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        StompSession session = stompClient.connectAsync("http://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        connectionLost.complete(exception);
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return new WatchedSession(session, connectionLost);
    }

    private void logout(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken);
        var response = restTemplate.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(null, headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
