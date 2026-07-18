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
@Table(name = "items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 50)
    private String type;

    @Column(length = 50)
    private String subtype;

    @Column(length = 50)
    private String rarity;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String properties;

    @Column(name = "requires_attunement")
    @Builder.Default
    private Boolean requiresAttunement = false;

    @Column(name = "attunement_condition", length = 200)
    private String attunementCondition;

    private Double weight;

    @Column(length = 50)
    private String cost;

    @Column(name = "damage_dice", length = 20)
    private String damageDice;

    @Column(name = "damage_type", length = 50)
    private String damageType;

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
