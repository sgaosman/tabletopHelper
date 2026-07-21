package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RepeatSpellEffectRequest {

    @NotNull
    private List<UUID> targetIds;

    private Boolean advantage;

    private Integer overrideSpellAttackBonus;

    private Integer overrideSpellSaveDC;
}
