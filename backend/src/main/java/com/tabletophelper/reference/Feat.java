package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String prerequisite;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ability_score_increase", columnDefinition = "jsonb")
    private String abilityScoreIncrease;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grants_features", columnDefinition = "jsonb")
    private String grantsFeatures;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
