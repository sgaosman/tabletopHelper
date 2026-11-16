package com.tabletophelper.encounter.dto;

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

    // Generous, but bounded, as every free text a request stores is (D221)
    @Size(max = 10_000)
    private String description;
}
