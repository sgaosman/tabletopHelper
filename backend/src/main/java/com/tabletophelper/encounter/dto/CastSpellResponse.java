package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.state.SpellSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder(toBuilder = true)
public class CastSpellResponse {

    @Schema(requiredMode = REQUIRED)
    private EncounterResponse encounterState;

    // Every cast names a spell the server has found
    @Schema(requiredMode = REQUIRED)
    private String spellName;

    // The level it was cast at: the slot's, the pact slots', or the spell's own (D165)
    @Schema(requiredMode = REQUIRED)
    private int slotLevelUsed;

    // What paid for it; a repeated effect pays nothing
    @Schema(requiredMode = REQUIRED)
    private SpellSource spellSource;

    @Schema(requiredMode = REQUIRED)
    private boolean autoResolved;

    @Schema(requiredMode = REQUIRED)
    private String resultSummary;

    @Schema(requiredMode = REQUIRED)
    private List<TargetOutcome> targets;

    private String manualResolutionReason;

    @Data
    @Builder
    public static class TargetOutcome {

        @Schema(requiredMode = REQUIRED)
        private UUID targetId;

        @Schema(requiredMode = REQUIRED)
        private String targetName;

        @Schema(requiredMode = REQUIRED)
        private String outcome;

        private Integer damage;
        private Integer healing;

        @Schema(requiredMode = REQUIRED)
        private List<String> conditionsApplied;

        private Integer attackRoll;
        private Integer saveRoll;
    }
}
