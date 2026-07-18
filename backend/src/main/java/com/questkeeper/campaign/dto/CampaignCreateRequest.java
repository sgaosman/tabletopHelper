package com.questkeeper.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CampaignCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    private String description;
}
