package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Ends one of a participant's Spell Effects by hand, and with it the concentration it belongs to (M15). */
@Data
public class EndSpellEffectRequest {

    @NotNull
    private UUID participantId;

    @NotNull
    private UUID effectId;
}
