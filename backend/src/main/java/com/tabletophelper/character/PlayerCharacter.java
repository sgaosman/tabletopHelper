package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
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
    @JoinColumn(name = "race_id")
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

    @Column(name = "hit_dice_total", length = 20)
    private String hitDiceTotal;

    @Column(name = "hit_dice_remaining", length = 20)
    private String hitDiceRemaining;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throw_proficiencies", columnDefinition = "jsonb")
    private String savingThrowProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_proficiencies", columnDefinition = "jsonb")
    private String skillProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_expertises", columnDefinition = "jsonb")
    private String skillExpertises;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "armor_proficiencies", columnDefinition = "jsonb")
    private String armorProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weapon_proficiencies", columnDefinition = "jsonb")
    private String weaponProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_proficiencies", columnDefinition = "jsonb")
    private String toolProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "language_proficiencies", columnDefinition = "jsonb")
    private String languageProficiencies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_resistances", columnDefinition = "jsonb")
    private String damageResistances;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_immunities", columnDefinition = "jsonb")
    private String damageImmunities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_immunities", columnDefinition = "jsonb")
    private String conditionImmunities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spells_known", columnDefinition = "jsonb")
    private String spellsKnown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots", columnDefinition = "jsonb")
    private String spellSlots;

    @Column(name = "spell_save_dc")
    private Integer spellSaveDc;

    @Column(name = "spell_attack_bonus")
    private Integer spellAttackBonus;

    @Column(name = "spellcasting_ability", length = 20)
    private String spellcastingAbility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String equipment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String currency;

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
    private String racialAbilityBonuses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "multiclass_entries", columnDefinition = "jsonb")
    private String multiclassEntries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prepared_spells", columnDefinition = "jsonb")
    private String preparedSpells;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attuned_items", columnDefinition = "jsonb")
    private String attunedItems;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "equipped_items", columnDefinition = "jsonb")
    private String equippedItems;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hit_dice_map", columnDefinition = "jsonb")
    private String hitDiceMap;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "level_history", columnDefinition = "jsonb")
    private String levelHistory;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
