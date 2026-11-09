package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Stunning Strike on the monk's latest melee weapon hit (PHB p.79, M17), on the same turn: 1 ki point for a
 * Constitution save by the creature hit.
 *
 * @param participantId the monk
 * @param attackLogId   the hit's combat log entry, as the monk's turn economy names it; when given, any other hit is
 *                      refused
 */
public record StunningStrikeRequest(
        @NotNull UUID participantId,
        UUID attackLogId
) {
}
