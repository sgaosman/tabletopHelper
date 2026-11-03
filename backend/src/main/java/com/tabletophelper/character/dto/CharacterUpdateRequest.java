package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A field left out, or null, keeps its value; a JSON field given replaces the whole value. */
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

    @Min(1)
    private Integer hpMax;
    @Min(0)
    private Integer hpCurrent;
    @Min(0)
    private Integer hpTemp;
    @Min(0)
    private Integer armourClass;
    private Integer initiativeBonus;
    @Min(0)
    private Integer speed;
    @Min(2) @Max(6)
    private Integer proficiencyBonus;

    private List<String> savingThrowProficiencies;
    private List<String> skillProficiencies;
    private List<String> skillExpertises;
    private List<String> armorProficiencies;
    private List<String> weaponProficiencies;
    private List<String> toolProficiencies;
    private List<String> languageProficiencies;
    private List<String> damageResistances;
    private List<String> damageImmunities;
    private List<String> conditionImmunities;

    private List<Feature> features;
    private List<KnownSpell> spellsKnown;
    private Map<String, SpellSlot> spellSlots;
    private Integer spellSaveDc;
    private Integer spellAttackBonus;
    private String spellcastingAbility;

    private List<InventoryItem> equipment;
    private Currency currency;

    private String personalityTraits;
    private String ideals;
    private String bonds;
    private String flaws;
    private String notes;

    private String portraitUrl;
    private UUID campaignId;
    private Boolean clearCampaign;

    private List<ClassEntry> multiclassEntries;

    // Keyed by class name
    private Map<String, HitDicePool> hitDice;

    private List<LevelHistoryEntry> levelHistory;
    private List<FeatResourceEntry> featResources;
    private List<ResourcePoolEntry> resourcePools;
}
