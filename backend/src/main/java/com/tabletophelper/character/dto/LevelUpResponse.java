package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder
public class LevelUpResponse {

    @Schema(requiredMode = REQUIRED)
    private CharacterResponse character;

    @Schema(requiredMode = REQUIRED)
    private PendingChoices pendingChoices;

    @Data
    @Builder
    public static class PendingChoices {

        @Schema(requiredMode = REQUIRED)
        private boolean asiAvailable;

        @Schema(requiredMode = REQUIRED)
        private boolean subclassRequired;

        @Schema(requiredMode = REQUIRED)
        private boolean expertiseAvailable;

        @Schema(requiredMode = REQUIRED)
        private int expertiseCount;

        @Schema(requiredMode = REQUIRED)
        private boolean spellSelectionNeeded;

        // "known" or "prepared", set only when spellSelectionNeeded is
        private String spellSelectionType;

        @Schema(requiredMode = REQUIRED)
        private List<String> newFeatures;

        @Schema(requiredMode = REQUIRED)
        private int maxSpellLevel;
    }
}
