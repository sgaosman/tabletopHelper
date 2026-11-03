package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A subclass as the level history entry of its class's subclass level records it (D159): levelling down past that
 * level takes the subclass away again. The features it brings are among the {@code featuresGained} of the levels
 * they come at.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubclassChosen(
        @Schema(requiredMode = REQUIRED) UUID classId,
        @Schema(requiredMode = REQUIRED) UUID subclassId,
        @Schema(requiredMode = REQUIRED) String subclassName
) {
}
