package com.tabletophelper.character.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LevelUpResponse {
    private CharacterResponse character;
    private PendingChoices pendingChoices;

    @Data
    @Builder
    public static class PendingChoices {
        private boolean asiAvailable;
        private boolean subclassRequired;
        private boolean expertiseAvailable;
        private int expertiseCount;
        private boolean spellSelectionNeeded;
        private String spellSelectionType;
        private List<String> newFeatures;
        private int maxSpellLevel;
    }
}
