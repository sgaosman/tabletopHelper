package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;

import java.util.Collection;
import java.util.List;

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

    /**
     * How the creature rolls this saving throw: Dexterity saves with advantage while it takes the Dodge action
     * (PHB p.192, {@link StandardActions#dodging}), Strength saves with advantage while it rages (PHB p.48,
     * {@link ClassFeatures#raging}), Dexterity saves with disadvantage while it is restrained (PHB p.292), every save
     * with disadvantage at exhaustion level 3 or more (PHB p.291, {@link Exhaustion}), and anything else normally.
     */
    public static RollMode rollMode(Ability ability, Collection<String> conditions) {
        return rollMode(ability, conditions, false);
    }

    /**
     * As {@link #rollMode(Ability, Collection)}, with disadvantage on Strength and Dexterity saves when
     * strDexDisadvantage is true, as for a creature wearing armour or a shield it isn't proficient with (PHB p.144), as
     * well as restrained's disadvantage on Dexterity saves.
     * Advantage and disadvantage cancel (PHB p.173).
     */
    public static RollMode rollMode(Ability ability, Collection<String> conditions, boolean strDexDisadvantage) {
        RollMode dodge = ability == Ability.DEX && StandardActions.dodging(conditions)
                ? RollMode.ADVANTAGE : RollMode.NORMAL;
        RollMode rage = ability == Ability.STR && ClassFeatures.raging(conditions)
                ? RollMode.ADVANTAGE : RollMode.NORMAL;
        RollMode armour = strDexDisadvantage && (ability == Ability.STR || ability == Ability.DEX)
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        RollMode restrained = ability == Ability.DEX && conditions != null
                && Conditions.has(conditions, Conditions.RESTRAINED)
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        RollMode exhausted = Exhaustion.saveDisadvantage(Exhaustion.level(conditions))
                ? RollMode.DISADVANTAGE : RollMode.NORMAL;
        return RollMode.combine(List.of(dodge, rage, armour, restrained, exhausted));
    }
}
