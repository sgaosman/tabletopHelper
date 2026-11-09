package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Makes one of the character's Pending Choices (D159), named by its ID, with the answer its type takes and nothing
 * else: {@code asi} for an Ability Score Improvement, {@code feat} for a race's or background's feat,
 * {@code subclassId} for a subclass, {@code expertiseSkills} for Expertise, {@code fightingStyle} for a Fighting
 * Style, {@code metamagic} for a sorcerer's Metamagic options, and {@code spells} for cantrips or spells, with
 * {@code swap} for a known caster's optional swap.
 */
@Data
public class ApplyChoicesRequest {

    @NotNull
    @Schema(requiredMode = REQUIRED)
    private UUID choiceId;

    private AsiChoice asi;
    private AsiChoice feat;
    private UUID subclassId;
    private List<String> expertiseSkills;
    private String fightingStyle;
    private List<String> metamagic;
    private List<String> spells;

    @Valid
    private SpellSwap swap;

    @Data
    public static class AsiChoice {
        @Schema(allowableValues = {"ability", "feat"})
        private String type;
        private List<AbilityIncrease> increases;

        // Legacy free-text (kept for backwards compatibility)
        private String featName;

        // Feat automation fields
        private UUID featId;
        private String featAbility;
        private String resistanceChoice;
        private List<String> skillProficiencyChoices;
        private String savingThrowChoice;
        private List<String> expertiseSkillChoices;
        private List<String> toolProficiencyChoices;
        private List<String> languageChoices;
        private List<String> weaponChoices;
        private List<UUID> spellIds;
        private List<UUID> optionalFeatureIds;
    }

    @Data
    public static class AbilityIncrease {
        private String ability;
        private int bonus;
    }

    /** A known caster's swap of one spell it knows for another from its list, as it gains a level (D159). */
    @Data
    public static class SpellSwap {
        @NotBlank
        @Schema(requiredMode = REQUIRED)
        private String replace;

        @NotBlank
        @Schema(requiredMode = REQUIRED)
        private String with;
    }
}
