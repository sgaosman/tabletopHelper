package com.tabletophelper.identity.internal;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration and login over HTTP at their limits: a password over BCrypt's 72 bytes is a 400, never a 500, and
 * attempts past the rate limits are a 429 with Retry-After.
 */
class AuthLimitsIT extends IntegrationTest {

    @Autowired private AuthRateLimitFilter rateLimitFilter;

    @AfterEach
    void configuredLimits() {
        rateLimitFilter.useConfiguredLimits();
    }

    private ResponseEntity<String> registerWith(String username, String password) {
        return exchange(HttpMethod.POST, "/api/auth/register", Map.of("username", username,
                "email", username + "@example.com", "password", password), null);
    }

    private ResponseEntity<String> login(String username, String password) {
        return exchange(HttpMethod.POST, "/api/auth/login", Map.of("username", username, "password", password),
                null);
    }

    @Test
    @DisplayName("Registering with a password of 73 bytes is a 400 naming the limit; 72 bytes registers and logs in")
    void passwordBytes() {
        assertThat(expect(HttpStatus.BAD_REQUEST, registerWith("gimli", "a".repeat(73))).get("error").asString())
                .contains("72 bytes");
        // 25 characters of 3 bytes each pass the 100-character limit but not the 72 bytes
        assertThat(registerWith("gimli", "€".repeat(25)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();

        expect(HttpStatus.CREATED, registerWith("gimli", "a".repeat(72)));
        expect(HttpStatus.OK, login("gimli", "a".repeat(72)));
    }

    @Test
    @DisplayName("Logging in with a password over 72 bytes is the usual 400, for a known username and an unknown one")
    void loginWithLongPassword() {
        register("gimli");

        assertThat(expect(HttpStatus.BAD_REQUEST, login("gimli", "a".repeat(100))).get("error").asString())
                .isEqualTo("Invalid username or password");
        expect(HttpStatus.BAD_REQUEST, login("nobody", "a".repeat(100)));
        expect(HttpStatus.BAD_REQUEST, login("nobody", "password123"));
    }

    @Test
    @DisplayName("Logins past the username's limit are a 429 with Retry-After, even with the right password, and the "
            + "controller still reads the body of those allowed")
    void loginRateLimit() {
        register("gimli");
        rateLimitFilter.useLimits(new AuthRateLimitFilter.Limits(100, 2, 100, Duration.ofMinutes(1)));

        expect(HttpStatus.BAD_REQUEST, login("gimli", "wrong-password"));
        expect(HttpStatus.OK, login("gimli", "password123"));
        ResponseEntity<String> refused = login("gimli", "password123");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(json(refused).get("error").asString()).startsWith("Too many attempts");
    }

    @Test
    @DisplayName("Registrations past the IP address's limit are a 429")
    void registerRateLimit() {
        rateLimitFilter.useLimits(new AuthRateLimitFilter.Limits(100, 100, 1, Duration.ofMinutes(1)));

        expect(HttpStatus.CREATED, registerWith("gimli", "password123"));

        assertThat(registerWith("balin", "password123").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
