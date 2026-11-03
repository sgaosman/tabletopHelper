package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** The coins a character carries, in {@code player_characters.currency} (D168). A coin left out counts as 0. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Currency(
        @Schema(requiredMode = REQUIRED) int cp,
        @Schema(requiredMode = REQUIRED) int sp,
        @Schema(requiredMode = REQUIRED) int ep,
        @Schema(requiredMode = REQUIRED) int gp,
        @Schema(requiredMode = REQUIRED) int pp
) {

    /** No coins, as a new character starts. */
    public static final Currency NONE = new Currency(0, 0, 0, 0, 0);
}
