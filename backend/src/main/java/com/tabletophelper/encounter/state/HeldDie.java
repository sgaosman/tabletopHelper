package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A die a creature holds to add to a roll of its own, kept on the condition entry that marks it (M17): a Bardic
 * Inspiration die (PHB p.53), added to one attack roll, ability check or saving throw within 10 minutes.
 *
 * @param sides        the die's sides, such as 8 for a d8
 * @param onFailedSave whether its holder rolls it on its next failed saving throw that the server rolls, which turns
 *                     the failure into a success when the die makes up the difference; false while it is kept for
 *                     an attack, or to roll by hand
 */
public record HeldDie(@Schema(requiredMode = REQUIRED) int sides, @Schema(requiredMode = REQUIRED) boolean onFailedSave) {

    public HeldDie withOnFailedSave(boolean armed) {
        return new HeldDie(sides, armed);
    }
}
