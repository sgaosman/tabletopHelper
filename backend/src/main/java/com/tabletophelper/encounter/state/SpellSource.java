package com.tabletophelper.encounter.state;

/**
 * What pays for a cast spell (D165): a spell slot, a pact slot, an innate use such as 1/day, a warlock's Mystic
 * Arcanum use (PHB p.108), nothing, for cantrips and at-will spells, a magic item's charges, as a Wand of Magic
 * Missiles' (D210), or a spell scroll, used up as it is read (DMG p.200, D210). Every levelled spell that isn't at will
 * needs one of the others.
 */
public enum SpellSource {
    SLOT,
    PACT_SLOT,
    INNATE,
    MYSTIC_ARCANUM,
    NONE,
    ITEM,
    SCROLL
}
