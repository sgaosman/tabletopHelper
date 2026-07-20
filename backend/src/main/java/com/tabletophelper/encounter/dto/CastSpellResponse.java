package com.tabletophelper.encounter.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CastSpellResponse {

    private EncounterResponse encounterState;
    private String spellName;
    private int slotLevelUsed;
    private boolean autoResolved;
    private String resultSummary;
    private List<TargetOutcome> targets;
    private String manualResolutionReason;

    @Data
    @Builder
    public static class TargetOutcome {
        private UUID targetId;
        private String targetName;
        private String outcome;
        private Integer damage;
        private Integer healing;
        private List<String> conditionsApplied;
        private Integer attackRoll;
        private Integer saveRoll;
    }
}
