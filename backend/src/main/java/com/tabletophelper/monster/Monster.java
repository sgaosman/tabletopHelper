package com.tabletophelper.monster;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Entity
@Table(name = "monsters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Monster {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 20)
    private String size;

    @Column(length = 100)
    private String type;

    @Column(length = 100)
    private String subtype;

    @Column(length = 50)
    private String alignment;

    // The columns of armourClass, hitPoints, the ability scores, experiencePoints and isHomebrew allow
    // null, but monsters are only written by MonsterLoader, which sets each of them, and
    // MonsterActionLoader, which writes only action templates
    @Schema(requiredMode = REQUIRED)
    @Column(name = "armour_class")
    private Integer armourClass;

    @Column(name = "ac_type", length = 200)
    private String acType;

    @Schema(requiredMode = REQUIRED)
    @Column(name = "hit_points")
    private Integer hitPoints;

    @Column(name = "hit_dice", length = 50)
    private String hitDice;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String speed;

    @Schema(requiredMode = REQUIRED)
    private Integer strength;

    @Schema(requiredMode = REQUIRED)
    private Integer dexterity;

    @Schema(requiredMode = REQUIRED)
    private Integer constitution;

    @Schema(requiredMode = REQUIRED)
    private Integer intelligence;

    @Schema(requiredMode = REQUIRED)
    private Integer wisdom;

    @Schema(requiredMode = REQUIRED)
    private Integer charisma;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throws", columnDefinition = "jsonb")
    private String savingThrows;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String skills;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_resistances", columnDefinition = "jsonb")
    private String damageResistances;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_immunities", columnDefinition = "jsonb")
    private String damageImmunities;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_vulnerabilities", columnDefinition = "jsonb")
    private String damageVulnerabilities;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_immunities", columnDefinition = "jsonb")
    private String conditionImmunities;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String senses;

    @Column(length = 500)
    private String languages;

    @Column(name = "challenge_rating", length = 10)
    private String challengeRating;

    @Schema(requiredMode = REQUIRED)
    @Column(name = "experience_points")
    private Integer experiencePoints;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String traits;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String actions;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String reactions;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legendary_actions", columnDefinition = "jsonb")
    private String legendaryActions;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lair_actions", columnDefinition = "jsonb")
    private String lairActions;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_templates", columnDefinition = "jsonb")
    private String actionTemplates;

    @Column(length = 100)
    private String source;

    @Schema(requiredMode = REQUIRED)
    @Column(name = "is_homebrew")
    @Builder.Default
    private Boolean isHomebrew = false;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
