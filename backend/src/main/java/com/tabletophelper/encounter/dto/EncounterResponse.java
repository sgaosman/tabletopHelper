package com.tabletophelper.encounter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.HealthStatus;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.rules.combat.LifeState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    // The current turn's place in turnOrder, computed for each view (D157, D163): null before the
    // encounter starts, and in the Player View while a participant hidden from players acts
    private Integer currentTurnIndex;

    @Schema(requiredMode = REQUIRED)
    private Integer roundNumber;

    // Set when the encounter starts
    private String sessionCode;

    // In turn order
    @Schema(requiredMode = REQUIRED)
    private List<ParticipantResponse> participants;

    // Every turn in order, the Lair's included while it has one (D163). The Player View leaves out the
    // turns of participants hidden from players, and the Lair's unless they can see a monster in its lair.
    @Schema(requiredMode = REQUIRED)
    private List<TurnResponse> turnOrder;

    // The monster participants fighting in their lair, which gives the Lair a turn while any is alive
    @Schema(requiredMode = REQUIRED)
    private List<UUID> monstersInLair;

    // Null in the response that creates the encounter: @CreationTimestamp only fills it in when the
    // insert is flushed, after the response is built
    private Instant createdAt;

    // The two campaign facts that decide which view a user gets. They stay on the server, so
    // choosing a view needs no further lookup (D157).
    @JsonIgnore
    private UUID dmUserId;
    @JsonIgnore
    private boolean showExactMonsterHp;

    // The campaign's setting, which the session pages read to offer players Manual Modifiers (D164)
    @Schema(requiredMode = REQUIRED)
    private boolean allowPlayerManualModifiers;

    @Data
    @Builder(toBuilder = true)
    public static class ParticipantResponse {

        @Schema(requiredMode = REQUIRED)
        private UUID id;

        @Schema(requiredMode = REQUIRED, implementation = ParticipantType.class)
        private String participantType;

        private UUID characterId;
        private UUID monsterId;

        // A player character's class as its sheet names it, which picks its class colour; null for a monster.
        // It comes from the sheet, so no request sets it
        private String characterClass;

        @Schema(requiredMode = REQUIRED)
        private String displayName;

        private Integer initiative;
        private Integer initiativeModifier;

        // The participant's turn's place in turnOrder, counted again over the turns the view shows
        @Schema(requiredMode = REQUIRED)
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

        private List<ConditionEntry> activeConditions;

        // The spell and slot level of the Concentration Instance it holds, if any (D162)
        private String concentrationSpell;
        private Integer concentrationSlotLevel;
        private String activeSpell;
        private Integer activeSpellSlotLevel;

        // Keyed "1" to "9" by spell level, and "pact_N" for pact slots (D168)
        private Map<String, SpellSlot> spellSlotsCurrent;

        // Null counts as visible (D157)
        private Boolean isVisibleToPlayers;

        // Alive, Dying, Stable or Dead (D161); the column is NOT NULL
        @Schema(requiredMode = REQUIRED, implementation = LifeState.class)
        private String lifeState;

        // Computed from the encounter's current turn (D163)
        @Schema(requiredMode = REQUIRED)
        private Boolean isCurrentTurn;

        // A Dying PC whose turn has started and who hasn't yet rolled the death save it is due (D163)
        @Schema(requiredMode = REQUIRED)
        private Boolean deathSaveDue;

        // DM View only: whether a monster's action templates have lair actions, so it can fight in its lair
        private Boolean hasLairActions;

        private UUID controlledByUserId;

        // The columns allow null, but participants are only created through the entity's builder,
        // which starts each of these, and combat code reads them without a null check
        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveSuccesses;

        @Schema(requiredMode = REQUIRED)
        private Integer deathSaveFailures;

        private String notes;
        private Integer spellAttackBonus;
        private Integer spellSaveDc;
        private String spellcastingAbility;
        private List<KnownSpell> spellsKnown;
        private List<ResourcePoolEntry> resourcePoolsCurrent;

        // What it has spent of its turn (D174); null in the Player View for a monster (D157)
        private TurnEconomy turnEconomy;

        // The attacks one Attack action gives it (Extra Attack); null in the Player View for a monster
        private Integer attacksPerAction;

        // Its speeds in feet by kind ("walk", "fly", "swim", "climb", "burrow"), walking first; empty when unknown
        @Schema(requiredMode = REQUIRED)
        private Map<String, Integer> speed;
    }

    /** One turn in the order: a participant's, or the Lair's (D163). */
    @Data
    @Builder(toBuilder = true)
    public static class TurnResponse {

        // The participant whose turn it is; null for the Lair's
        private UUID participantId;

        // The participant's name, or "Lair Actions"
        @Schema(requiredMode = REQUIRED)
        private String name;

        // 20 for the Lair; null for a participant without initiative
        private Integer initiative;

        @Schema(requiredMode = REQUIRED)
        private boolean lair;

        // A dead monster's turn, which the order passes over
        @Schema(requiredMode = REQUIRED)
        private boolean skipped;

        @Schema(requiredMode = REQUIRED)
        private boolean current;

        // DM View only: the same number on each turn of a group tied on initiative and DEX, which the DM
        // may reorder; null for a turn tied with no other
        private Integer tieGroup;
    }
}
