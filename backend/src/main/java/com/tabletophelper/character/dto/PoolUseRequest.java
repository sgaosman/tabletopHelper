package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Uses of one of a character's resource pools to spend or recover on its sheet (D158). A pool can't spend uses it
 * doesn't have or recover past its maximum.
 *
 * @param poolId the pool, as the sheet names it, such as "class:monk-ki"
 * @param amount how many uses; 1 when left out
 */
public record PoolUseRequest(
        @NotBlank String poolId,
        @Min(1) Integer amount
) {
}
