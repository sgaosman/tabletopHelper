package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Uses of one of a participant's resource pools spent at the table (D174), costing what the pool's
 * {@code spendActionType} says from the participant's turn (D125). A pool whose metadata names an {@code effect} also
 * does what its feature does (M17), which some features let the user steer with an option or a target.
 *
 * @param participantId whose pool it is
 * @param poolId        the pool, as the participant names it, such as "class:barbarian-rage"
 * @param uses          how many uses; 1 when left out. For Lay on Hands, the hit points spent from the pool
 * @param option        what the feature does, where it does more than one thing: "end" to end a Rage; "heal" (the
 *                      default) or "cure" for Lay on Hands; "patient-defense", "step-of-the-wind-dash" or
 *                      "step-of-the-wind-disengage" for Ki
 * @param targetIds     the creature a feature that touches another is used on: Lay on Hands' one creature, the user
 *                      itself when left out
 */
public record UsePoolRequest(
        @NotNull UUID participantId,
        @NotBlank String poolId,
        @Min(1) @Max(100) Integer uses,
        String option,
        @Size(max = 1) List<UUID> targetIds
) {

    /** Uses of a pool with no option and no target. */
    public UsePoolRequest(UUID participantId, String poolId, Integer uses) {
        this(participantId, poolId, uses, null, null);
    }
}
