package com.tabletophelper.encounter;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.monster.Monster;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "encounter_participants", indexes = {
    @Index(name = "idx_ep_encounter_id", columnList = "encounter_id"),
    @Index(name = "idx_ep_character_id", columnList = "character_id")
})
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

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_conditions", columnDefinition = "jsonb")
    private String activeConditions;

    @Column(name = "concentration_spell", length = 200)
    private String concentrationSpell;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots_current", columnDefinition = "jsonb")
    private String spellSlotsCurrent;

    @Column(name = "is_visible_to_players")
    @Builder.Default
    private Boolean isVisibleToPlayers = true;

    @Column(name = "is_alive")
    @Builder.Default
    private Boolean isAlive = true;

    @Column(name = "is_current_turn")
    @Builder.Default
    private Boolean isCurrentTurn = false;

    @Column(name = "controlled_by_user_id")
    private UUID controlledByUserId;

    @Column(name = "death_save_successes")
    @Builder.Default
    private Integer deathSaveSuccesses = 0;

    @Column(name = "death_save_failures")
    @Builder.Default
    private Integer deathSaveFailures = 0;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
