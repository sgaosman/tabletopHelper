package com.tabletophelper.rules.combat;

/** Whether a creature is up, down at 0 HP, or dead (D161). */
public enum LifeState {

    /** Above 0 HP. */
    ALIVE,

    /** A PC at 0 HP, unconscious and making death saving throws. */
    DYING,

    /** A PC at 0 HP and unconscious, but no longer making death saving throws. */
    STABLE,

    /** Failed three death saves, killed outright by massive damage, or a monster that dropped to 0 HP. */
    DEAD
}
