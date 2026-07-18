package com.questkeeper.reference;

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
@Table(name = "spells")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Spell {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer level;

    @Column(length = 50)
    private String school;

    @Column(name = "casting_time", length = 500)
    private String castingTime;

    @Column(name = "range_distance", length = 100)
    private String rangeDistance;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String components;

    @Column(length = 100)
    private String duration;

    @Builder.Default
    private Boolean concentration = false;

    @Builder.Default
    private Boolean ritual = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "higher_levels", columnDefinition = "TEXT")
    private String higherLevels;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String classes;

    @Column(name = "damage_type", length = 50)
    private String damageType;

    @Column(name = "damage_dice", length = 50)
    private String damageDice;

    @Column(name = "save_ability", length = 20)
    private String saveAbility;

    @Column(length = 100)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
