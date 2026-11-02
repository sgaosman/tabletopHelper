package com.tabletophelper.rules.combat;

/** What an Effect comes to against one target (D160). */
public enum Outcome {

    /** An attack roll that hits. */
    HIT,

    /** An attack roll that misses. */
    MISS,

    /** An attack roll that is a critical hit. */
    CRITICAL,

    /** A saving throw that succeeds. */
    SAVE_SUCCESS,

    /** A saving throw that fails, rolled or automatically. */
    SAVE_FAIL,

    /** No roll: the Effect simply reaches the target. */
    AUTOMATIC
}
