package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Moves a caster's Hunter's Mark or Hex to a new creature with a bonus action, once the creature it was on has dropped
 * to 0 hit points (PHB p.251, D184).
 */
@Data
public class MoveSpellEffectRequest {

    // The caster
    @NotNull
    private UUID participantId;

    @NotNull
    private UUID effectId;

    @NotNull
    private UUID targetId;
}
