package com.tabletophelper.identity;

import com.tabletophelper.identity.internal.UserRepository;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private UserService userService;

    @Test
    @DisplayName("getUser returns the user with the given ID")
    void getUserReturnsTheUser() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).username("aria").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertSame(user, userService.getUser(userId));
    }

    @Test
    @DisplayName("getUser throws NotFoundException for an ID that matches no user")
    void getUserThrowsNotFoundForAnUnknownId() {
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        var thrown = assertThrows(NotFoundException.class, () -> userService.getUser(userId));
        assertEquals("User not found", thrown.getMessage());
    }
}
