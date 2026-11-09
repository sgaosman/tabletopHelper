package com.tabletophelper.encounter;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.rules.combat.LifeState;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "encounter_participants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncounterParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id", nullable = false)
    private Encounter encounter;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_type", nullable = false)
    private ParticipantType participantType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private PlayerCharacter character;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monster_id")
    private Monster monster;

    @Column(name = "display_name", length = 200, nullable = false)
    private String displayName;

    private Integer initiative;

    @Column(name = "initiative_modifier")
    private Integer initiativeModifier;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "hp_max", nullable = false)
    private Integer hpMax;

    @Column(name = "hp_current", nullable = false)
    private Integer hpCurrent;

    @Column(name = "hp_temp")
    @Builder.Default
    private Integer hpTemp = 0;

    @Column(name = "armour_class", nullable = false)
    private Integer armourClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_conditions", columnDefinition = "jsonb")
    private List<ConditionEntry> activeConditions;

    // The casting it is concentrating on, if any (D162)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concentration", columnDefinition = "jsonb")
    private ConcentrationInstance concentration;

    // The Spell Effects it cast that are still going, such as Spiritual Weapon or Moonbeam (M15, V33)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_effects", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<SpellEffectEntry> activeEffects = new ArrayList<>();

    // Keyed "1" to "9" by spell level, and "pact_N" for pact slots (D168)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots_current", columnDefinition = "jsonb")
    private Map<String, SpellSlot> spellSlotsCurrent;

    @Column(name = "spell_attack_bonus")
    private Integer spellAttackBonus;

    @Column(name = "spell_save_dc")
    private Integer spellSaveDc;

    @Column(name = "spellcasting_ability", length = 20)
    private String spellcastingAbility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spells_known", columnDefinition = "jsonb")
    private List<KnownSpell> spellsKnown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_pools_current", columnDefinition = "jsonb")
    private List<ResourcePoolEntry> resourcePoolsCurrent;

    // What a player character joined with, from which Write-back works out what the encounter changed (D166, V24);
    // null for a monster, and for a participant of an encounter completed before R6
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "joined_with", columnDefinition = "jsonb")
    private CombatTotals joinedWith;

    // What it has spent of its turn, fresh at the start of its own turn (D174, V30)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "turn_economy", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private TurnEconomy turnEconomy = TurnEconomy.fresh();

    @Column(name = "is_visible_to_players")
    @Builder.Default
    private Boolean isVisibleToPlayers = true;

    // Kept consistent with the hit points and death saves, as rules.combat.Vitals requires (D161)
    @Enumerated(EnumType.STRING)
    @Column(name = "life_state", length = 10, nullable = false)
    @Builder.Default
    private LifeState lifeState = LifeState.ALIVE;

    // Set when a Dying PC's turn starts, until it rolls a death save or stops dying (D163)
    @Column(name = "death_save_due", nullable = false)
    @Builder.Default
    private Boolean deathSaveDue = false;

    @Column(name = "controlled_by_user_id")
    private UUID controlledByUserId;

    @Column(name = "death_save_successes")
    @Builder.Default
    private Integer deathSaveSuccesses = 0;

    @Column(name = "death_save_failures")
    @Builder.Default
    private Integer deathSaveFailures = 0;

    @Column(name = "last_lair_action_used", length = 200)
    private String lastLairActionUsed;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
