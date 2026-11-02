package com.tabletophelper.auth;

import com.tabletophelper.auth.dto.LoginRequest;
import com.tabletophelper.auth.dto.RegisterRequest;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks private AuthService authService;

    private UUID userId;
    private User existingUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        existingUser = User.builder()
                .id(userId)
                .username("thorin")
                .email("thorin@shire.com")
                .passwordHash("hashed_password")
                .displayName("thorin")
                .build();
    }

    @Test
    @DisplayName("Register creates the user, returns an access token and stores only the refresh token's hash")
    void register_createsUserAndStoresHashedRefreshToken() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("thorin");
        request.setEmail("thorin@shire.com");
        request.setPassword("Mithril123!");

        when(userRepository.existsByUsername("thorin")).thenReturn(false);
        when(userRepository.existsByEmail("thorin@shire.com")).thenReturn(false);
        when(passwordEncoder.encode("Mithril123!")).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("thorin"))).thenReturn("access_token");
        stubRefreshTokenSave();

        AuthResult result = authService.register(request);

        assertEquals(userId, result.response().getUserId());
        assertEquals("thorin", result.response().getUsername());
        assertEquals("access_token", result.response().getAccessToken());
        RefreshToken stored = savedRefreshToken();
        assertNotNull(result.refreshToken());
        assertEquals(AuthService.hash(result.refreshToken()), stored.getTokenHash());
        assertNotEquals(result.refreshToken(), stored.getTokenHash());
        assertEquals(64, stored.getTokenHash().length());
        assertEquals(userId, stored.getUser().getId());
        assertNotNull(stored.getFamilyId());
        assertEquals(Duration.ofDays(7), Duration.between(stored.getIssuedAt(), stored.getExpiresAt()));
        verify(passwordEncoder).encode("Mithril123!");
    }

    @Test
    @DisplayName("Register fails when username is already taken")
    void register_failsOnDuplicateUsername() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("thorin");
        request.setEmail("new@email.com");
        request.setPassword("Mithril123!");

        when(userRepository.existsByUsername("thorin")).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> authService.register(request));

        assertEquals("Username already taken", ex.getMessage());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("Each login returns a different random refresh token and starts its own family")
    void login_startsNewFamilyEachTime() {
        LoginRequest request = new LoginRequest();
        request.setUsername("thorin");
        request.setPassword("Mithril123!");

        when(userRepository.findByUsername("thorin")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("Mithril123!", "hashed_password")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("thorin"))).thenReturn("access_token");
        stubRefreshTokenSave();

        AuthResult first = authService.login(request);
        AuthResult second = authService.login(request);

        assertEquals("access_token", first.response().getAccessToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        assertNotEquals(saved.getAllValues().get(0).getFamilyId(), saved.getAllValues().get(1).getFamilyId());
    }

    @Test
    @DisplayName("Login with wrong password fails with generic error")
    void login_withWrongPassword_fails() {
        LoginRequest request = new LoginRequest();
        request.setUsername("thorin");
        request.setPassword("WrongPassword");

        when(userRepository.findByUsername("thorin")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("WrongPassword", "hashed_password")).thenReturn(false);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> authService.login(request));

        assertEquals("Invalid username or password", ex.getMessage());
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("Refresh with a live token replaces it with a new 7-day token in the same family")
    void refresh_withLiveToken_rotatesWithinFamily() {
        RefreshToken current = storedToken("old-token", Instant.now().plus(Duration.ofDays(3)));
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("old-token")))
                .thenReturn(Optional.of(current));
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("thorin"))).thenReturn("new_access_token");
        stubRefreshTokenSave();

        Optional<AuthResult> result = authService.refresh("old-token");

        assertTrue(result.isPresent());
        assertEquals("new_access_token", result.get().response().getAccessToken());
        assertEquals(userId, result.get().response().getUserId());
        assertNotEquals("old-token", result.get().refreshToken());
        RefreshToken next = savedRefreshToken();
        assertEquals(AuthService.hash(result.get().refreshToken()), next.getTokenHash());
        assertEquals(current.getFamilyId(), next.getFamilyId());
        assertEquals(Duration.ofDays(7), Duration.between(next.getIssuedAt(), next.getExpiresAt()));
        assertEquals(next.getId(), current.getReplacedById());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("Refresh with a token that was already replaced revokes the whole family and returns empty")
    void refresh_withReplacedToken_revokesFamily() {
        RefreshToken replaced = storedToken("old-token", Instant.now().plus(Duration.ofDays(3)));
        replaced.setReplacedById(UUID.randomUUID());
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("old-token")))
                .thenReturn(Optional.of(replaced));

        Optional<AuthResult> result = authService.refresh("old-token");

        assertTrue(result.isEmpty());
        verify(refreshTokenRepository).revokeFamily(eq(replaced.getFamilyId()), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("Refresh with a revoked token returns empty without issuing anything")
    void refresh_withRevokedToken_returnsEmpty() {
        RefreshToken revoked = storedToken("old-token", Instant.now().plus(Duration.ofDays(3)));
        revoked.setRevokedAt(Instant.now().minusSeconds(60));
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("old-token")))
                .thenReturn(Optional.of(revoked));

        assertTrue(authService.refresh("old-token").isEmpty());

        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("Refresh with an expired token returns empty without issuing anything")
    void refresh_withExpiredToken_returnsEmpty() {
        RefreshToken expired = storedToken("old-token", Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("old-token")))
                .thenReturn(Optional.of(expired));

        assertTrue(authService.refresh("old-token").isEmpty());

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refresh with an unknown, blank or missing token returns empty")
    void refresh_withUnknownOrMissingToken_returnsEmpty() {
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("unknown"))).thenReturn(Optional.empty());

        assertTrue(authService.refresh("unknown").isEmpty());
        assertTrue(authService.refresh("  ").isEmpty());
        assertTrue(authService.refresh(null).isEmpty());

        verify(refreshTokenRepository, times(1)).findByTokenHashForUpdate(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Logout revokes the presented token's family")
    void logout_revokesFamily() {
        RefreshToken current = storedToken("token", Instant.now().plus(Duration.ofDays(3)));
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("token"))).thenReturn(Optional.of(current));

        authService.logout("token");

        verify(refreshTokenRepository).revokeFamily(eq(current.getFamilyId()), any(Instant.class));
    }

    @Test
    @DisplayName("Logout with an unknown or missing token does nothing")
    void logout_withUnknownOrMissingToken_doesNothing() {
        when(refreshTokenRepository.findByTokenHashForUpdate(AuthService.hash("unknown"))).thenReturn(Optional.empty());

        authService.logout("unknown");
        authService.logout(null);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("The stored hash is the SHA-256 of the token in lowercase hex")
    void hash_isSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AuthService.hash("abc"));
    }

    private RefreshToken storedToken(String value, Instant expiresAt) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(existingUser)
                .familyId(UUID.randomUUID())
                .tokenHash(AuthService.hash(value))
                .issuedAt(expiresAt.minus(Duration.ofDays(7)))
                .expiresAt(expiresAt)
                .build();
    }

    private void stubRefreshTokenSave() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken token = invocation.getArgument(0);
            token.setId(UUID.randomUUID());
            return token;
        });
    }

    private RefreshToken savedRefreshToken() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        return saved.getValue();
    }
}
