package com.questkeeper.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {

    private UUID userId;
    private String username;
    private String displayName;
    private String accessToken;
    private String refreshToken;
}
