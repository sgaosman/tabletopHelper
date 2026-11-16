package com.tabletophelper.reference;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a race row to a resource pool definition it grants (M24.5b, D208), as {@link ClassFeaturePool} links a class.
 * A subrace is a race row of its own ("Fallen Aasimar"), so each row that has the trait is mapped. A character gains
 * the pool when its total level reaches {@code minLevel}, as an Aasimar's transformation at 3rd level (VGM p.105).
 */
@Entity
@Table(name = "race_feature_pools")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaceFeaturePool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "race_id", nullable = false)
    private Race race;

    @Column(name = "min_level", nullable = false)
    private Integer minLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", referencedColumnName = "pool_id", nullable = false)
    private ResourcePoolDefinition poolDefinition;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
