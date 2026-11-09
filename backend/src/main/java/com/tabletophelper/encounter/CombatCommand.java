package com.tabletophelper.encounter;

import com.tabletophelper.encounter.state.CommandSnapshot;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One combat command an Active encounter took (M14, V32), in the order taken, with what it changed as it was before,
 * which undo puts back. Its combat log entries name it. A barrier, such as the encounter's start, is a command undo
 * can't go past.
 */
@Entity
@Table(name = "combat_commands")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CombatCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    // The order commands were taken in, from 1 for each encounter
    @Column(nullable = false)
    private Long sequence;

    // The action type of the entry that sums the command up, such as SPELL_CAST, or a barrier's kind
    @Column(length = 40)
    private String kind;

    // What the command did, as the DM's undo dialog and the UNDO entry name it
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "issued_by")
    private UUID issuedBy;

    // Null once the encounter has completed, when nothing can be undone any more
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private CommandSnapshot beforeState;

    @Column(nullable = false)
    @Builder.Default
    private boolean barrier = false;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "undone_by")
    private UUID undoneBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
