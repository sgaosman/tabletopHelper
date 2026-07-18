package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SpellSlotRequest {

    @NotNull
    private UUID participantId;

    @NotNull
    @Min(1)
    @Max(9)
    private Integer slotLevel;
}
