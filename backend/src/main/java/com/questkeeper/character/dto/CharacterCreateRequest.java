package com.questkeeper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CharacterCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    private String race;
    private String characterClass;
    private String subclass;

    @Min(1) @Max(20)
    private Integer level = 1;

    private String background;
    private String alignment;

    @Min(1) @Max(30)
    private Integer strength;

    @Min(1) @Max(30)
    private Integer dexterity;

    @Min(1) @Max(30)
    private Integer constitution;

    @Min(1) @Max(30)
    private Integer intelligence;

    @Min(1) @Max(30)
    private Integer wisdom;

    @Min(1) @Max(30)
    private Integer charisma;

    @Min(1)
    private Integer hpMax;

    private Integer armourClass;

    private Integer initiativeBonus;
    private Integer speed;
    private Integer proficiencyBonus;

    private UUID campaignId;
}
