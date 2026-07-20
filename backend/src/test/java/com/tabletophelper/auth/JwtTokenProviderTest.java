package com.tabletophelper.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private static final String TEST_SECRET = "ThisIsATestSecretKeyForJwtTokenProviderTestsThatIsLongEnoughForHS256";
    private static final long ACCESS_TOKEN_EXPIRY_MS = 3600000; // 1 hour
    private static final long REFRESH_TOKEN_EXPIRY_MS = 86400000; // 24 hours

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, ACCESS_TOKEN_EXPIRY_MS, REFRESH_TOKEN_EXPIRY_MS);
    }

    @Test
    @DisplayName("Generate access token contains userId and username claims")
    void generateAccessToken_containsCorrectClaims() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String username = "thorin";

        String token = jwtTokenProvider.generateAccessToken(userId, username);

        assertNotNull(token);
        // JWT should have 3 parts separated by dots
        assertEquals(3, token.split("\\.").length);
        // Validate it passes validation
        assertTrue(jwtTokenProvider.validateToken(token));
        // Verify claims
        assertEquals(userId, jwtTokenProvider.getUserIdFromToken(token));
        assertEquals(username, jwtTokenProvider.getUsernameFromToken(token));
    }

    @Test
    @DisplayName("Validate token: valid returns true, expired and malformed return false")
    void validateToken_variousInputs() {
        UUID userId = UUID.randomUUID();
        String validToken = jwtTokenProvider.generateAccessToken(userId, "thorin");

        // Valid token
        assertTrue(jwtTokenProvider.validateToken(validToken));

        // Malformed token
        assertFalse(jwtTokenProvider.validateToken("not.a.jwt"));
        assertFalse(jwtTokenProvider.validateToken(""));
        assertFalse(jwtTokenProvider.validateToken("random-string"));

        // Token signed with different secret
        JwtTokenProvider otherProvider = new JwtTokenProvider(
                "DifferentSecretKeyThatIsAlsoLongEnoughForTheHS256AlgorithmRequirements",
                ACCESS_TOKEN_EXPIRY_MS, REFRESH_TOKEN_EXPIRY_MS);
        String otherToken = otherProvider.generateAccessToken(userId, "thorin");
        assertFalse(jwtTokenProvider.validateToken(otherToken));

        // Expired token (use a provider with 0ms expiry)
        JwtTokenProvider expiredProvider = new JwtTokenProvider(TEST_SECRET, 0, 0);
        String expiredToken = expiredProvider.generateAccessToken(userId, "thorin");
        assertFalse(jwtTokenProvider.validateToken(expiredToken));
    }

    @Test
    @DisplayName("getUserIdFromToken extracts correct UUID")
    void getUserIdFromToken_extractsCorrectUuid() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String token = jwtTokenProvider.generateAccessToken(userId, "thorin");

        UUID extractedId = jwtTokenProvider.getUserIdFromToken(token);

        assertEquals(userId, extractedId);
    }

    @Test
    @DisplayName("getUsernameFromToken extracts correct username")
    void getUsernameFromToken_extractsCorrectUsername() {
        UUID userId = UUID.randomUUID();
        String username = "thorin_oakenshield_42";
        String token = jwtTokenProvider.generateAccessToken(userId, username);

        String extractedUsername = jwtTokenProvider.getUsernameFromToken(token);

        assertEquals(username, extractedUsername);
    }
}
