package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ApplyChoicesRequest {
    private AsiChoice asi;
    private UUID subclassId;
    private UUID classId;
    private List<String> expertiseSkills;

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
}
