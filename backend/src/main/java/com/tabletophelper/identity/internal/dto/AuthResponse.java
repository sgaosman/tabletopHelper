package com.tabletophelper.identity.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID userId;

    @Schema(requiredMode = REQUIRED)
    private String username;

    // The column allows null, but registration has always fallen back to the username
    @Schema(requiredMode = REQUIRED)
    private String displayName;

    @Schema(requiredMode = REQUIRED)
    private String accessToken;
}
