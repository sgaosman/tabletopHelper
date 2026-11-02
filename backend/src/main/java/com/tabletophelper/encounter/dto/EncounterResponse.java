package com.tabletophelper.encounter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full encounter state, which the DM sees (the DM View). {@code EncounterViews} derives the
 * Player View from it (D157).
 */
@Data
@Builder(toBuilder = true)
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

    // The two campaign facts that decide which view a user gets. They stay on the server, so
    // choosing a view needs no further lookup (D157).
    @JsonIgnore
    private UUID dmUserId;
    @JsonIgnore
    private boolean showExactMonsterHp;

    @Data
    @Builder(toBuilder = true)
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
        // Set only in the Player View, for a monster whose HP it hides (D157)
        private String healthStatus;
        private Integer armourClass;
        private String activeConditions;
        private String concentrationSpell;
        private Integer concentrationSlotLevel;
        private String activeSpell;
        private Integer activeSpellSlotLevel;
        private String spellSlotsCurrent;
        private Boolean isVisibleToPlayers;
        private Boolean isAlive;
        private Boolean isCurrentTurn;
        private UUID controlledByUserId;
        private Integer deathSaveSuccesses;
        private Integer deathSaveFailures;
        private String notes;
        private Integer spellAttackBonus;
        private Integer spellSaveDc;
        private String spellcastingAbility;
        private String spellsKnown;
        private String resourcePoolsCurrent;
        private Boolean actionUsed;
        private Boolean bonusActionUsed;
        private Boolean reactionUsed;
    }
}
