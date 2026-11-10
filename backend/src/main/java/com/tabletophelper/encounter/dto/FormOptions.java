package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The creatures a spell can turn one creature into (D192), for the cast's picker: Polymorph's and Mass Polymorph's
 * beasts (PHB p.266, XGE p.160), True Polymorph's and Shapechange's creatures (PHB p.283, p.274) and Animal Shapes'
 * beasts (PHB p.212), within the spell's challenge rating limit for that creature. Whether the caster has seen the
 * creature is the DM's call.
 *
 * @param spell              the spell
 * @param targetId           the creature that would take the form
 * @param maxChallengeRating the highest challenge rating allowed, such as "1/2" or "7"
 * @param beastsOnly         whether only beasts are allowed
 * @param largestSize        the largest size allowed, such as "Large", or null for any
 * @param creatures          the creatures within the limits, by challenge rating and then name, from the rulebooks and
 *                           with hit points
 */
public record FormOptions(
        @Schema(requiredMode = REQUIRED) String spell,
        @Schema(requiredMode = REQUIRED) UUID targetId,
        @Schema(requiredMode = REQUIRED) String maxChallengeRating,
        @Schema(requiredMode = REQUIRED) boolean beastsOnly,
        String largestSize,
        @Schema(requiredMode = REQUIRED) List<WildShapeOptions.Beast> creatures
) {
}
