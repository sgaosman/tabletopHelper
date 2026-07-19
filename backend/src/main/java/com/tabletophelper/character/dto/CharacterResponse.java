package com.tabletophelper.character.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CharacterResponse {

    private UUID id;
    private UUID userId;
    private String ownerDisplayName;
    private UUID campaignId;

    private UUID raceId;
    private String raceName;
    private UUID classId;
    private String className;
    private UUID subclassId;
    private String subclassName;
    private UUID backgroundId;
    private String backgroundName;

    private String name;
    private String race;
    private String characterClass;
    private String subclass;
    private Integer level;
    private Integer experiencePoints;
    private String background;
    private String alignment;

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

    private Integer deathSaveSuccesses;
    private Integer deathSaveFailures;
    private String portraitUrl;

    private String abilityScoreMethod;
    private String racialAbilityBonuses;
    private String multiclassEntries;
    private String preparedSpells;
    private String attunedItems;
    private String equippedItems;
    private String hitDiceMap;

    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
