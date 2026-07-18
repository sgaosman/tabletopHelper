package com.questkeeper.encounter.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EncounterResponse {

    private UUID id;
    private UUID campaignId;
    private String campaignName;
    private String name;
    private String description;
    private String status;
    private Integer currentTurnIndex;
    private Integer roundNumber;
    private String sessionCode;
    private List<ParticipantResponse> participants;
    private Instant createdAt;

    @Data
    @Builder
    public static class ParticipantResponse {
        private UUID id;
        private String participantType;
        private UUID characterId;
        private UUID monsterId;
        private String displayName;
        private Integer initiative;
        private Integer initiativeModifier;
        private Integer sortOrder;
        private Integer hpMax;
        private Integer hpCurrent;
        private Integer hpTemp;
        private Integer armourClass;
        private String activeConditions;
        private String concentrationSpell;
        private Boolean isVisibleToPlayers;
        private Boolean isAlive;
        private Boolean isCurrentTurn;
        private UUID controlledByUserId;
        private Integer deathSaveSuccesses;
        private Integer deathSaveFailures;
        private String notes;
    }
}
