package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.ActionCost;

/**
 * What a standard action costs from its participant's turn (D174): its action, or its bonus action when a feature
 * such as Cunning Action grants that (PHB p.96).
 */
public enum StandardActionCost {
    ACTION,
    BONUS_ACTION;

    /** The cost as the action economy names it. */
    public ActionCost actionCost() {
        return switch (this) {
            case ACTION -> ActionCost.ACTION;
            case BONUS_ACTION -> ActionCost.BONUS_ACTION;
        };
    }
}
