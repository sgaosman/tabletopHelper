package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.StandardActions;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One of the standard actions of PHB p.192–193 other than Attack and Cast a Spell, taken on the participant's turn
 * (D174).
 *
 * @param participantId who takes it
 * @param action        which action
 * @param cost          what it costs: an action when left out; a bonus action for Dash, Disengage or Hide with
 *                      Cunning Action, or Disengage or Hide with Nimble Escape
 * @param targetId      the creature Help names; only Help takes one, and it must
 * @param note          what the log adds: Ready's trigger, which it must have, or the object of Use an Object
 */
public record StandardActionRequest(
        @NotNull UUID participantId,
        @NotNull StandardActions.Action action,
        StandardActionCost cost,
        UUID targetId,
        @Size(max = 500) String note
) {
}
