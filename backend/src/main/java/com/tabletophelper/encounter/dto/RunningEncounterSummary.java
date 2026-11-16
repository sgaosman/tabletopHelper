package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.EncounterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * An encounter under way (Active or Paused) in a campaign the caller runs, for the DM's dashboard (D236). It carries
 * only what the dashboard lists, not the encounter's participants.
 *
 * @param id               the encounter
 * @param name             the encounter's name
 * @param status           ACTIVE or PAUSED
 * @param roundNumber      the round it is on
 * @param campaignId       its campaign
 * @param campaignName     its campaign's name
 * @param participantCount how many participants it has, monsters and summons included
 * @param updatedAt        when the encounter row last changed
 */
public record RunningEncounterSummary(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) EncounterStatus status,
        Integer roundNumber,
        @Schema(requiredMode = REQUIRED) UUID campaignId,
        @Schema(requiredMode = REQUIRED) String campaignName,
        @Schema(requiredMode = REQUIRED) long participantCount,
        Instant updatedAt) {
}
