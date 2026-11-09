package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A caster's bonus action commanding the creatures its Animate Dead (PHB p.212) or Tiny Servant (XGE p.168) made, any
 * or all of them at once, so its player may act for them on their next turns (D182).
 *
 * @param casterParticipantId the caster
 * @param participantIds      the creatures commanded
 */
public record CommandSummonsRequest(
        @Schema(requiredMode = REQUIRED) @NotNull UUID casterParticipantId,
        @Schema(requiredMode = REQUIRED) @NotEmpty @Size(max = 32) List<@NotNull UUID> participantIds) {
}
