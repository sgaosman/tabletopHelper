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
 *                     set only in the response, as check is (D200), and null for a plain ability check or a save. A
 *                     monster's grapple or net stores both on its entry, having no template (D203)
 * @param escape       the action is spent escaping a monster's grapple: a Strength (Athletics) or Dexterity
 *                     (Acrobatics) check, the creature's better, against the escape DC (PHB p.195, D203); null
 *                     otherwise
 */
public record RepeatSave(
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) boolean awaitingRoll,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean byAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean check,
        @JsonInclude(JsonInclude.Include.NON_NULL) String skill,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean escape
) {

    /** A save or check that isn't an escape (D203). */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll, Boolean byAction, Boolean check, String skill) {
        this(ability, dc, awaitingRoll, byAction, check, skill, null);
    }

    /**
     * The escape from a monster's grapple, made with the creature's action (PHB p.195, D203): an Athletics or Acrobatics
     * check against the grapple's escape DC.
     */
    public static RepeatSave escape(int dc) {
        return new RepeatSave(Ability.STR, dc, false, true, true, ESCAPE_SKILLS, true);
    }

    /** An ability check of one ability made with the creature's action to end a condition, as a net's (D203). */
    public static RepeatSave checkByAction(Ability ability, int dc) {
        return new RepeatSave(ability, dc, false, true, true, null, null);
    }

    /** The skills an escape names. */
    public static final String ESCAPE_SKILLS = "Athletics or Acrobatics";

    /** Whether it is the escape from a grapple (D203). */
    public boolean escapes() {
        return Boolean.TRUE.equals(escape);
    }

    /** Whether the action is spent on an ability check rather than a save. */
    public boolean madeAsCheck() {
        return Boolean.TRUE.equals(check);
    }

    /** A save repeated at the end of each of the creature's turns. */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll) {
        this(ability, dc, awaitingRoll, null, null, null);
    }

    /** A save, made at the end of each turn or with the creature's action (D188). */
    public RepeatSave(Ability ability, int dc, boolean awaitingRoll, Boolean byAction) {
        this(ability, dc, awaitingRoll, byAction, null, null);
    }

    public RepeatSave withAwaitingRoll(boolean awaiting) {
        return new RepeatSave(ability, dc, awaiting, byAction, check, skill, escape);
    }

    /** The same, saying the action is spent on an ability check, with its skill or null for none (D200). */
    public RepeatSave asCheck(String checkSkill) {
        return new RepeatSave(ability, dc, awaitingRoll, byAction, true, checkSkill, escape);
    }

    /** Whether the creature makes it with its action (D188). */
    public boolean madeByAction() {
        return Boolean.TRUE.equals(byAction);
    }
}
