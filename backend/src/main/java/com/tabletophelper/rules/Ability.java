package com.tabletophelper.rules;

import java.util.Optional;

/** The six abilities, by their usual abbreviations. */
public enum Ability {
    STR("strength"), DEX("dexterity"), CON("constitution"), INT("intelligence"), WIS("wisdom"), CHA("charisma");

    private final String fullName;

    Ability(String fullName) {
        this.fullName = fullName;
    }

    /** The ability's full name in lower case, such as "dexterity". */
    public String fullName() {
        return fullName;
    }

    /**
     * The ability a name stands for, given as the abbreviation or the full name in any case, such as "DEX",
     * "dex" or "Dexterity", with surrounding spaces ignored. Empty for anything else, null included.
     */
    public static Optional<Ability> parse(String name) {
        if (name == null) return Optional.empty();
        String trimmed = name.trim();
        for (Ability ability : values()) {
            if (ability.name().equalsIgnoreCase(trimmed) || ability.fullName.equalsIgnoreCase(trimmed)) {
                return Optional.of(ability);
            }
        }
        return Optional.empty();
    }
}
