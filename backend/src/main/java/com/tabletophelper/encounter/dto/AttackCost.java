package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.ActionCost;

/**
 * What an attack costs from its attacker's turn (D174): an attack of the Attack action, opening it with the
 * attacker's action when it hasn't one going (Extra Attack, PHB p.72); an off-hand attack, the bonus action
 * two-weapon fighting gives after the Attack action (PHB p.195); or an opportunity attack, a reaction that may be
 * taken on another creature's turn (PHB p.195).
 */
public enum AttackCost {
    ACTION,
    BONUS_ACTION,
    REACTION;

    /** The cost as the action economy names it. */
    public ActionCost actionCost() {
        return switch (this) {
            case ACTION -> ActionCost.ACTION;
            case BONUS_ACTION -> ActionCost.BONUS_ACTION;
            case REACTION -> ActionCost.REACTION;
        };
    }
}
