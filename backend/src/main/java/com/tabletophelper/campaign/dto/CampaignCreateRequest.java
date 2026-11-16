package com.tabletophelper.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CampaignCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    // Generous, but bounded, as every free text a request stores is (D221)
    @Size(max = 10_000)
    private String description;
}
