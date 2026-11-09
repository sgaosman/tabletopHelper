package com.tabletophelper.character;

import com.tabletophelper.character.state.LevelHistoryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Way of the Kensei monk's kensei weapons (XGE p.34): the weapon types chosen at Monk 3, one melee and one ranged,
 * and one more at Monk 6, 11 and 17, each recorded on the level that chose it, so levelling down takes it away. "Weapons
 * of the chosen types are monk weapons for you", so Martial Arts covers them (PHB p.78).
 */
public final class KenseiWeapons {

    private KenseiWeapons() {
    }

    /** The character's kensei weapons, as its levels record them, oldest first, each once whatever its case. */
    public static List<String> of(PlayerCharacter character) {
        List<String> weapons = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        List<LevelHistoryEntry> history = character.getLevelHistory();
        for (LevelHistoryEntry level : history != null ? history : List.<LevelHistoryEntry>of()) {
            if (level == null || level.choices() == null || level.choices().kenseiWeapons() == null) continue;
            for (String weapon : level.choices().kenseiWeapons()) {
                if (weapon == null || weapon.isBlank()) continue;
                String key = weapon.trim().toLowerCase(Locale.ROOT);
                if (!seen.contains(key)) {
                    seen.add(key);
                    weapons.add(weapon.trim());
                }
            }
        }
        return weapons;
    }
}
