package com.tabletophelper.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rotating refresh cookie (D156) through the full stack: the cookie's attributes, rotation,
 * reuse revoking the family, logout, and requests without a cookie. The it profile keeps the
 * production default, so the cookie is Secure.
 */
class RefreshTokenIT extends IntegrationTest {

    @Test
    @DisplayName("Login sets an HttpOnly, Secure, SameSite=Strict cookie on /api/auth for 7 days, and the body holds no refresh token")
    void loginSetsRefreshCookie() {
        register("aria");

        var response = post("/api/auth/login", null, Map.of("username", "aria", "password", "password123"));

        JsonNode body = expect(HttpStatus.OK, response);
        assertThat(body.has("refreshToken")).isFalse();
        assertThat(body.get("accessToken").asText()).isNotBlank();
        List<String> attributes = List.of(setCookie(response).split("; "));
        assertThat(attributes.get(0)).startsWith("refresh_token=");
        assertThat(attributes).contains("Path=/api/auth", "Max-Age=604800", "Secure", "HttpOnly", "SameSite=Strict");
    }

    @Test
    @DisplayName("Only the SHA-256 hash of the refresh token is stored")
    void onlyTheHashIsStored() {
        var user = register("aria");

        List<String> stored = jdbcTemplate.queryForList("SELECT token_hash FROM refresh_tokens", String.class);

        assertThat(stored).containsExactly(sha256Hex(user.refreshToken()));
    }

    @Test
    @DisplayName("Refreshing with the cookie returns a working access token and a new cookie, and replaces the old token")
    void refreshRotatesTheToken() {
        var user = register("aria");

        var response = post("/api/auth/refresh", user.refreshToken(), null);

        JsonNode body = expect(HttpStatus.OK, response);
        String next = refreshCookieValue(response);
        assertThat(next).isNotBlank().isNotEqualTo(user.refreshToken());
        assertThat(body.get("userId").asText()).isEqualTo(user.id().toString());
        expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters", null, body.get("accessToken").asText()));
        Map<String, Object> old = row(user.refreshToken());
        Map<String, Object> replacement = row(next);
        assertThat(old.get("replaced_by_id")).isEqualTo(replacement.get("id"));
        assertThat(old.get("family_id")).isEqualTo(replacement.get("family_id"));
        assertThat(replacement.get("replaced_by_id")).isNull();
        // The new cookie refreshes again
        expect(HttpStatus.OK, post("/api/auth/refresh", next, null));
    }

    @Test
    @DisplayName("Reusing a replaced token gets 401 and revokes both tokens in its family, but not another login's")
    void reuseRevokesTheFamily() {
        var user = register("aria");
        String next = refreshCookieValue(expectOk(post("/api/auth/refresh", user.refreshToken(), null)));
        String otherLogin = refreshCookieValue(expectOk(
                post("/api/auth/login", null, Map.of("username", "aria", "password", "password123"))));

        var reuse = post("/api/auth/refresh", user.refreshToken(), null);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(row(user.refreshToken()).get("revoked_at")).isNotNull();
        assertThat(row(next).get("revoked_at")).isNotNull();
        assertThat(post("/api/auth/refresh", next, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/auth/refresh", otherLogin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Logout revokes the session, clears the cookie, and the old cookie can no longer refresh")
    void logoutRevokesTheSession() {
        var user = register("aria");
        String next = refreshCookieValue(expectOk(post("/api/auth/refresh", user.refreshToken(), null)));

        var response = post("/api/auth/logout", next, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<String> attributes = List.of(setCookie(response).split("; "));
        assertThat(attributes.get(0)).isEqualTo("refresh_token=");
        assertThat(attributes).contains("Path=/api/auth", "Max-Age=0", "HttpOnly");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class)).isZero();
        assertThat(post("/api/auth/refresh", next, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Logout without a cookie still succeeds and clears the cookie")
    void logoutWithoutCookieSucceeds() {
        var response = post("/api/auth/logout", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(response)).startsWith("refresh_token=;").contains("Max-Age=0");
    }

    @Test
    @DisplayName("Refresh without a cookie gets 401 with an error body, even with the old JSON body, and sets no cookie")
    void refreshWithoutCookieIsUnauthorized() {
        var user = register("aria");

        var response = post("/api/auth/refresh", null, Map.of("refreshToken", user.refreshToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).get("error").asText()).isNotBlank();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(row(user.refreshToken()).get("replaced_by_id")).isNull();
    }

    @Test
    @DisplayName("Refresh with an unknown or expired token gets 401")
    void unknownOrExpiredTokenIsUnauthorized() {
        var user = register("aria");
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 minute'");

        var expired = post("/api/auth/refresh", user.refreshToken(), null);
        var unknown = post("/api/auth/refresh", "not-a-token-the-server-issued", null);

        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** POSTs with the refresh cookie when one is given; this client keeps no cookies of its own. */
    private ResponseEntity<String> post(String path, String refreshToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (refreshToken != null) {
            headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> expectOk(ResponseEntity<String> response) {
        expect(HttpStatus.OK, response);
        return response;
    }

    private static String setCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(1);
        return cookies.get(0);
    }

    private Map<String, Object> row(String refreshToken) {
        return jdbcTemplate.queryForMap(
                "SELECT id, family_id, replaced_by_id, revoked_at FROM refresh_tokens WHERE token_hash = ?",
                sha256Hex(refreshToken));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
