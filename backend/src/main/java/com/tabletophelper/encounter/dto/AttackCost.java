package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.ActionCost;

/**
 * What an attack costs from its attacker's turn (D174): an attack of the Attack action, opening it with the
 * attacker's action when it hasn't one going (Extra Attack, PHB p.72); an off-hand attack, the bonus action
 * two-weapon fighting gives after the Attack action (PHB p.195); an opportunity attack, a reaction that may be
 * taken on another creature's turn (PHB p.195); a monk's unarmed strike as a bonus action after the Attack action
 * with an unarmed strike or a monk weapon (Martial Arts, PHB p.78); or one of the two unarmed strikes of Flurry of
 * Blows, whose first spends 1 ki point and the bonus action after the Attack action, and whose second costs nothing
 * more (PHB p.78).
 */
public enum AttackCost {
    ACTION,
    BONUS_ACTION,
    REACTION,
    MARTIAL_ARTS,
    FLURRY_OF_BLOWS;

    /** The cost as the action economy names it. */
    public ActionCost actionCost() {
        return switch (this) {
            case ACTION -> ActionCost.ACTION;
            case BONUS_ACTION, MARTIAL_ARTS, FLURRY_OF_BLOWS -> ActionCost.BONUS_ACTION;
            case REACTION -> ActionCost.REACTION;
        };
    }
}
