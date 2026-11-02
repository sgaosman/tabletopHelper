package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;

import java.util.Collection;

/** Saving throws under the 2014 rules (D161). */
public final class SaveRules {

    private SaveRules() {}

    /**
     * Whether the creature fails this saving throw without rolling: Strength and Dexterity saves fail
     * automatically while it is unconscious, paralyzed, stunned or petrified (PHB appendix A).
     */
    public static boolean autoFails(Ability ability, Collection<String> conditions) {
        if (ability != Ability.STR && ability != Ability.DEX) return false;
        return Conditions.has(conditions, Conditions.UNCONSCIOUS)
                || Conditions.has(conditions, Conditions.PARALYZED)
                || Conditions.has(conditions, Conditions.STUNNED)
                || Conditions.has(conditions, Conditions.PETRIFIED);
    }
}
