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
 * @param option        what the feature does, where it does more than one thing: "end" to end a Rage or a Wild Shape;
 *                      "heal" (the default) or "cure" for Lay on Hands; "patient-defense", "step-of-the-wind-dash" or
 *                      "step-of-the-wind-disengage" for Ki; the Channel Divinity option, such as "turn-undead" or
 *                      "preserve-life" (left out, Channel Divinity only spends)
 * @param targetIds     the creatures a feature that reaches others is used on: Lay on Hands' and Bardic Inspiration's
 *                      one creature (Lay on Hands' the user itself when left out), or those a Channel Divinity option
 *                      affects, such as the undead Turn Undead is used on
 * @param amounts       Preserve Life's hit points for each of targetIds, in the same order; only for Preserve Life
 * @param beastId       the beast Wild Shape turns the druid into, a monster from the wild-shape options, or for a
 *                      Circle of the Moon druid of 10th level one of the elementals Elemental Wild Shape offers, which
 *                      spends two uses (PHB p.69); only for Wild Shape without an option
 * @param weapon        the weapon Sacred Weapon imbues (PHB p.86), as the attack options name it, such as "Longsword";
 *                      left out, the paladin's only weapon. Only for Sacred Weapon
 */
public record UsePoolRequest(
        @NotNull UUID participantId,
        @NotBlank String poolId,
        @Min(1) @Max(100) Integer uses,
        String option,
        @Size(max = 20) List<UUID> targetIds,
        @Size(max = 20) List<@NotNull @Min(1) Integer> amounts,
        UUID beastId,
        @Size(max = 200) String weapon
) {

    /** Uses of a pool naming no weapon. */
    public UsePoolRequest(UUID participantId, String poolId, Integer uses, String option, List<UUID> targetIds,
                          List<Integer> amounts, UUID beastId) {
        this(participantId, poolId, uses, option, targetIds, amounts, beastId, null);
    }

    /** Uses of a pool with no option and no target. */
    public UsePoolRequest(UUID participantId, String poolId, Integer uses) {
        this(participantId, poolId, uses, null, null, null, null);
    }

    /** Uses of a pool with an option and targets, and no amounts. */
    public UsePoolRequest(UUID participantId, String poolId, Integer uses, String option, List<UUID> targetIds) {
        this(participantId, poolId, uses, option, targetIds, null, null);
    }

    /** Uses of a pool with an option, targets and amounts, and no beast. */
    public UsePoolRequest(UUID participantId, String poolId, Integer uses, String option, List<UUID> targetIds,
                          List<Integer> amounts) {
        this(participantId, poolId, uses, option, targetIds, amounts, null);
    }

    /** Wild Shape into the beast. */
    public static UsePoolRequest wildShape(UUID participantId, String poolId, UUID beastId) {
        return new UsePoolRequest(participantId, poolId, 1, null, null, null, beastId);
    }
}
