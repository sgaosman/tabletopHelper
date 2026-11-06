package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One feat as a character could take it (D158, D159), from {@code GET /api/characters/{id}/feat-options}.
 *
 * @param eligible     whether the character meets the feat's prerequisite now; a campaign or a requirement in words
 *                     never makes it ineligible, since only the DM can judge those
 * @param unmet        what the character doesn't meet, in words; empty when eligible
 * @param prerequisite the whole prerequisite in words, campaigns and requirements in words included; null for a feat
 *                     without one
 * @param taken        whether the character already has the feat
 * @param repeatable   whether the feat can be taken again, as Elemental Adept can (PHB p.166)
 */
public record FeatOptionResponse(
        @Schema(requiredMode = REQUIRED) UUID featId,
        @Schema(requiredMode = REQUIRED) String name,
        String source,
        @Schema(requiredMode = REQUIRED) boolean eligible,
        @Schema(requiredMode = REQUIRED) List<String> unmet,
        String prerequisite,
        @Schema(requiredMode = REQUIRED) boolean taken,
        @Schema(requiredMode = REQUIRED) boolean repeatable
) {
}
