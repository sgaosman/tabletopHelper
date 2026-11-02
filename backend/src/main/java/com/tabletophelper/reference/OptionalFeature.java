package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonRawValue;
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
@Table(name = "optional_features")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionalFeature {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

    @Schema(requiredMode = REQUIRED)
    @Column(name = "feature_type", length = 50, nullable = false)
    private String featureType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String prerequisite;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
