package com.tabletophelper.encounter.state;

/**
 * What pays for a cast spell (D165): a spell slot, a pact slot, an innate use such as 1/day, or nothing, for
 * cantrips and at-will spells. Every levelled spell that isn't at will needs one of the first three.
 */
public enum SpellSource {
    SLOT,
    PACT_SLOT,
    INNATE,
    NONE
}
