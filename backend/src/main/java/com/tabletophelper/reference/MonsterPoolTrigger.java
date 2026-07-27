package com.tabletophelper.reference;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Defines conditions under which a monster automatically receives a
 * resource pool when added to an encounter (e.g., "has legendary actions"
 * → legendary action pool, "has recharge ability" → recharge pool).
 */
@Entity
@Table(name = "monster_pool_triggers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonsterPoolTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", referencedColumnName = "pool_id", nullable = false)
    private ResourcePoolDefinition poolDefinition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_condition", columnDefinition = "jsonb", nullable = false)
    private String triggerCondition;

    @Builder.Default
    private Integer priority = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
