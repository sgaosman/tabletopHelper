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
@Table(name = "races")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Race {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    // The columns of source, size and creatureType allow null, but races are only written by RaceSeeder,
    // which falls back to "", Medium and Humanoid
    @Schema(requiredMode = REQUIRED)
    @Column(length = 100)
    private String source;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 20)
    private String size;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String speed;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ability_score_bonuses", columnDefinition = "jsonb")
    private String abilityScoreBonuses;

    @Schema(requiredMode = REQUIRED)
    @Column(name = "creature_type", length = 50)
    private String creatureType;

    private Integer darkvision;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String traits;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String proficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String resistances;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "race_choices", columnDefinition = "jsonb")
    private String raceChoices;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "additional_spells", columnDefinition = "jsonb")
    private String additionalSpells;

    @Column(name = "base_race_name", length = 200)
    private String baseRaceName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
