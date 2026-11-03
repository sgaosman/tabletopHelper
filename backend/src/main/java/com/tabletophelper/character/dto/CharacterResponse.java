package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A character. Its JSON columns arrive as structured JSON, in the shapes of the character.state records (D168). */
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

    // The ability scores, the armour class, the hit point maximum, initiative, speed, the spell numbers and the damage
    // defences below all include the active DM Adjustments (D158)
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

    // The scores before the DM Adjustments, which Ability Score Improvements raise up to the maximums
    @Schema(requiredMode = REQUIRED)
    private BaseAbilityScores baseAbilityScores;

    @Schema(requiredMode = REQUIRED)
    private Integer hpMax;

    @Schema(requiredMode = REQUIRED)
    private Integer hpCurrent;

    @Schema(requiredMode = REQUIRED)
    private Integer hpTemp;

    @Schema(requiredMode = REQUIRED)
    private Integer armourClass;

    @Schema(requiredMode = REQUIRED)
    private Integer initiativeBonus;

    @Schema(requiredMode = REQUIRED)
    private Integer speed;

    @Schema(requiredMode = REQUIRED)
    private Integer proficiencyBonus;

    private List<String> savingThrowProficiencies;
    private List<String> skillProficiencies;
    private List<String> skillExpertises;
    private List<String> armorProficiencies;
    private List<String> weaponProficiencies;
    private List<String> toolProficiencies;
    private List<String> languageProficiencies;
    // From the race, features and feats, and from the DM's DEFENCE adjustments, each damage type once; only the DM's
    // adjustments give vulnerabilities (D161)
    @Schema(requiredMode = REQUIRED)
    private List<String> damageResistances;

    @Schema(requiredMode = REQUIRED)
    private List<String> damageImmunities;

    @Schema(requiredMode = REQUIRED)
    private List<String> damageVulnerabilities;

    private List<String> conditionImmunities;

    private List<Feature> features;
    private List<KnownSpell> spellsKnown;

    // Keyed "1" to "9" by spell level, and "pact_N" for pact slots
    private Map<String, SpellSlot> spellSlots;

    // The main spell numbers, which race and feat spells use (D164), and the ability behind them: the one chosen at
    // creation for race and feat spells, or else the first casting class's
    private Integer spellSaveDc;
    private Integer spellAttackBonus;
    private String spellcastingAbility;

    // What each class casts with, worked out by the server, in the order the classes were taken (D158, D167)
    @Schema(requiredMode = REQUIRED)
    private List<ClassSpellcasting> spellcasting;

    @Schema(requiredMode = REQUIRED)
    private AbilityScoreMaximums abilityScoreMaximums;

    // The always-prepared spells of each of the character's subclasses, by subclass name and then by the class
    // level they come at
    private Map<String, Map<String, List<String>>> subclassAlwaysPreparedSpells;

    private List<InventoryItem> equipment;
    private Currency currency;

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
    private List<AbilityBonus> racialAbilityBonuses;
    private List<ClassEntry> multiclassEntries;

    // Keyed by class name: one die per level in the class, the totals worked out by the server
    private Map<String, HitDicePool> hitDice;

    private List<LevelHistoryEntry> levelHistory;
    private List<ResourcePoolEntry> resourcePools;

    // The DM's adjustments, oldest first, the removed ones kept as the record; only the active ones count (D158)
    @Schema(requiredMode = REQUIRED)
    private List<DmAdjustment> dmAdjustments;

    // The choices its levels owe that the player hasn't made yet, oldest level first; apply-choices makes one (D159)
    @Schema(requiredMode = REQUIRED)
    private List<PendingChoice> pendingChoices;

    @Schema(requiredMode = REQUIRED)
    private Boolean isActive;

    // Whether the character is in an Active or Paused encounter, while rests, levelling and the choices a level
    // brings wait for it to end (D166)
    @Schema(requiredMode = REQUIRED)
    private boolean inLiveEncounter;

    // Null in the response that creates the character: @CreationTimestamp and @UpdateTimestamp only fill
    // them in when the insert is flushed, after the response is built
    private Instant createdAt;
    private Instant updatedAt;
}
