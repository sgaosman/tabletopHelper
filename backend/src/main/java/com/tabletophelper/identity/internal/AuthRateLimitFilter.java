package com.tabletophelper.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Limits login and registration attempts, so passwords can't be guessed at speed: logins by IP address and by the
 * username tried, registrations by IP address, each a number of attempts in a fixed window
 * ({@code app.auth.rate-limit.*}). An attempt past the limit is a 429 with Retry-After and an {"error": ...} body,
 * before the request reaches the controller. The counts are held in memory (AttemptLimiter). Behind a reverse proxy
 * the IP address is the proxy's unless the server reads X-Forwarded-For ({@code server.forward-headers-strategy}).
 *
 * <p>The path is matched decoded and normalised, as the controller mapping matches it, so no encoding of the login or
 * registration path (such as {@code /api/auth/%6Cogin}) slips past the count (D221). Their bodies are read before
 * authentication, so they are capped at {@link #MAX_BODY_BYTES}: a larger one is a 413 and is never read whole.
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN = "/api/auth/login";
    static final String REGISTER = "/api/auth/register";
    /** A login or registration body is a username, an email and a password of at most 100 characters each (D221). */
    static final int MAX_BODY_BYTES = 4 * 1024;

    // Decodes the path and drops ;-parameters and doubled slashes, as the request mapping does
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /** The attempts each key may make in a window. */
    record Limits(int loginPerIp, int loginPerUsername, int registerPerIp, Duration window) {
    }

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Limits configured;
    private volatile Limiters limiters;

    private record Limiters(AttemptLimiter loginByIp, AttemptLimiter loginByUsername, AttemptLimiter registerByIp) {

        static Limiters of(Limits limits) {
            return new Limiters(limiter(limits.loginPerIp(), limits.window()),
                    limiter(limits.loginPerUsername(), limits.window()),
                    limiter(limits.registerPerIp(), limits.window()));
        }

        private static AttemptLimiter limiter(int limit, Duration window) {
            return new AttemptLimiter(limit, window, System::currentTimeMillis);
        }
    }

    public AuthRateLimitFilter(ObjectMapper objectMapper,
                               @Value("${app.auth.rate-limit.enabled:true}") boolean enabled,
                               @Value("${app.auth.rate-limit.window:1m}") Duration window,
                               @Value("${app.auth.rate-limit.login-per-ip:20}") int loginPerIp,
                               @Value("${app.auth.rate-limit.login-per-username:10}") int loginPerUsername,
                               @Value("${app.auth.rate-limit.register-per-ip:5}") int registerPerIp) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.configured = new Limits(loginPerIp, loginPerUsername, registerPerIp, window);
        this.limiters = Limiters.of(configured);
    }

    /** Uses these limits with every count forgotten, for a test; {@link #useConfiguredLimits} goes back. */
    void useLimits(Limits limits) {
        this.limiters = Limiters.of(limits);
    }

    /** The limits the properties give, with every count forgotten. */
    void useConfiguredLimits() {
        this.limiters = Limiters.of(configured);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!enabled || !"POST".equals(request.getMethod())) return true;
        String path = path(request);
        return !LOGIN.equals(path) && !REGISTER.equals(path);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Limiters current = limiters;
        String ip = request.getRemoteAddr();
        boolean login = LOGIN.equals(path(request));
        Optional<Duration> wait = login ? current.loginByIp().attempt(ip) : current.registerByIp().attempt(ip);
        if (wait.isPresent()) {
            tooManyAttempts(request, response, wait.get());
            return;
        }
        byte[] body = boundedBody(request);
        if (body == null) {
            log.warn("A body over {} bytes at {} from {}; refused", MAX_BODY_BYTES, path(request), ip);
            refuse(response, HttpStatus.CONTENT_TOO_LARGE, "The request is too large");
            return;
        }
        if (login) {
            String username = usernameOf(body);
            if (username != null) {
                wait = current.loginByUsername().attempt(username.trim().toLowerCase(Locale.ROOT));
                if (wait.isPresent()) {
                    tooManyAttempts(request, response, wait.get());
                    return;
                }
            }
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    // The request's path within the application, decoded and normalised (D221)
    private static String path(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request);
    }

    // The body, read no further than one byte past the cap; null when it is over the cap, by its length or its bytes
    private static byte[] boundedBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    // The username a login body names, or null for a body that isn't JSON or names none; the controller refuses those
    private String usernameOf(byte[] body) {
        try {
            JsonNode username = objectMapper.readTree(body).path("username");
            return username.isString() && !username.asString().isBlank() ? username.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private void tooManyAttempts(HttpServletRequest request, HttpServletResponse response, Duration wait)
            throws IOException {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        // The IP address only: a username in the log would tell which accounts are being tried
        log.warn("Too many attempts at {} from {}; refused for {}s", path(request), request.getRemoteAddr(), seconds);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        refuse(response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Try again in " + seconds + " second" + (seconds == 1 ? "" : "s") + ".");
    }

    private void refuse(HttpServletResponse response, HttpStatus status, String error) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", error)));
    }

    /** The request with its body read once and given again to whatever reads it next. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body is already read");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
