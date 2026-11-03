package com.tabletophelper.encounter;

import com.tabletophelper.campaign.Campaign;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "encounters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Encounter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EncounterStatus status = EncounterStatus.PREPARING;

    // Whose turn it is, by participant rather than by place in the order, which is computed and shifts as
    // initiative ties are broken and participants and the Lair come and go (D163). Null with lairTurn unset
    // before the encounter starts; V17 keeps the two from both being set.
    @Column(name = "current_turn_participant_id")
    private UUID currentTurnParticipantId;

    // The Lair's turn, on initiative 20 (D163)
    @Column(name = "is_lair_turn", nullable = false)
    @Builder.Default
    private Boolean lairTurn = false;

    @Column(name = "round_number")
    @Builder.Default
    private Integer roundNumber = 1;

    @Column(name = "session_code", length = 8, unique = true)
    private String sessionCode;

    // Whether the DM applied the results to the character sheets on completing it (D166, V24): null until it
    // completes, and for an encounter completed before R6
    @Column(name = "results_applied")
    private Boolean resultsApplied;

    // The monster participants fighting in their lair (D136), typed since R5 (D168). The Lair takes a turn
    // while any of them is alive (D163).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monsters_in_lair", columnDefinition = "jsonb")
    private List<UUID> monstersInLair;

    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<EncounterParticipant> participants = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
