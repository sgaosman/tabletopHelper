package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A feat taken outside an Ability Score Improvement, as its level history entry records it (D159): a race's or
 * background's, at creation, through a FEAT Pending Choice. Its effects count as an Ability Score Improvement's feat's
 * do: CharacterStats adds its speed, initiative and hit points and builds the pool of its limited uses (D158).
 *
 * @param appliedEffects what applying the feat changed on the sheet, which levelling down would reverse
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatTaken(
        @Schema(requiredMode = REQUIRED) String featName,
        @Schema(requiredMode = REQUIRED) UUID featId,
        @Schema(requiredMode = REQUIRED) AppliedFeatEffects appliedEffects
) {
}
