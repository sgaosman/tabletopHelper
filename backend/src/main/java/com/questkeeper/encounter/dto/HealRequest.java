package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class HealRequest {

    @NotNull
    private UUID targetId;

    @NotNull
    @Min(1)
    private Integer amount;
}
