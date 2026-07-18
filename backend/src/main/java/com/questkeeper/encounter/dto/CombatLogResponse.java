package com.questkeeper.encounter.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CombatLogResponse {

    private UUID id;
    private Integer roundNumber;
    private UUID actorId;
    private String actorName;
    private UUID targetId;
    private String targetName;
    private String actionType;
    private String description;
    private Integer rollValue;
    private Integer rollTotal;
    private Integer damageDealt;
    private Integer healingDone;
    private Instant createdAt;
}
