package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The DM's record that a caster's lasting spell is already going (D184), such as Mage Armor cast before the fight or
 * Bless cast just before initiative: nothing is spent or rolled, and its concentration and Ongoing Modifiers start.
 */
@Data
public class ActiveSpellRequest {

    @NotNull
    private UUID casterId;

    @NotBlank
    @Size(max = 200)
    private String spellName;

    // The slot level it was cast with; the spell's own level when left out
    @Min(0)
    @Max(9)
    private Integer slotLevel;

    // The creatures it affects; left out or empty for a spell on its caster
    @Size(max = 20)
    private List<UUID> targetIds = new ArrayList<>();

    // The option it was cast with, for a spell that has options, such as "reduce"
    @Size(max = 50)
    private String option;
}
