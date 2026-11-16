package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Uses of one of a character's resource pools to spend or recover on its sheet (D158). A pool can't spend uses it
 * doesn't have or recover past its maximum.
 *
 * @param poolId the pool, as the sheet names it, such as "class:monk-ki"
 * @param amount how many uses; 1 when left out, and at most 1,000, past the largest pool (D221)
 */
public record PoolUseRequest(
        @NotBlank @Size(max = 200) String poolId,
        @Min(1) @Max(1000) Integer amount
) {
}
