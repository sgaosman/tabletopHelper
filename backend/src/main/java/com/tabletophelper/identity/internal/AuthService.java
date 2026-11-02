package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.dto.AuthResponse;
import com.tabletophelper.identity.internal.dto.LoginRequest;
import com.tabletophelper.identity.internal.dto.RegisterRequest;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Each refresh issues a new token with this lifetime, and the cookie lasts as long (D156). */
    static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException("Username already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email already registered");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName() != null ? request.getDisplayName() : request.getUsername())
                .build();

        user = userRepository.save(user);

        return startSession(user);
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ValidationException("Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ValidationException("Invalid username or password");
        }

        return startSession(user);
    }

    /**
     * Exchanges a refresh token for a new one and a new access token. Empty when the token is
     * missing, unknown, expired or revoked. A token that was already replaced means someone else
     * holds a copy, so its whole family is revoked. Nothing is thrown, so that revocation commits;
     * the controller answers 401.
     */
    @Transactional
    public Optional<AuthResult> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHashForUpdate(hash(refreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken current = found.get();
        Instant now = Instant.now();
        if (current.getReplacedById() != null) {
            int revoked = refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            log.warn("Replaced refresh token presented again for user {}; revoked {} token(s) in family {}",
                    current.getUser().getId(), revoked, current.getFamilyId());
            return Optional.empty();
        }
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        String nextToken = newRefreshToken();
        RefreshToken next = saveRefreshToken(current.getUser(), current.getFamilyId(), nextToken, now);
        current.setReplacedById(next.getId());
        return Optional.of(buildResult(current.getUser(), nextToken));
    }

    /** Revokes every token from the presented token's login. A missing or unknown token is ignored. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHashForUpdate(hash(refreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /** SHA-256 of the token as lowercase hex; only this is stored. */
    static String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A login starts a new token family. */
    private AuthResult startSession(User user) {
        String refreshToken = newRefreshToken();
        saveRefreshToken(user, UUID.randomUUID(), refreshToken, Instant.now());
        return buildResult(user, refreshToken);
    }

    private RefreshToken saveRefreshToken(User user, UUID familyId, String refreshToken, Instant now) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .familyId(familyId)
                .tokenHash(hash(refreshToken))
                .issuedAt(now)
                .expiresAt(now.plus(REFRESH_TOKEN_LIFETIME))
                .build());
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AuthResult buildResult(User user, String refreshToken) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());

        AuthResponse response = AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .accessToken(accessToken)
                .build();
        return new AuthResult(response, refreshToken);
    }
}
