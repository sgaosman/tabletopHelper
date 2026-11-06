package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Uses of one of a participant's resource pools spent at the table (D174), costing what the pool's
 * {@code spendActionType} says from the participant's turn (D125).
 *
 * @param participantId whose pool it is
 * @param poolId        the pool, as the participant names it, such as "class:barbarian-rage"
 * @param uses          how many uses; 1 when left out
 */
public record UsePoolRequest(
        @NotNull UUID participantId,
        @NotBlank String poolId,
        @Min(1) @Max(20) Integer uses
) {
}
