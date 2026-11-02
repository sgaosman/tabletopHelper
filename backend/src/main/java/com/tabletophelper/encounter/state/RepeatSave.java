package com.tabletophelper.encounter.state;

import com.tabletophelper.rules.Ability;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The saving throw a creature repeats at the end of each of its turns to end a condition (D162), stored on
 * the condition's entry. The server rolls it for monsters, and for player characters unless the campaign
 * prompts their players, when the entry waits for the player's roll instead.
 *
 * @param ability      the save's ability
 * @param dc           the save's DC
 * @param awaitingRoll the turn ended and the player has yet to roll it
 */
public record RepeatSave(
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) boolean awaitingRoll
) {

    public RepeatSave withAwaitingRoll(boolean awaiting) {
        return new RepeatSave(ability, dc, awaiting);
    }
}
