package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ConditionRequest {

    @NotNull
    private UUID targetId;

    @NotBlank
    private String condition;

    private Integer duration;
}
