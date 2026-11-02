package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record MonsterSpellRequest(
        @Schema(requiredMode = REQUIRED) UUID monsterParticipantId,
        @Schema(requiredMode = REQUIRED) String spellName,
        // Left out, it is 0, which spends no spell slot
        int slotLevel,
        @Schema(requiredMode = REQUIRED) List<UUID> targetParticipantIds,
        Integer overrideAttackBonus,
        Integer overrideSaveDC,
        Boolean advantage
) {
}
