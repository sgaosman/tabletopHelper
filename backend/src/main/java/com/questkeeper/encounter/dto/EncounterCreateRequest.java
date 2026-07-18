package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class EncounterCreateRequest {

    @NotNull
    private UUID campaignId;

    @NotBlank
    @Size(max = 200)
    private String name;

    private String description;
}
