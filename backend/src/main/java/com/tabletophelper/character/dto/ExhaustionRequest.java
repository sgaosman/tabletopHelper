package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A character's exhaustion level as its owner sets it on the sheet, 0 to 6 (PHB p.291, D183): 0 is none, and 6 is
 * death.
 */
public record ExhaustionRequest(
        @NotNull @Min(0) @Max(6) Integer level
) {
}
