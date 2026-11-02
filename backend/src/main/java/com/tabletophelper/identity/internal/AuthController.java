package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.internal.dto.AuthResponse;
import com.tabletophelper.identity.internal.dto.LoginRequest;
import com.tabletophelper.identity.internal.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Registration, login, refresh and logout. The refresh token travels only in an httpOnly cookie
 * scoped to these endpoints; the body carries the access token (D156).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService, @Value("${app.auth.cookie-secure}") boolean cookieSecure) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return withRefreshCookie(ResponseEntity.status(HttpStatus.CREATED), authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(ResponseEntity.ok(), authService.login(request));
    }

    /** A missing, expired, revoked or reused cookie gets 401 (D155). */
    @ApiResponse(responseCode = "200", description = "A new access token; the rotated refresh token is in the cookie",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        Optional<AuthResult> result = authService.refresh(refreshToken);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Your session has ended. Please sign in again."));
        }
        return withRefreshCookie(ResponseEntity.ok(), result.get());
    }

    /** Revokes the session behind the cookie, if any, and tells the browser to delete the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(ResponseEntity.BodyBuilder builder, AuthResult result) {
        return builder
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(result.refreshToken(), AuthService.REFRESH_TOKEN_LIFETIME).toString())
                .body(result.response());
    }

    // Secure is off only in the dev profile, which runs on plain HTTP
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
