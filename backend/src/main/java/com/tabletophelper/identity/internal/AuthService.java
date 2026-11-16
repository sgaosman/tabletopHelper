package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.dto.AuthResponse;
import com.tabletophelper.identity.internal.dto.LoginRequest;
import com.tabletophelper.identity.internal.dto.RegisterRequest;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    /** The most bytes of a password BCrypt reads (UTF-8). */
    static final int MAX_PASSWORD_BYTES = 72;
    private static final String INVALID_CREDENTIALS = "Invalid username or password";
    private static final String DUMMY_PASSWORD = "no-account-has-this-password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private volatile String dummyHash;

    @Transactional
    public AuthResult register(RegisterRequest request) {
        // BCrypt reads only the first 72 bytes, and Spring Security's encoder refuses more
        if (passwordBytes(request.getPassword()) > MAX_PASSWORD_BYTES) {
            throw new ValidationException("Password must be at most " + MAX_PASSWORD_BYTES
                    + " bytes; letters outside plain English, such as accented letters or emoji, take 2 to 4 each");
        }
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

        // Two registrations racing for the same name or email pass the checks above; the unique constraint stops the
        // second, which is then the same 409
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Username or email already registered");
        }

        return startSession(user);
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        // A password no account can have (over 72 bytes, which registration refuses) is wrong, not a 500
        boolean tooLong = passwordBytes(request.getPassword()) > MAX_PASSWORD_BYTES;
        Optional<User> found = userRepository.findByUsername(request.getUsername());
        // An unknown username takes as long as a wrong password, so the time taken doesn't tell which names exist
        if (found.isEmpty() || tooLong) {
            passwordEncoder.matches(DUMMY_PASSWORD, dummyHash());
            throw new ValidationException(INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ValidationException(INVALID_CREDENTIALS);
        }

        return startSession(user);
    }

    private static int passwordBytes(String password) {
        return password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
    }

    // A hash of a password no one uses, made once with the real encoder's cost, for the unknown username's comparison
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(DUMMY_PASSWORD);
            dummyHash = hash;
        }
        return hash;
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
