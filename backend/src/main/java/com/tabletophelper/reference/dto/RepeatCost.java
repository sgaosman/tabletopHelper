package com.tabletophelper.reference.dto;

import com.tabletophelper.rules.combat.ActionCost;

/**
 * What using a spell's repeat effect again costs from its caster's turn (D174): an action, a bonus action, or nothing
 * (an effect that happens by itself, such as Spirit Guardians' damage).
 */
public enum RepeatCost {
    ACTION,
    BONUS_ACTION,
    NONE;

    /** The repeat cost a template's repeatActionType names, or null for one it doesn't name. */
    public static RepeatCost of(ActionCost cost) {
        if (cost == null) {
            return null;
        }
        return switch (cost) {
            case ACTION -> ACTION;
            case BONUS_ACTION -> BONUS_ACTION;
            case NONE -> NONE;
            default -> null;
        };
    }
}
