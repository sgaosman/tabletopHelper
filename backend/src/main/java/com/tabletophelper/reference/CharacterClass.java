package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "character_classes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterClass {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 100)
    private String source;

    @Column(name = "hit_dice")
    private Integer hitDice;

    @Column(name = "primary_ability", length = 50)
    private String primaryAbility;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throw_proficiencies", columnDefinition = "jsonb")
    private String savingThrowProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "armor_proficiencies", columnDefinition = "jsonb")
    private String armorProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weapon_proficiencies", columnDefinition = "jsonb")
    private String weaponProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_proficiencies", columnDefinition = "jsonb")
    private String toolProficiencies;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_choices", columnDefinition = "jsonb")
    private String skillChoices;

    @Column(name = "spellcasting_ability", length = 20)
    private String spellcastingAbility;

    @Column(name = "is_spellcaster")
    @Builder.Default
    private Boolean isSpellcaster = false;

    @Column(name = "is_prepared_caster")
    @Builder.Default
    private Boolean isPreparedCaster = false;

    @Column(name = "is_known_caster")
    @Builder.Default
    private Boolean isKnownCaster = false;

    @Column(name = "is_pact_magic")
    @Builder.Default
    private Boolean isPactMagic = false;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slot_progression", columnDefinition = "jsonb")
    private String spellSlotProgression;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String features;

    @JsonRawValue
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starting_equipment", columnDefinition = "jsonb")
    private String startingEquipment;

    @Column(name = "subclass_level")
    private Integer subclassLevel;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
