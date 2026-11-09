package com.tabletophelper.reference;

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
@Table(name = "items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
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

    // The columns allow null, but items are only written by ItemLoader, which sets this and isHomebrew
    @Schema(requiredMode = REQUIRED)
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

    /** The kind of armour the item is, which decides how its AC adds up (PHB p.144); null for anything else. */
    @Enumerated(EnumType.STRING)
    @Column(name = "armour_category", length = 10)
    private ArmourCategory armourCategory;

    /** An armour's base AC, or a shield's bonus of 2; null for anything that isn't armour. */
    @Column(name = "armour_class_base")
    private Integer armourClassBase;

    /** The Strength score heavy armour needs before it stops slowing its wearer (PHB p.144); null for none. */
    @Column(name = "strength_requirement")
    private Integer strengthRequirement;

    /** Whether the armour gives disadvantage on Dexterity (Stealth) checks (PHB p.144). */
    @Schema(requiredMode = REQUIRED)
    @Column(name = "stealth_disadvantage", nullable = false)
    @Builder.Default
    private Boolean stealthDisadvantage = false;

    /** A magic armour's or shield's bonus to AC, such as Elven Chain's +1; null for none or for other items. */
    @Column(name = "bonus_ac")
    private Integer bonusAc;

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

    /** The armour categories of PHB p.144, a shield among them. */
    public enum ArmourCategory {
        LIGHT, MEDIUM, HEAVY, SHIELD
    }
}
