package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A class, subclass, race, background or feat feature on a character's sheet, in {@code player_characters.features}
 * and in each level history entry's {@code featuresGained} (D168).
 *
 * @param source what granted it: a class or subclass name, "Feat", or a race or background name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Feature(
        @Schema(requiredMode = REQUIRED) String name,
        String description,
        String source
) {
}
