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
@Table(name = "backgrounds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Background {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_proficiencies", columnDefinition = "jsonb")
    private String skillProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_proficiencies", columnDefinition = "jsonb")
    private String toolProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "language_proficiencies", columnDefinition = "jsonb")
    private String languageProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starting_equipment", columnDefinition = "jsonb")
    private String startingEquipment;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String feature;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String feats;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "additional_spells", columnDefinition = "jsonb")
    private String additionalSpells;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
