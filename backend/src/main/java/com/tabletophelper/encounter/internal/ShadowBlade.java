package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.WeaponRules;

import java.util.Locale;
import java.util.Optional;

/**
 * Shadow Blade (XGE p.164, D197): while its caster keeps the spell, it holds a simple melee weapon of solidified gloom
 * that it is proficient with, finesse, light and thrown (20/60), dealing 2d8 psychic damage on a hit, 3d8 from a
 * 3rd- or 4th-level slot, 4d8 from 5th or 6th and 5d8 from 7th or higher. It attacks with it like any weapon, with its
 * Strength or Dexterity and its proficiency bonus.
 */
final class ShadowBlade {

    /** The spell's and the weapon's name. */
    static final String NAME = "Shadow Blade";

    private ShadowBlade() {
    }

    /** The blade's damage dice from a slot of slotLevel. */
    static Dice damage(int slotLevel) {
        int dice = slotLevel >= 7 ? 5 : slotLevel >= 5 ? 4 : slotLevel >= 3 ? 3 : 2;
        return new Dice(dice, 8, 0);
    }

    /** The blade cast with a slot of slotLevel. */
    static WeaponRules.Weapon weapon(int slotLevel) {
        return new WeaponRules.Weapon(NAME, WeaponRules.Category.SIMPLE, false, true, false, true, damage(slotLevel),
                "psychic", false, false, null);
    }

    /**
     * The blade the participant holds while it concentrates on its Shadow Blade, proficient; empty when it has none.
     */
    static Optional<CharacterWeapons.Held> held(EncounterParticipant participant) {
        ConcentrationInstance held = participant != null ? participant.getConcentration() : null;
        if (held == null || !NAME.equalsIgnoreCase(held.spell())) {
            return Optional.empty();
        }
        return Optional.of(new CharacterWeapons.Held(weapon(held.slotLevel() != null ? held.slotLevel() : 2), true,
                null));
    }

    /** Whether the weapon asked for is the blade. */
    static boolean named(String weaponName) {
        return weaponName != null && NAME.toLowerCase(Locale.ROOT).equals(weaponName.trim().toLowerCase(Locale.ROOT));
    }
}
