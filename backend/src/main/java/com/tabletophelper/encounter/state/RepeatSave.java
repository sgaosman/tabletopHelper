package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param byAction     the creature makes it by using its action, not at the end of its turns, and a success ends the
 *                     spell, as against Otto's Irresistible Dance (PHB p.264, D188); null for an end-of-turn save
 */
public record RepeatSave(
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) boolean awaitingRoll,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean byAction
) {

    /** A save repeated at the end of each of the creature's turns. */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll) {
        this(ability, dc, awaitingRoll, null);
    }

    public RepeatSave withAwaitingRoll(boolean awaiting) {
        return new RepeatSave(ability, dc, awaiting, byAction);
    }

    /** Whether the creature makes it with its action (D188). */
    public boolean madeByAction() {
        return Boolean.TRUE.equals(byAction);
    }
}
