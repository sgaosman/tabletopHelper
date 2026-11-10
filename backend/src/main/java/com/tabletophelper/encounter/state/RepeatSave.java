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
 * @param check        the action is spent on an ability check of the ability against the DC, not a save, as against
 *                     Wrathful Smite's fear (PHB p.289) or Ensnaring Strike's vines (PHB p.237) (D199); set only in
 *                     the encounter's response, from the spell's template, so its button can say so (D200), and null
 *                     otherwise
 * @param skill        the skill of that check, as Phantasmal Force's Intelligence (Investigation) (PHB p.264, D199);
 *                     set only in the response, as check is (D200), and null for a plain ability check or a save
 */
public record RepeatSave(
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) boolean awaitingRoll,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean byAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean check,
        @JsonInclude(JsonInclude.Include.NON_NULL) String skill
) {

    /** A save repeated at the end of each of the creature's turns. */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll) {
        this(ability, dc, awaitingRoll, null, null, null);
    }

    /** A save, made at the end of each turn or with the creature's action (D188). */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll, Boolean byAction) {
        this(ability, dc, awaitingRoll, byAction, null, null);
    }

    public RepeatSave withAwaitingRoll(boolean awaiting) {
        return new RepeatSave(ability, dc, awaiting, byAction, check, skill);
    }

    /** The same, saying the action is spent on an ability check, with its skill or null for none (D200). */
    public RepeatSave asCheck(String checkSkill) {
        return new RepeatSave(ability, dc, awaitingRoll, byAction, true, checkSkill);
    }

    /** Whether the creature makes it with its action (D188). */
    public boolean madeByAction() {
        return Boolean.TRUE.equals(byAction);
    }
}
