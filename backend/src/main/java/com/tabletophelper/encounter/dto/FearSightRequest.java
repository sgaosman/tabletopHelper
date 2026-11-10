package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The DM marks the source of a creature's fear out of its line of sight, or back in sight (PHB p.290, D201): while out
 * of sight, the frightened condition gives no disadvantage on the creature's attack rolls and ability checks.
 *
 * @param participantId the frightened creature
 * @param conditionId   its frightened entry
 * @param outOfSight    true to mark the source out of sight, false to put it back in sight
 */
public record FearSightRequest(
        @NotNull UUID participantId,
        @NotNull UUID conditionId,
        @NotNull Boolean outOfSight
) {
}
