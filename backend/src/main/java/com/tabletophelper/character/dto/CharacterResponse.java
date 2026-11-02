package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder
public class CharacterResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    private UUID userId;

    // The column allows null, but registration has always fallen back to the username
    @Schema(requiredMode = REQUIRED)
    private String ownerDisplayName;

    private UUID campaignId;

    // Every character has a race (V11, D152)
    @Schema(requiredMode = REQUIRED)
    private UUID raceId;

    @Schema(requiredMode = REQUIRED)
    private String raceName;

    private UUID classId;
    private String className;
    private UUID subclassId;
    private String subclassName;
    private UUID backgroundId;
    private String backgroundName;

    @Schema(requiredMode = REQUIRED)
    private String name;

    private String race;
    private String characterClass;
    private String subclass;

    // The columns of level, experiencePoints, hpTemp, initiativeBonus, speed, proficiencyBonus, the death
    // saves and isActive allow null, but characters are only created through the entity's builder, which
    // starts each of them, and no update sets one to null
    @Schema(requiredMode = REQUIRED)
    private Integer level;

    @Schema(requiredMode = REQUIRED)
    private Integer experiencePoints;

    private String background;
    private String alignment;

    @Schema(requiredMode = REQUIRED)
    private Integer strength;

    @Schema(requiredMode = REQUIRED)
    private Integer dexterity;

    @Schema(requiredMode = REQUIRED)
    private Integer constitution;

    @Schema(requiredMode = REQUIRED)
    private Integer intelligence;

    @Schema(requiredMode = REQUIRED)
    private Integer wisdom;

    @Schema(requiredMode = REQUIRED)
    private Integer charisma;

    @Schema(requiredMode = REQUIRED)
    private Integer hpMax;

    @Schema(requiredMode = REQUIRED)
    private Integer hpCurrent;

    @Schema(requiredMode = REQUIRED)
    private Integer hpTemp;

    private String hitDiceTotal;
    private String hitDiceRemaining;

    @Schema(requiredMode = REQUIRED)
    private Integer armourClass;

    @Schema(requiredMode = REQUIRED)
    private Integer initiativeBonus;

    @Schema(requiredMode = REQUIRED)
    private Integer speed;

    @Schema(requiredMode = REQUIRED)
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
    private String subclassAlwaysPreparedSpells;

    private String equipment;
    private String currency;

    private String personalityTraits;
    private String ideals;
    private String bonds;
    private String flaws;
    private String notes;

    @Schema(requiredMode = REQUIRED)
    private Integer deathSaveSuccesses;

    @Schema(requiredMode = REQUIRED)
    private Integer deathSaveFailures;

    private String portraitUrl;

    private String abilityScoreMethod;
    private String racialAbilityBonuses;
    private String multiclassEntries;
    private String attunedItems;
    private String equippedItems;
    private String hitDiceMap;
    private String levelHistory;
    private String featResources;
    private String resourcePools;

    @Schema(requiredMode = REQUIRED)
    private Boolean isActive;

    // Null in the response that creates the character: @CreationTimestamp and @UpdateTimestamp only fill
    // them in when the insert is flushed, after the response is built
    private Instant createdAt;
    private Instant updatedAt;
}
