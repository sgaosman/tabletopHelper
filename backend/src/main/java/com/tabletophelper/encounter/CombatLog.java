package com.tabletophelper.encounter;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "combat_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CombatLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id", nullable = false)
    private Encounter encounter;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private CombatActionType actionType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "roll_value")
    private Integer rollValue;

    @Column(name = "roll_total")
    private Integer rollTotal;

    @Column(name = "damage_dealt")
    private Integer damageDealt;

    @Column(name = "healing_done")
    private Integer healingDone;

    @Column(name = "turn_participant_name", length = 200)
    private String turnParticipantName;

    @Column(name = "legendary_resistance_eligible")
    private Boolean legendaryResistanceEligible;

    @Column(name = "lr_target_id")
    private UUID lrTargetId;

    @Column(name = "lr_damage_dealt")
    private Integer lrDamageDealt;

    @Column(name = "lr_conditions_applied", length = 500)
    private String lrConditionsApplied;

    @Column(name = "lr_resolved")
    private Boolean lrResolved;

    // What the target takes if Legendary Resistance turns its failed save into a success, after its
    // defences, and the conditions that save applied, which it removes (D163). Entries logged before R5
    // have neither, and Legendary Resistance can't undo them.
    @Column(name = "lr_success_damage")
    private Integer lrSuccessDamage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lr_condition_ids", columnDefinition = "jsonb")
    private List<UUID> lrConditionIds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
