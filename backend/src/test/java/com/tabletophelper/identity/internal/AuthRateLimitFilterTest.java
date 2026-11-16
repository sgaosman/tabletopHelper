package com.tabletophelper.identity.internal;

import com.tabletophelper.shared.JacksonDefaults;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The login and registration limits: AttemptLimiter's fixed windows, and the filter's keys and 429. */
class AuthRateLimitFilterTest {

    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(JacksonDefaults.mapper(), true,
            Duration.ofMinutes(1), 3, 2, 1);

    @Test
    @DisplayName("A key gets the limit's attempts in a window, then waits for its end; another key has its own count")
    void fixedWindow() {
        AtomicLong now = new AtomicLong(1_000);
        AttemptLimiter limiter = new AttemptLimiter(2, Duration.ofSeconds(60), now::get);

        assertEquals(Optional.empty(), limiter.attempt("a"));
        now.addAndGet(10_000);
        assertEquals(Optional.empty(), limiter.attempt("a"));
        assertEquals(Optional.of(Duration.ofSeconds(50)), limiter.attempt("a"));
        assertEquals(Optional.empty(), limiter.attempt("b"));

        now.addAndGet(50_000);
        assertEquals(Optional.empty(), limiter.attempt("a"), "a new window starts once the old one ends");
    }

    @Test
    @DisplayName("Logins past the username's limit are a 429 with Retry-After and an error body, whatever the case of the name")
    void loginByUsername() throws Exception {
        assertEquals(200, login("Thorin", "10.0.0.1").getStatus());
        assertEquals(200, login("thorin", "10.0.0.2").getStatus());

        MockHttpServletResponse refused = login("THORIN", "10.0.0.3");

        assertEquals(429, refused.getStatus());
        assertEquals("60", refused.getHeader("Retry-After"));
        assertTrue(refused.getContentAsString().contains("\"error\":\"Too many attempts. Try again in 60 seconds.\""),
                refused.getContentAsString());
        assertEquals(200, login("balin", "10.0.0.3").getStatus());
    }

    @Test
    @DisplayName("Logins past the IP address's limit are refused for any username")
    void loginByIp() throws Exception {
        assertEquals(200, login("a", "10.0.0.9").getStatus());
        assertEquals(200, login("b", "10.0.0.9").getStatus());
        assertEquals(200, login("c", "10.0.0.9").getStatus());

        assertEquals(429, login("d", "10.0.0.9").getStatus());
    }

    @Test
    @DisplayName("The controller still reads the login body the filter read, and a body that isn't JSON passes to be refused there")
    void bodyReadAgain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = post(AuthRateLimitFilter.LOGIN, "{\"username\":\"dwalin\"}", "10.0.1.1");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertEquals("{\"username\":\"dwalin\"}", new String(passed.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
        assertEquals(200, send(post(AuthRateLimitFilter.LOGIN, "not json", "10.0.1.2")).getStatus());
    }

    @Test
    @DisplayName("Registrations are limited by IP address; other auth paths and methods are never counted")
    void registerAndOtherPaths() throws Exception {
        assertEquals(200, send(post(AuthRateLimitFilter.REGISTER, "{}", "10.0.2.1")).getStatus());
        assertEquals(429, send(post(AuthRateLimitFilter.REGISTER, "{}", "10.0.2.1")).getStatus());

        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(post("/api/auth/refresh", "", "10.0.2.1")).getStatus());
        }
        MockHttpServletRequest get = post(AuthRateLimitFilter.LOGIN, "", "10.0.2.1");
        get.setMethod("GET");
        assertEquals(200, send(get).getStatus());
    }

    @Test
    @DisplayName("Turned off, nothing is counted")
    void disabled() throws Exception {
        AuthRateLimitFilter off = new AuthRateLimitFilter(JacksonDefaults.mapper(), false, Duration.ofMinutes(1),
                1, 1, 1);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            off.doFilter(post(AuthRateLimitFilter.LOGIN, "{\"username\":\"ori\"}", "10.0.3.1"), response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }

    private MockHttpServletResponse login(String username, String ip) throws Exception {
        return send(post(AuthRateLimitFilter.LOGIN, "{\"username\":\"" + username + "\",\"password\":\"x\"}", ip));
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest post(String path, String body, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
