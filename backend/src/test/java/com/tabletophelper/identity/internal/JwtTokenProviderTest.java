package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.internal.JwtTokenProvider.AccessTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
    @DisplayName("An access token parses back to its userId, username and expiry")
    void generateAccessToken_parsesBackToItsClaims() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String username = "thorin_oakenshield_42";

        String token = jwtTokenProvider.generateAccessToken(userId, username);

        // JWT should have 3 parts separated by dots
        assertEquals(3, token.split("\\.").length);
        AccessTokenClaims claims = jwtTokenProvider.parseAccessToken(token).orElseThrow();
        assertEquals(userId, claims.userId());
        assertEquals(username, claims.username());
        // JWT expiry has whole-second precision
        long secondsLeft = Duration.between(Instant.now(), claims.expiresAt()).toSeconds();
        assertTrue(secondsLeft > 890 && secondsLeft <= 900, "expires in " + secondsLeft + "s");
        // The issue time, which lets a CONNECT in flight during logout be closed (D238 review)
        assertTrue(Math.abs(Duration.between(Instant.now(), claims.issuedAt()).toSeconds()) <= 5);
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

    @Test
    @DisplayName("The development secret committed in application-dev.yml starts only under the dev or test profile (D221)")
    void constructor_refusesDevSecretOutsideDev() throws Exception {
        String devYaml;
        try (var in = getClass().getResourceAsStream("/application-dev.yml")) {
            assertNotNull(in, "application-dev.yml is on the classpath");
            devYaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(devYaml.contains("secret: " + JwtTokenProvider.DEV_SECRET),
                "DEV_SECRET is the secret application-dev.yml commits");

        String secret = JwtTokenProvider.DEV_SECRET;
        IllegalStateException prod = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRY_MS, new String[]{"prod"}));
        assertTrue(prod.getMessage().contains("JWT_SECRET"));
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRY_MS, new String[0]));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRY_MS));

        assertDoesNotThrow(() -> new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRY_MS, new String[]{"dev"}));
        assertDoesNotThrow(() -> new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRY_MS, new String[]{"prod", "test"}));
        assertDoesNotThrow(() -> new JwtTokenProvider(TEST_SECRET, ACCESS_TOKEN_EXPIRY_MS, new String[]{"prod"}));
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
