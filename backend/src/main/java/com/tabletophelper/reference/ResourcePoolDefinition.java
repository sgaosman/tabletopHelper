package com.tabletophelper.reference;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Seeded reference table defining every type of resource pool that can exist.
 * Not specific to any character or monster — the template from which
 * concrete {@code ResourcePoolEntry} instances are created.
 */
@Entity
@Table(name = "resource_pool_definitions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePoolDefinition {

    @Id
    @Column(name = "pool_id", length = 64)
    private String poolId;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Column(name = "source_type", length = 20, nullable = false)
    private String sourceType;

    @Column(name = "max_uses_formula", length = 100)
    private String maxUsesFormula;

    @Column(name = "reset_on", length = 20, nullable = false)
    private String resetOn;

    @Column(name = "reset_amount", length = 100)
    private String resetAmount;

    @Column(name = "reset_check", length = 20)
    private String resetCheck;

    @Column(name = "spend_action_type", length = 30)
    private String spendActionType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 30)
    private String icon;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
