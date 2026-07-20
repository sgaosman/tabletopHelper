package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CharacterUpdateRequest {

    @Size(max = 100)
    private String name;
    private String race;
    private String characterClass;
    private String subclass;
    @Min(1) @Max(20)
    private Integer level;
    @Min(0)
    private Integer experiencePoints;
    private String background;
    private String alignment;

    private UUID raceId;
    private UUID classId;
    private UUID subclassId;
    private UUID backgroundId;
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
    @Min(0)
    private Integer hpCurrent;
    @Min(0)
    private Integer hpTemp;
    private String hitDiceTotal;
    private String hitDiceRemaining;
    @Min(0)
    private Integer armourClass;
    private Integer initiativeBonus;
    @Min(0)
    private Integer speed;
    @Min(2) @Max(6)
    private Integer proficiencyBonus;

    private String savingThrowProficiencies;
    private String skillProficiencies;
    private String skillExpertises;
    private String armorProficiencies;
    private String weaponProficiencies;
    private String toolProficiencies;
    private String languageProficiencies;
    private String damageResistances;
    private String damageImmunities;
    private String conditionImmunities;

    private String features;
    private String spellsKnown;
    private String spellSlots;
    private Integer spellSaveDc;
    private Integer spellAttackBonus;
    private String spellcastingAbility;

    private String equipment;
    private String currency;

    private String personalityTraits;
    private String ideals;
    private String bonds;
    private String flaws;
    private String notes;

    private String portraitUrl;
    private UUID campaignId;
    private Boolean clearCampaign;

    private String multiclassEntries;
    private String attunedItems;
    private String equippedItems;
    private String hitDiceMap;
    private String levelHistory;
    private String featResources;
}
