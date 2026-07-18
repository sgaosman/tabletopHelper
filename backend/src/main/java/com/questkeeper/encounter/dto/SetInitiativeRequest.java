package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SetInitiativeRequest {

    @NotNull
    private UUID participantId;

    @NotNull
    private Integer initiative;
}
