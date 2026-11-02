package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.CombatActionType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder(toBuilder = true)
public class CombatLogResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID id;

    // The column allows null, but every entry records the encounter's round, which is never null
    @Schema(requiredMode = REQUIRED)
    private Integer roundNumber;

    private UUID actorId;
    private String actorName;
    private UUID targetId;
    private String targetName;

    @Schema(requiredMode = REQUIRED, implementation = CombatActionType.class)
    private String actionType;

    @Schema(requiredMode = REQUIRED)
    private String description;

    private Integer rollValue;
    private Integer rollTotal;
    private Integer damageDealt;
    private Integer healingDone;
    private String turnParticipantName;
    private Boolean legendaryResistanceEligible;
    private UUID lrTargetId;
    private Integer lrDamageDealt;
    private String lrConditionsApplied;
    private Boolean lrResolved;

    // The log is only read back after its entries are inserted, when @CreationTimestamp has filled it in
    @Schema(requiredMode = REQUIRED)
    private Instant createdAt;
}
