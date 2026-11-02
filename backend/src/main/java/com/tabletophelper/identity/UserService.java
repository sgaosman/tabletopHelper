package com.tabletophelper.identity;

import com.tabletophelper.identity.internal.UserRepository;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * How other modules look users up, so that only identity uses its repository (D151).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** The user with this ID. A user that does not exist is a {@link NotFoundException}. */
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
