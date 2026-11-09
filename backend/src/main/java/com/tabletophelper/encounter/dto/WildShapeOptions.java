package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The beasts a druid can become with Wild Shape now (PHB p.66–67), by the Beast Shapes limits of its druid level, with
 * Circle Forms for a Circle of the Moon druid (PHB p.69), and the elementals of Elemental Wild Shape. Whether the druid
 * has seen a beast is the DM's call.
 *
 * @param druidLevel        the participant's druid level; below 2 it has no Wild Shape
 * @param circleOfTheMoon   whether it is a Circle of the Moon druid
 * @param maxChallengeRating the highest challenge rating it can become, such as "1/4"; null without Wild Shape
 * @param flyingAllowed     whether it can become a beast with a flying speed
 * @param swimmingAllowed   whether it can become a beast with a swimming speed
 * @param cost              what transforming costs, ACTION or BONUS_ACTION; null without Wild Shape
 * @param currentForm       the beast it is now, or null
 * @param durationHours     how long a Wild Shape lasts at most, in hours
 * @param beasts            the beasts within the limits, by challenge rating and then name, from the rulebooks and with
 *                          hit points
 * @param elementals        the elementals a Circle of the Moon druid of 10th level can become with Elemental Wild Shape,
 *                          which spends two uses of Wild Shape (PHB p.69); empty for any other druid
 */
public record WildShapeOptions(
        @Schema(requiredMode = REQUIRED) int druidLevel,
        @Schema(requiredMode = REQUIRED) boolean circleOfTheMoon,
        String maxChallengeRating,
        @Schema(requiredMode = REQUIRED) boolean flyingAllowed,
        @Schema(requiredMode = REQUIRED) boolean swimmingAllowed,
        String cost,
        String currentForm,
        @Schema(requiredMode = REQUIRED) int durationHours,
        @Schema(requiredMode = REQUIRED) List<Beast> beasts,
        @Schema(requiredMode = REQUIRED) List<Beast> elementals
) {

    /**
     * A beast, or an elemental, the druid can become.
     *
     * @param monsterId       its monster row, which the use-pool request names as beastId
     * @param name            its name
     * @param challengeRating its challenge rating, as the stat block writes it
     * @param hitPoints       its hit points
     * @param armourClass     its AC
     * @param speed           its speeds in feet by kind
     * @param size            its size, such as "M"
     * @param source          the book it comes from
     */
    public record Beast(
            @Schema(requiredMode = REQUIRED) UUID monsterId,
            @Schema(requiredMode = REQUIRED) String name,
            String challengeRating,
            @Schema(requiredMode = REQUIRED) int hitPoints,
            @Schema(requiredMode = REQUIRED) int armourClass,
            @Schema(requiredMode = REQUIRED) Map<String, Integer> speed,
            String size,
            String source
    ) {
    }
}
