package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AttackRollRequest {

    @NotNull
    private UUID targetId;

    @NotNull
    private Integer attackBonus;

    @NotBlank
    private String damageDice;

    private String damageType;

    private Boolean advantage;
}
