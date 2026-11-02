package com.tabletophelper.rules.combat;

import java.util.Collection;

/** How a d20 is rolled: once, or twice keeping the higher (advantage) or the lower (disadvantage). */
public enum RollMode {
    NORMAL, ADVANTAGE, DISADVANTAGE;

    /**
     * One roll's mode from all its sources. Advantage and disadvantage cancel, however many of each there are,
     * leaving a normal roll; otherwise any advantage gives advantage and any disadvantage gives disadvantage
     * (PHB p.173). NORMAL sources add nothing.
     */
    public static RollMode combine(Collection<RollMode> sources) {
        boolean advantage = sources.contains(ADVANTAGE);
        boolean disadvantage = sources.contains(DISADVANTAGE);
        if (advantage == disadvantage) return NORMAL;
        return advantage ? ADVANTAGE : DISADVANTAGE;
    }
}
