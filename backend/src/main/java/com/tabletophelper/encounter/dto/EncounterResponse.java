package com.tabletophelper.encounter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.HealthStatus;
import com.tabletophelper.encounter.ParticipantType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The full encounter state, which the DM sees (the DM View). {@code EncounterViews} derives the
 * Player View from it (D157).
 */
@Data
@Builder(toBuilder = true)
public class EncounterResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    private UUID campaignId;

    @Schema(requiredMode = REQUIRED)
    private String campaignName;

    @Schema(requiredMode = REQUIRED)
    private String name;

    private String description;

    @Schema(requiredMode = REQUIRED, implementation = EncounterStatus.class)
    private String status;

    // The columns allow null, but a new encounter starts at turn 0 of round 1 and the turn order
    // only ever counts on from there
    @Schema(requiredMode = REQUIRED)
    private Integer currentTurnIndex;

    @Schema(requiredMode = REQUIRED)
    private Integer roundNumber;

    // Set when the encounter starts
    private String sessionCode;

    @Schema(requiredMode = REQUIRED)
    private List<ParticipantResponse> participants;

    // Null in the response that creates the encounter: @CreationTimestamp only fills it in when the
    // insert is flushed, after the response is built
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

        @Schema(requiredMode = REQUIRED)
        private UUID id;

        @Schema(requiredMode = REQUIRED, implementation = ParticipantType.class)
        private String participantType;

        private UUID characterId;
        private UUID monsterId;

        @Schema(requiredMode = REQUIRED)
        private String displayName;

        private Integer initiative;
        private Integer initiativeModifier;
        private Integer sortOrder;

        // Null in the Player View for a monster whose HP it hides behind healthStatus (D157)
        private Integer hpMax;
        private Integer hpCurrent;
        private Integer hpTemp;

        // Set only in the Player View, for a monster whose HP it hides (D157)
        @Schema(implementation = HealthStatus.class)
        private String healthStatus;

        @Schema(requiredMode = REQUIRED)
        private Integer armourClass;

        private String activeConditions;
        private String concentrationSpell;
        private Integer concentrationSlotLevel;
        private String activeSpell;
        private Integer activeSpellSlotLevel;
        private String spellSlotsCurrent;

        // Null counts as visible (D157)
        private Boolean isVisibleToPlayers;

        // The columns allow null, but participants are only created through the entity's builder,
        // which starts each of these, and combat code reads them without a null check
        @Schema(requiredMode = REQUIRED)
        private Boolean isAlive;

        @Schema(requiredMode = REQUIRED)
        private Boolean isCurrentTurn;

        private UUID controlledByUserId;

        // As isAlive
        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveSuccesses;

        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveFailures;

        private String notes;
        private Integer spellAttackBonus;
        private Integer spellSaveDc;
        private String spellcastingAbility;
        private String spellsKnown;
        private String resourcePoolsCurrent;

        // V8 added these columns with a default of false, which the entity's builder matches
        @Schema(requiredMode = REQUIRED)
        private Boolean actionUsed;

        @Schema(requiredMode = REQUIRED)
        private Boolean bonusActionUsed;

        @Schema(requiredMode = REQUIRED)
        private Boolean reactionUsed;
    }
}
