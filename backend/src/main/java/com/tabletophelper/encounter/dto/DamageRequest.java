package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class DamageRequest {

    @NotNull
    private UUID targetId;

    @NotNull
    @Min(0)
    private Integer amount;

    private String damageType;
}
