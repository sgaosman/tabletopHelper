package com.questkeeper.campaign.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CampaignResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID dmUserId;
    private String dmDisplayName;
    private String inviteCode;
    private Boolean isActive;
    private List<MemberResponse> members;
    private Instant createdAt;

    @Data
    @Builder
    public static class MemberResponse {
        private UUID userId;
        private String username;
        private String displayName;
        private String role;
        private Instant joinedAt;
    }
}
