package com.tabletophelper.auth;

import com.tabletophelper.auth.JwtTokenProvider.AccessTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private static final String TEST_SECRET = "ThisIsATestSecretKeyForJwtTokenProviderTestsThatIsLongEnoughForHS256";
    private static final long ACCESS_TOKEN_EXPIRY_MS = 900000; // 15 minutes

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, ACCESS_TOKEN_EXPIRY_MS);
    }

    @Test
    @DisplayName("An access token parses back to its userId and username")
    void generateAccessToken_parsesBackToItsClaims() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String username = "thorin_oakenshield_42";

        String token = jwtTokenProvider.generateAccessToken(userId, username);

        // JWT should have 3 parts separated by dots
        assertEquals(3, token.split("\\.").length);
        assertEquals(Optional.of(new AccessTokenClaims(userId, username)), jwtTokenProvider.parseAccessToken(token));
    }

    @Test
    @DisplayName("An access token carries type: access and expires 15 minutes after it is issued")
    void generateAccessToken_carriesTypeAndFifteenMinuteLife() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "thorin");

        Claims claims = Jwts.parser().verifyWith(key(TEST_SECRET)).build().parseSignedClaims(token).getPayload();

        assertEquals("access", claims.get("type", String.class));
        assertEquals(900_000, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    @Test
    @DisplayName("parseAccessToken refuses malformed, empty and missing tokens")
    void parseAccessToken_refusesMalformedTokens() {
        assertTrue(jwtTokenProvider.parseAccessToken("not.a.jwt").isEmpty());
        assertTrue(jwtTokenProvider.parseAccessToken("random-string").isEmpty());
        assertTrue(jwtTokenProvider.parseAccessToken("").isEmpty());
        assertTrue(jwtTokenProvider.parseAccessToken(null).isEmpty());
    }

    @Test
    @DisplayName("parseAccessToken refuses a token signed with another secret")
    void parseAccessToken_refusesOtherSecret() {
        JwtTokenProvider otherProvider = new JwtTokenProvider(
                "DifferentSecretKeyThatIsAlsoLongEnoughForTheHS256AlgorithmRequirements", ACCESS_TOKEN_EXPIRY_MS);

        String otherToken = otherProvider.generateAccessToken(UUID.randomUUID(), "thorin");

        assertTrue(jwtTokenProvider.parseAccessToken(otherToken).isEmpty());
    }

    @Test
    @DisplayName("parseAccessToken refuses an expired token")
    void parseAccessToken_refusesExpiredToken() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(TEST_SECRET, -60_000);

        String expiredToken = expiredProvider.generateAccessToken(UUID.randomUUID(), "thorin");

        assertTrue(jwtTokenProvider.parseAccessToken(expiredToken).isEmpty());
    }

    @Test
    @DisplayName("parseAccessToken refuses a correctly signed token with no type, like those issued before D156")
    void parseAccessToken_refusesUntypedToken() {
        String untyped = signedToken(null);

        assertTrue(jwtTokenProvider.parseAccessToken(untyped).isEmpty());
    }

    @Test
    @DisplayName("parseAccessToken refuses a correctly signed token of another type")
    void parseAccessToken_refusesOtherType() {
        String refreshTyped = signedToken("refresh");

        assertTrue(jwtTokenProvider.parseAccessToken(refreshTyped).isEmpty());
    }

    @Test
    @DisplayName("Startup fails when the secret is missing or shorter than 32 bytes")
    void constructor_refusesShortSecret() {
        IllegalStateException empty = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("", ACCESS_TOKEN_EXPIRY_MS));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, ACCESS_TOKEN_EXPIRY_MS));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("a".repeat(31), ACCESS_TOKEN_EXPIRY_MS));

        assertTrue(empty.getMessage().contains("JWT_SECRET"));
        assertDoesNotThrow(() -> new JwtTokenProvider("a".repeat(32), ACCESS_TOKEN_EXPIRY_MS));
    }

    private static String signedToken(String type) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("username", "thorin")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ACCESS_TOKEN_EXPIRY_MS));
        if (type != null) {
            builder.claim("type", type);
        }
        return builder.signWith(key(TEST_SECRET)).compact();
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
