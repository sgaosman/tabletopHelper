package com.questkeeper.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CampaignJoinRequest {

    @NotBlank
    private String inviteCode;
}
