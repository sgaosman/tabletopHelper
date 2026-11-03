package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CharacterCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotNull
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
    private List<AbilityBonus> racialAbilityBonuses;

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

    // The hit point maximum, proficiency bonus, armour class (10 + DEX, since armour isn't modelled), initiative,
    // speed (the race's), spell slots, spell save DC and attack bonus, and Hit Dice are the server's to work out
    // (D158, D167), so a request can't set them

    private List<String> savingThrowProficiencies;
    private List<String> skillProficiencies;
    private List<String> skillExpertises;
    private List<String> armorProficiencies;
    private List<String> weaponProficiencies;
    private List<String> toolProficiencies;
    private List<String> languageProficiencies;
    private List<KnownSpell> spellsKnown;

    // The ability race and feat spells are cast with, such as a Fairy's choice of INT, WIS or CHA; a class's own
    // spells use the class's ability
    private String spellcastingAbility;
    private List<Feature> features;
    private List<String> damageResistances;
    private List<InventoryItem> equipment;
    private Currency currency;

    // Each class and its level, for a character created in more than one class; only classId, subclassId and level
    // are read, and the levels add up to the character's level
    private List<ClassEntry> multiclassClassEntries;

    private UUID campaignId;
}
