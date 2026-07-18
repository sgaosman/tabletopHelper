package com.questkeeper.monster;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monsters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Monster {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

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

    @Column(name = "armour_class")
    private Integer armourClass;

    @Column(name = "ac_type", length = 200)
    private String acType;

    @Column(name = "hit_points")
    private Integer hitPoints;

    @Column(name = "hit_dice", length = 50)
    private String hitDice;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String speed;

    private Integer strength;
    private Integer dexterity;
    private Integer constitution;
    private Integer intelligence;
    private Integer wisdom;
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

    @Column(length = 100)
    private String source;

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
