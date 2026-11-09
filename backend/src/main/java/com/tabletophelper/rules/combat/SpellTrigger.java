package com.tabletophelper.rules.combat;

/**
 * When a lasting spell's area acts on a creature by itself, with no action from its caster (M15): Moonbeam and Spirit
 * Guardians when a creature enters the area for the first time on a turn or starts its turn there (PHB p.261, p.278),
 * Flaming Sphere when one ends its turn beside the sphere (PHB p.242), Hunger of Hadar at both ends of a turn
 * (PHB p.251). There is no map, so the DM applies each one to the creature it happens to.
 */
public enum SpellTrigger {

    /** A creature enters the area for the first time on a turn. */
    ENTER,

    /** A creature starts its turn in the area, or under the spell. */
    START_OF_TURN,

    /** A creature ends its turn in the area, or near the effect. */
    END_OF_TURN
}
