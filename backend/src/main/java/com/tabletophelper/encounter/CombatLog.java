package com.tabletophelper.encounter;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "combat_logs", indexes = {
    @Index(name = "idx_cl_encounter_id", columnList = "encounter_id")
})
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
