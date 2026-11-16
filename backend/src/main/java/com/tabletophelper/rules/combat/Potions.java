package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The potions of healing (DMG p.187, PHB p.153): drinking one, or giving it to another creature, takes an action, and
 * the drinker regains the potion's hit points.
 */
public final class Potions {

    private static final Map<String, Dice> HEALING = Map.of(
            "potion of healing", new Dice(2, 4, 2),
            "potion of greater healing", new Dice(4, 4, 4),
            "potion of superior healing", new Dice(8, 4, 8),
            "potion of supreme healing", new Dice(10, 4, 20));

    private Potions() {
    }

    /** The hit points a potion of healing restores, by its name in any case; empty for any other item. */
    public static Optional<Dice> healing(String itemName) {
        if (itemName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(HEALING.get(itemName.trim().toLowerCase(Locale.ROOT)));
    }
}
