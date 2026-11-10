package com.tabletophelper.encounter.state;

/**
 * Which spells a creature lets through a trait that keeps spells off it "unless it wishes to be" affected, as a
 * rakshasa's Limited Magic Immunity does for spells of 6th level or lower (MM p.257, D198). Whoever controls the
 * creature sets it: the DM, or the player a spell gave control to.
 */
public enum SpellConsent {
    /** It is never affected, as the trait reads without a choice. */
    NEVER,
    /** It lets through spells that don't harm it, such as Heal and Bless, and keeps out the rest, such as Fireball. */
    HELPFUL,
    /** It lets every spell through. */
    ALWAYS
}
