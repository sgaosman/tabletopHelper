package com.tabletophelper.encounter;

public enum CombatActionType {
    ATTACK,
    DAMAGE,
    HEAL,
    CONDITION_ADD,
    CONDITION_REMOVE,
    DEATH_SAVE,
    CONCENTRATION_CHECK,
    CONCENTRATION_LOST,
    TURN_ADVANCE,
    TURN_BACK,
    STABILIZE,
    KILL,
    REVIVE,
    SPELL_SLOT_USE,
    SPELL_SLOT_RESTORE,
    SPELL_CAST,
    SPELL_EFFECT_REPEAT,
    MONSTER_ACTION,
    LEGENDARY_ACTION,
    LAIR_ACTION,
    LEGENDARY_RESISTANCE_USED,
    RECHARGE_SUCCESS,
    RECHARGE_FAILURE,
    // The DM's Set HP / revive (D161)
    HP_SET,
    // A save repeated to end a condition, at the end of its creature's turn or when its player rolls it (D162)
    REPEAT_SAVE,
    // A participant's resource pool used through the pool-use command, with what it cost from the turn (D174)
    POOL_USE,
    // Dash, Disengage, Dodge, Help, Hide, Ready, Search or Use an Object (D174)
    STANDARD_ACTION,
    // A participant's object interaction: the first each turn is free (D174)
    OBJECT_INTERACTION,
    // The DM's edit of a participant's turn economy, with what changed (D174)
    TURN_ECONOMY_SET
}
