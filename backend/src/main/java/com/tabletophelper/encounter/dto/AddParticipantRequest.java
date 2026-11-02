package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.ParticipantType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AddParticipantRequest {

    @NotBlank
    @Schema(implementation = ParticipantType.class)
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
