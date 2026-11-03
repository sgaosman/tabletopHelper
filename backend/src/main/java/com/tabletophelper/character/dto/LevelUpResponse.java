package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A level gained. What the player has to choose for it is in the character's Pending Choices, those of the new level
 * among them (D159).
 */
@Data
@Builder
public class LevelUpResponse {

    @Schema(requiredMode = REQUIRED)
    private CharacterResponse character;

    // The names of the features the level brought
    @Schema(requiredMode = REQUIRED)
    private List<String> newFeatures;
}
