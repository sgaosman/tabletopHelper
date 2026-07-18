package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SetHpRequest {

    @NotNull
    private UUID targetId;

    @NotNull
    private Integer hpCurrent;

    private Integer hpTemp;
}
