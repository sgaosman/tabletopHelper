package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ConditionRequest {

    @NotNull
    private UUID targetId;

    @NotBlank
    private String condition;

    private Integer duration;

    // On adding frightened, the participant the creature is frightened of, so its disadvantage applies only while that
    // source is in sight (PHB p.290, D201); null for none recorded. Refused for any other condition
    private UUID sourceParticipantId;

    // On removing, the one condition entry to remove, so a creature frightened by two sources keeps the other's; it
    // must be a condition of the name given. Null removes every condition of the name, as older clients ask. Ignored
    // on adding
    private UUID conditionId;
}
