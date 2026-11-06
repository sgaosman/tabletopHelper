package com.tabletophelper.reference;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Entity
@Table(name = "feats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feat {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

    // The four JSON columns are typed as records with the stored JSON kept whole (D168), which the API returns as it
    // is; OpenApiConfig describes them
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private FeatPrerequisite prerequisite;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ability_score_increase", columnDefinition = "jsonb")
    private FeatAbilityIncrease abilityScoreIncrease;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grants_features", columnDefinition = "jsonb")
    private FeatSpells grantsFeatures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private FeatEffects effects;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
