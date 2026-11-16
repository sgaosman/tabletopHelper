package com.tabletophelper.identity.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Logout closes the user's WebSockets (D238). */
class AuthControllerTest {

    private AuthService authService;
    private WebSocketSessions webSocketSessions;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        webSocketSessions = mock(WebSocketSessions.class);
        controller = new AuthController(authService, webSocketSessions, true);
    }

    @Test
    @DisplayName("A logout that ends a login closes that user's WebSockets")
    void logout_closesTheUsersSockets() {
        UUID userId = UUID.randomUUID();
        when(authService.logout("cookie")).thenReturn(Optional.of(userId));

        var response = controller.logout("cookie");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(webSocketSessions).closeAllFor(userId);
    }

    @Test
    @DisplayName("A logout without a live login closes no WebSockets")
    void logout_withoutUser_closesNothing() {
        when(authService.logout(any())).thenReturn(Optional.empty());

        controller.logout(null);
        controller.logout("stale");

        verify(webSocketSessions, never()).closeAllFor(any());
    }
}
