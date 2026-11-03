package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A field left out, or null, keeps its value; a JSON field given replaces the whole value. The Derived Stats, and the
 * level, class, features and level history, aren't here: the server works them out, and only levelling changes the
 * rest (D158, D167). Workstream D replaces this request with intent endpoints.
 */
@Data
public class CharacterUpdateRequest {

    @Size(max = 100)
    private String name;
    private String race;
    @Min(0)
    private Integer experiencePoints;
    private String background;
    private String alignment;

    private UUID raceId;
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

    // At most the hit point maximum
    @Min(0)
    private Integer hpCurrent;
    @Min(0)
    private Integer hpTemp;

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

    private List<KnownSpell> spellsKnown;

    // Only each slot's used count is read, for a slot the character has, between 0 and its maximum
    private Map<String, SpellSlot> spellSlots;

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

    // Keyed by class name; only each class's remaining count is read, between 0 and its total
    private Map<String, HitDicePool> hitDice;

    private List<FeatResourceEntry> featResources;

    // Only each pool's current uses are read, for a pool the character has, between 0 and its maximum
    private List<ResourcePoolEntry> resourcePools;
}
