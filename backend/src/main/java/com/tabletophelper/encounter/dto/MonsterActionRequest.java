package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record MonsterActionRequest(
        @Schema(requiredMode = REQUIRED) UUID monsterParticipantId,
        @Schema(requiredMode = REQUIRED) String actionName,
        @Schema(requiredMode = REQUIRED, allowableValues = {"ACTION", "LEGENDARY", "LAIR"}) String actionSource,
        @Schema(requiredMode = REQUIRED) List<UUID> targetParticipantIds,
        Integer overrideAttackBonus,
        Integer overrideSaveDC,
        Boolean advantage
) {
}
