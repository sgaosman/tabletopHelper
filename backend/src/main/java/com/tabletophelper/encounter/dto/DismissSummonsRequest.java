package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The DM dismisses summoned creatures, which disappear while their spell goes on (D182).
 *
 * @param participantIds the creatures
 */
public record DismissSummonsRequest(
        @Schema(requiredMode = REQUIRED) @NotEmpty @Size(max = 32) List<@NotNull UUID> participantIds) {
}
