package com.tabletophelper.character.dto;

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

    private UUID raceId;
    private UUID classId;
    private UUID subclassId;
    private UUID backgroundId;

    private String race;
    private String characterClass;
    private String subclass;

    @Min(1) @Max(20)
    private Integer level = 1;

    private String background;
    private String alignment;
    private String abilityScoreMethod;
    private String racialAbilityBonuses;

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

    private String savingThrowProficiencies;
    private String skillProficiencies;
    private String spellsKnown;
    private String spellSlots;
    private Integer spellSaveDc;
    private Integer spellAttackBonus;
    private String spellcastingAbility;
    private String features;
    private String damageResistances;
    private String equipment;
    private String currency;
    private String hitDiceMap;
    private String preparedSpells;

    private UUID campaignId;
}
