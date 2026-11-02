package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record MonsterActionResponse(
        @Schema(requiredMode = REQUIRED) boolean resolved,
        @Schema(requiredMode = REQUIRED) String description,
        @Schema(requiredMode = REQUIRED) int totalDamage,
        @Schema(requiredMode = REQUIRED) int totalHealing,
        @Schema(requiredMode = REQUIRED) List<TargetResult> targetResults,
        @Schema(requiredMode = REQUIRED) boolean requiresManualResolution,
        String manualResolutionReason,
        @Schema(requiredMode = REQUIRED) List<String> conditionsInflicted,
        @Schema(requiredMode = REQUIRED) boolean legendaryResistanceAvailable,
        @Schema(requiredMode = REQUIRED) int legendaryResistanceRemaining,
        @Schema(requiredMode = REQUIRED) EncounterResponse encounterState
) {

    public MonsterActionResponse withEncounterState(EncounterResponse state) {
        return new MonsterActionResponse(resolved, description, totalDamage, totalHealing, targetResults,
                requiresManualResolution, manualResolutionReason, conditionsInflicted,
                legendaryResistanceAvailable, legendaryResistanceRemaining, state);
    }

    /**
     * One target's result. damage is what it took after its defences; savedSuccessfully is set when it made its
     * save, the one a hit calls for against its conditions included; legendaryResistanceAvailable is set on a
     * failed save by a creature with Legendary Resistance left (D163).
     */
    public record TargetResult(
            @Schema(requiredMode = REQUIRED) UUID targetId,
            @Schema(requiredMode = REQUIRED) String targetName,
            @Schema(requiredMode = REQUIRED) int damage,
            @Schema(requiredMode = REQUIRED) int healing,
            @Schema(requiredMode = REQUIRED) boolean savedSuccessfully,
            @Schema(requiredMode = REQUIRED) List<String> conditionsApplied,
            @Schema(requiredMode = REQUIRED) String attackOutcome,
            Integer rollValue,
            Integer rollTotal,
            String damageType,
            @Schema(requiredMode = REQUIRED) boolean legendaryResistanceAvailable,
            @Schema(requiredMode = REQUIRED) int legendaryResistanceRemaining
    ) {
    }
}
