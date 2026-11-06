package com.tabletophelper.rules.combat;

/**
 * What a command costs from a creature's turn (PHB p.189–190, D174). The names match a resource pool's
 * {@code spendActionType} (D125).
 */
public enum ActionCost {

    /** The creature's action, or one attack of an Attack action it has taken (Extra Attack, PHB p.72). */
    ACTION,

    /** The creature's bonus action, which it has only when something grants one (PHB p.189). */
    BONUS_ACTION,

    /** The creature's reaction, back at the start of its own turn (PHB p.190). */
    REACTION,

    /** Nothing from the budget, though the use is still a use (Legendary Resistance, for one). */
    FREE,

    /** One attack of an Attack action, used for something else instead (opening the Attack action if needed). */
    REPLACE_ATTACK,

    /** Nothing at all: commands outside the action economy, such as damage entered by the DM. */
    NONE
}
