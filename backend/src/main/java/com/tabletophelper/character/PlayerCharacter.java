package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
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
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Subclass;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "player_characters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "race_id", nullable = false)
    private Race raceRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private CharacterClass classRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subclass_id")
    private Subclass subclassRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "background_id")
    private Background backgroundRef;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String race;

    @Column(name = "character_class", length = 100)
    private String characterClass;

    @Column(length = 100)
    private String subclass;

    @Builder.Default
    private Integer level = 1;

    @Column(name = "experience_points")
    @Builder.Default
    private Integer experiencePoints = 0;

    @Column(length = 100)
    private String background;

    @Column(length = 50)
    private String alignment;

    @Column(nullable = false)
    private Integer strength;

    @Column(nullable = false)
    private Integer dexterity;

    @Column(nullable = false)
    private Integer constitution;

    @Column(nullable = false)
    private Integer intelligence;

    @Column(nullable = false)
    private Integer wisdom;

    @Column(nullable = false)
    private Integer charisma;

    @Column(name = "hp_max", nullable = false)
    private Integer hpMax;

    @Column(name = "hp_current", nullable = false)
    private Integer hpCurrent;

    @Column(name = "hp_temp")
    @Builder.Default
    private Integer hpTemp = 0;

    @Column(name = "armour_class", nullable = false)
    private Integer armourClass;

    @Column(name = "initiative_bonus")
    @Builder.Default
    private Integer initiativeBonus = 0;

    @Builder.Default
    private Integer speed = 30;

    @Column(name = "proficiency_bonus")
    @Builder.Default
    private Integer proficiencyBonus = 2;

    // The JSONB columns map to records (D168). V29 dropped the deprecated hit_dice_total, hit_dice_remaining and
    // hit_dice_map, which hit_dice replaces, prepared_spells, attuned_items and equipped_items, which nothing read,
    // and feat_resources, whose uses are now pools (D158).

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throw_proficiencies", columnDefinition = "jsonb")
    private List<String> savingThrowProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_proficiencies", columnDefinition = "jsonb")
    private List<String> skillProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_expertises", columnDefinition = "jsonb")
    private List<String> skillExpertises;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "armor_proficiencies", columnDefinition = "jsonb")
    private List<String> armorProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weapon_proficiencies", columnDefinition = "jsonb")
    private List<String> weaponProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_proficiencies", columnDefinition = "jsonb")
    private List<String> toolProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "language_proficiencies", columnDefinition = "jsonb")
    private List<String> languageProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_resistances", columnDefinition = "jsonb")
    private List<String> damageResistances;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_immunities", columnDefinition = "jsonb")
    private List<String> damageImmunities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_immunities", columnDefinition = "jsonb")
    private List<String> conditionImmunities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Feature> features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spells_known", columnDefinition = "jsonb")
    private List<KnownSpell> spellsKnown;

    // Keyed "1" to "9" by spell level, and "pact_N" for pact slots (D168)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots", columnDefinition = "jsonb")
    private Map<String, SpellSlot> spellSlots;

    @Column(name = "spell_save_dc")
    private Integer spellSaveDc;

    @Column(name = "spell_attack_bonus")
    private Integer spellAttackBonus;

    @Column(name = "spellcasting_ability", length = 20)
    private String spellcastingAbility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<InventoryItem> equipment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Currency currency;

    @Column(name = "personality_traits", columnDefinition = "TEXT")
    private String personalityTraits;

    @Column(columnDefinition = "TEXT")
    private String ideals;

    @Column(columnDefinition = "TEXT")
    private String bonds;

    @Column(columnDefinition = "TEXT")
    private String flaws;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "death_save_successes")
    @Builder.Default
    private Integer deathSaveSuccesses = 0;

    @Column(name = "death_save_failures")
    @Builder.Default
    private Integer deathSaveFailures = 0;

    @Column(name = "portrait_url", length = 500)
    private String portraitUrl;

    @Column(name = "ability_score_method", length = 20)
    private String abilityScoreMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "racial_ability_bonuses", columnDefinition = "jsonb")
    private List<AbilityBonus> racialAbilityBonuses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "multiclass_entries", columnDefinition = "jsonb")
    private List<ClassEntry> multiclassEntries;

    // Keyed by class name (D158, V19)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hit_dice", columnDefinition = "jsonb")
    private Map<String, HitDicePool> hitDice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "level_history", columnDefinition = "jsonb")
    private List<LevelHistoryEntry> levelHistory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_pools", columnDefinition = "jsonb")
    private List<ResourcePoolEntry> resourcePools;

    // The DM's adjustments, oldest first (D158, V22): only the active ones count, and a removed one stays as the record
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dm_adjustments", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<DmAdjustment> dmAdjustments = List.of();

    // The choices its levels owe that the player hasn't made yet, oldest level first (D159, V23)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_choices", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<PendingChoice> pendingChoices = List.of();

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // A character who died in an encounter, until the DM revives it: it stays at 0 hit points, and can't rest, be
    // healed, gain or lose a level, or join an encounter (D166, V24)
    @Column(name = "is_dead", nullable = false)
    private boolean dead;

    // The backstop behind the row lock every command takes: a write from a copy that someone else has changed since
    // fails instead of overwriting it (D158, V21)
    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
