package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CastSpellRequest {

    @NotBlank
    private String spellName;

    @NotNull
    @Min(0)
    @Max(9)
    private Integer slotLevel;

    @NotNull
    private List<UUID> targetIds;

    private Boolean advantage;

    private Boolean usePactSlot;

    private Integer overrideSpellAttackBonus;

    private Integer overrideSpellSaveDC;
}
