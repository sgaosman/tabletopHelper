package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ConcentrationRequest {

    @NotNull
    private UUID participantId;

    private String spellName;
}
