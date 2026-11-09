package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Deft Strike on a Way of the Kensei monk's latest hit with a kensei weapon (XGE p.34), on the same turn, which must be
 * the monk's own: 1 ki point for extra damage equal to its Martial Arts die, once on each of its turns.
 *
 * @param participantId the monk
 * @param attackLogId   the hit's combat log entry, as the monk's turn economy names it; when given, any other hit is
 *                      refused
 */
public record DeftStrikeRequest(
        @NotNull UUID participantId,
        UUID attackLogId
) {
}
