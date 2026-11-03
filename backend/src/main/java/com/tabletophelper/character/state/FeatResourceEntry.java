package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A feat's limited uses, such as Lucky's luck points, in the deprecated {@code player_characters.feat_resources}
 * (D168). FeatEffectResolver still adds one when a feat with a resource is taken, rests reset them and the sheet
 * spends them, so the column stays mapped until the sheet's feat-resources strip goes later in R6 (D158).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatResourceEntry(
        String featName,
        String name,
        @Schema(requiredMode = REQUIRED) int maxUses,
        @Schema(requiredMode = REQUIRED) int currentUses,
        String resetOn
) {
}
