package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subclasses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subclass {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

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
    @Column(name = "additional_proficiencies", columnDefinition = "jsonb")
    private String additionalProficiencies;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
