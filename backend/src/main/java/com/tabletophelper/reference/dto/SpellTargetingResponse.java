package com.tabletophelper.reference.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Who a spell can target, read from its effect template.
 *
 * @param maxTargets -1 when the template sets no target count, such as for an area spell
 * @param targetType SINGLE_TARGET when the template sets none
 */
public record SpellTargetingResponse(
        @Schema(requiredMode = REQUIRED) int maxTargets,
        @Schema(requiredMode = REQUIRED) boolean selfOnly,
        @Schema(requiredMode = REQUIRED) boolean canTargetSelf,
        @Schema(requiredMode = REQUIRED) boolean canTargetAllies,
        @Schema(requiredMode = REQUIRED) boolean canTargetEnemies,
        @Schema(requiredMode = REQUIRED) String targetType,
        @Schema(requiredMode = REQUIRED) boolean hasRepeatEffect) {}
