package com.tabletophelper.campaign.dto;

import com.tabletophelper.identity.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder
public class CampaignResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    private String name;

    private String description;

    @Schema(requiredMode = REQUIRED)
    private UUID dmUserId;

    // The column allows null, but registration has always fallen back to the username
    @Schema(requiredMode = REQUIRED)
    private String dmDisplayName;

    @Schema(requiredMode = REQUIRED)
    private String inviteCode;

    @Schema(requiredMode = REQUIRED)
    private Boolean isActive;

    @Schema(requiredMode = REQUIRED,
            description = "Off: players see each monster's HP as a Health Status instead of numbers (D157)")
    private Boolean showExactMonsterHp;

    @Schema(requiredMode = REQUIRED,
            description = "On: players roll their characters' Repeat Saves when prompted; off: the server rolls them (D162)")
    private Boolean promptPlayerRepeatSaves;

    @Schema(requiredMode = REQUIRED,
            description = "On: players may enter Manual Modifiers in combat, as the DM always may (D164)")
    private Boolean allowPlayerManualModifiers;

    @Schema(requiredMode = REQUIRED,
            description = "On: the DM chooses Conjure Animals' creatures; off: the caster does (PHB p.225, D182)")
    private Boolean dmChoosesConjuredCreatures;

    @Schema(requiredMode = REQUIRED)
    private List<MemberResponse> members;

    // Null in the response that creates the campaign: @CreationTimestamp only fills it in when the
    // insert is flushed, after the response is built
    private Instant createdAt;

    @Data
    @Builder
    public static class MemberResponse {

        @Schema(requiredMode = REQUIRED)
        private UUID userId;

        @Schema(requiredMode = REQUIRED)
        private String username;

        @Schema(requiredMode = REQUIRED)
        private String displayName;

        @Schema(requiredMode = REQUIRED, implementation = UserRole.class)
        private String role;

        // Null for the member that a create or join request adds, for the same reason as createdAt
        private Instant joinedAt;
    }
}
