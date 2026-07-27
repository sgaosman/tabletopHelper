package com.tabletophelper.reference;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Links character classes (and optionally subclasses) to the resource pool
 * definitions they grant. A character gains the pool when their class level
 * reaches {@code minLevel}.
 */
@Entity
@Table(name = "class_feature_pools")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassFeaturePool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private CharacterClass characterClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subclass_id")
    private Subclass subclass;

    @Column(name = "min_level", nullable = false)
    private Integer minLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", referencedColumnName = "pool_id", nullable = false)
    private ResourcePoolDefinition poolDefinition;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
