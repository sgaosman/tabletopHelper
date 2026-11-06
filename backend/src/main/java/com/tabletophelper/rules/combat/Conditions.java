package com.tabletophelper.rules.combat;

import java.util.Collection;
import java.util.List;

/** Condition names as participants store them, matched ignoring case and surrounding spaces. */
final class Conditions {

    static final String UNCONSCIOUS = "unconscious";
    static final String PARALYZED = "paralyzed";
    static final String STUNNED = "stunned";
    static final String PETRIFIED = "petrified";
    static final String INCAPACITATED = "incapacitated";
    static final String GRAPPLED = "grappled";
    static final String RESTRAINED = "restrained";

    // The conditions that include being incapacitated (PHB appendix A)
    static final List<String> INCAPACITATING = List.of(INCAPACITATED, PARALYZED, PETRIFIED, STUNNED, UNCONSCIOUS);

    private Conditions() {}

    static boolean has(Collection<String> conditions, String name) {
        return conditions.stream().anyMatch(condition -> condition != null && condition.trim().equalsIgnoreCase(name));
    }
}
