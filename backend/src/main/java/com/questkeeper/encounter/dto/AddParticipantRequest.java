package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AddParticipantRequest {

    @NotBlank
    private String participantType;

    private UUID characterId;

    private UUID monsterId;

    @NotBlank
    @Size(max = 200)
    private String displayName;

    private Integer initiativeModifier;

    private Integer hpMax;

    private Integer armourClass;

    private UUID controlledByUserId;

    private Integer quantity;
}
