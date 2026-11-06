package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "subclasses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subclass {

    @Schema(requiredMode = REQUIRED)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(requiredMode = REQUIRED)
    @Column(length = 200, nullable = false)
    private String name;

    // The column allows null, but subclasses are only written by CharacterClassLoader, which falls back to ""
    @Schema(requiredMode = REQUIRED)
    @Column(length = 100)
    private String source;

    @Schema(requiredMode = REQUIRED)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "character_class_id", nullable = false)
    @JsonIgnoreProperties({"features", "startingEquipment", "spellSlotProgression", "skillChoices", "armorProficiencies", "weaponProficiencies", "toolProficiencies"})
    private CharacterClass characterClass;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String features;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "always_prepared_spells", columnDefinition = "jsonb")
    private String alwaysPreparedSpells;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expanded_spell_list", columnDefinition = "jsonb")
    private String expandedSpellList;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "additional_proficiencies", columnDefinition = "jsonb")
    private String additionalProficiencies;

    // Mechanical effects the 5etools data doesn't give, such as {"hpPerClassLevel": 1}, from the effects overlay the
    // content loader applies (V28); null for none. Character rules read it, and it isn't part of the API
    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String effects;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
