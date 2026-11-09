package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The DM sets a participant's exhaustion level, 0 to 6 (PHB p.291, D183), while the encounter is prepared or under
 * way: a creature that arrives exhausted from something outside the encounter, or an effect that gives or removes
 * exhaustion during it.
 */
public record ExhaustionRequest(
        @NotNull UUID participantId,
        @NotNull @Min(0) @Max(6) Integer level
) {
}
