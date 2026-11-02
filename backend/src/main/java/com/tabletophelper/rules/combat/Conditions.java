package com.tabletophelper.rules.combat;

import java.util.Collection;

/** Condition names as participants store them, matched ignoring case and surrounding spaces. */
final class Conditions {

    static final String UNCONSCIOUS = "unconscious";
    static final String PARALYZED = "paralyzed";
    static final String STUNNED = "stunned";
    static final String PETRIFIED = "petrified";

    private Conditions() {}

    static boolean has(Collection<String> conditions, String name) {
        return conditions.stream().anyMatch(condition -> condition != null && condition.trim().equalsIgnoreCase(name));
    }
}
