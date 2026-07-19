package com.tabletophelper.character.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class CharacterUpdateRequest {

    private String name;
    private String race;
    private String characterClass;
    private String subclass;
    private Integer level;
    private Integer experiencePoints;
    private String background;
    private String alignment;

    private UUID raceId;
    private UUID classId;
    private UUID subclassId;
    private UUID backgroundId;
    private String abilityScoreMethod;
    private String racialAbilityBonuses;

    private Integer strength;
    private Integer dexterity;
    private Integer constitution;
    private Integer intelligence;
    private Integer wisdom;
    private Integer charisma;

    private Integer hpMax;
    private Integer hpCurrent;
    private Integer hpTemp;
    private String hitDiceTotal;
    private String hitDiceRemaining;
    private Integer armourClass;
    private Integer initiativeBonus;
    private Integer speed;
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
    private String preparedSpells;
    private String attunedItems;
    private String equippedItems;
    private String hitDiceMap;
}
