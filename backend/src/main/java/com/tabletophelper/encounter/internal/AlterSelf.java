package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.WeaponRules;

import java.util.Locale;
import java.util.Optional;

/**
 * Alter Self's Natural Weapons (PHB p.211, D199): while the spell lasts with that option, its caster grows claws,
 * fangs, spines, horns or another natural weapon of its choice, chosen with the option's damage type. Its unarmed strikes
 * with it deal 1d6 damage of that type, it is proficient with them, and the weapon is magic, with a +1 bonus to its
 * attack and damage rolls. It attacks with it as with any weapon, with its Strength. The weapon heads the caster's attack
 * options, as Shadow Blade's does (D197).
 */
final class AlterSelf {

    /** The spell's name. */
    static final String SPELL = "Alter Self";

    /** The weapon's name in the attack options. */
    static final String WEAPON = "Natural Weapon";

    /** The option's words before its damage type, as "natural weapons (slashing)". */
    static final String OPTION = "natural weapons";

    /** The magic bonus to the weapon's attack and damage rolls. */
    static final int BONUS = 1;

    private AlterSelf() {
    }

    /** The natural weapon of a damage type: 1d6, the +1 added by {@link #BONUS} where the attack is rolled. */
    static WeaponRules.Weapon weapon(String damageType) {
        return new WeaponRules.Weapon(WEAPON, WeaponRules.Category.SIMPLE, false, false, false, false,
                new Dice(1, 6, 0), damageType);
    }

    /** The damage type an option of the spell names, as "slashing" for "natural weapons (slashing)"; empty for others. */
    static Optional<String> damageType(String option) {
        if (option == null) {
            return Optional.empty();
        }
        String text = option.trim().toLowerCase(Locale.ROOT);
        if (!text.startsWith(OPTION)) {
            return Optional.empty();
        }
        int open = text.indexOf('(');
        int close = text.indexOf(')');
        return Optional.of(open >= 0 && close > open ? text.substring(open + 1, close).trim() : "bludgeoning");
    }

    /** The natural weapon the participant has while its Alter Self with the option lasts, proficient; empty for none. */
    static Optional<CharacterWeapons.Held> held(EncounterParticipant participant) {
        if (participant == null) {
            return Optional.empty();
        }
        return EffectApplier.effects(participant).stream()
                .filter(effect -> SPELL.equalsIgnoreCase(effect.spell()))
                .map(SpellEffectEntry::option)
                .map(AlterSelf::damageType)
                .flatMap(Optional::stream)
                .findFirst()
                .map(type -> new CharacterWeapons.Held(weapon(type), true, null));
    }

    /** Whether the weapon asked for is the natural weapon. */
    static boolean named(String weaponName) {
        return weaponName != null && WEAPON.toLowerCase(Locale.ROOT).equals(weaponName.trim().toLowerCase(Locale.ROOT));
    }

    /** The magic bonus to attack and damage the weapon has: +1 for the natural weapon held, else 0. */
    static int bonus(EncounterParticipant attacker, String weaponName) {
        return named(weaponName) && held(attacker).isPresent() ? BONUS : 0;
    }
}
