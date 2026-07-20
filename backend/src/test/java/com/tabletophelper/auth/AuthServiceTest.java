package com.tabletophelper.auth;

import com.tabletophelper.auth.dto.AuthResponse;
import com.tabletophelper.auth.dto.LoginRequest;
import com.tabletophelper.auth.dto.RegisterRequest;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    @DisplayName("Register creates user and returns access and refresh tokens")
    void register_createsUserAndReturnsTokens() {
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
        when(jwtTokenProvider.generateRefreshToken(eq(userId), eq("thorin"))).thenReturn("refresh_token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals(userId, response.getUserId());
        assertEquals("thorin", response.getUsername());
        assertEquals("access_token", response.getAccessToken());
        assertEquals("refresh_token", response.getRefreshToken());
        verify(passwordEncoder).encode("Mithril123!");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Register fails when username is already taken")
    void register_failsOnDuplicateUsername() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("thorin");
        request.setEmail("new@email.com");
        request.setPassword("Mithril123!");

        when(userRepository.existsByUsername("thorin")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register(request));

        assertEquals("Username already taken", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Login with correct credentials returns tokens")
    void login_withCorrectCredentials_returnsTokens() {
        LoginRequest request = new LoginRequest();
        request.setUsername("thorin");
        request.setPassword("Mithril123!");

        when(userRepository.findByUsername("thorin")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("Mithril123!", "hashed_password")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("thorin"))).thenReturn("access_token");
        when(jwtTokenProvider.generateRefreshToken(eq(userId), eq("thorin"))).thenReturn("refresh_token");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals(userId, response.getUserId());
        assertEquals("access_token", response.getAccessToken());
        assertEquals("refresh_token", response.getRefreshToken());
    }

    @Test
    @DisplayName("Login with wrong password fails with generic error")
    void login_withWrongPassword_fails() {
        LoginRequest request = new LoginRequest();
        request.setUsername("thorin");
        request.setPassword("WrongPassword");

        when(userRepository.findByUsername("thorin")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("WrongPassword", "hashed_password")).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.login(request));

        assertEquals("Invalid username or password", ex.getMessage());
    }

    @Test
    @DisplayName("Refresh with valid token returns new tokens")
    void refresh_withValidToken_returnsNewTokens() {
        String refreshToken = "valid_refresh_token";

        when(jwtTokenProvider.validateToken(refreshToken)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(refreshToken)).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("thorin"))).thenReturn("new_access_token");
        when(jwtTokenProvider.generateRefreshToken(eq(userId), eq("thorin"))).thenReturn("new_refresh_token");

        AuthResponse response = authService.refresh(refreshToken);

        assertNotNull(response);
        assertEquals("new_access_token", response.getAccessToken());
        assertEquals("new_refresh_token", response.getRefreshToken());
        assertEquals(userId, response.getUserId());
    }

    @Test
    @DisplayName("Refresh with expired token fails")
    void refresh_withExpiredToken_fails() {
        String expiredToken = "expired_refresh_token";

        when(jwtTokenProvider.validateToken(expiredToken)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.refresh(expiredToken));

        assertEquals("Invalid or expired refresh token", ex.getMessage());
    }
}
