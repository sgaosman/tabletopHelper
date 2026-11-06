package com.tabletophelper.rules.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Attack rolls under the 2014 rules (D161): advantage and disadvantage, natural 1s and 20s, and attacks on
 * unconscious and paralyzed targets. Attacks never hit automatically.
 */
public final class AttackRules {

    /** How an attack is rolled against a target, and whether a hit on it is a critical hit. */
    public record Setup(RollMode rollMode, boolean hitIsCritical) {}

    private AttackRules() {}

    /**
     * Combines the attack's own sources of advantage and disadvantage with what the target's conditions add. An
     * unconscious or paralyzed target gives advantage, and a hit from within 5 feet is a critical hit. An
     * unconscious creature is also prone, which gives disadvantage from beyond 5 feet, so out there the two
     * cancel and it is attacked normally (PHB appendix A). A target taking the Dodge action gives disadvantage,
     * the attacker being assumed to see it (PHB p.192, {@link StandardActions#dodging}). The other conditions'
     * effects on attacks, prone on its own included, are left to the attack's own sources.
     */
    public static Setup setup(Collection<RollMode> sources, Collection<String> targetConditions, boolean withinFiveFeet) {
        boolean unconscious = Conditions.has(targetConditions, Conditions.UNCONSCIOUS);
        boolean downed = unconscious || Conditions.has(targetConditions, Conditions.PARALYZED);

        List<RollMode> all = new ArrayList<>(sources);
        if (downed) all.add(RollMode.ADVANTAGE);
        if (unconscious && !withinFiveFeet) all.add(RollMode.DISADVANTAGE);
        if (StandardActions.dodging(targetConditions)) all.add(RollMode.DISADVANTAGE);
        return new Setup(RollMode.combine(all), downed && withinFiveFeet);
    }

    /**
     * Whether an attack hits, from the d20 as rolled. A 1 misses and a 20 is a critical hit, whatever the bonus
     * and AC (PHB p.194); otherwise the total must reach the target's AC, and the hit is a critical hit when
     * hitIsCritical says so.
     */
    public static Outcome outcome(int d20, int attackBonus, int armourClass, boolean hitIsCritical) {
        if (d20 < 1 || d20 > 20) {
            throw new IllegalArgumentException("A d20 rolls 1 to 20, not " + d20);
        }
        if (d20 == 1) return Outcome.MISS;
        if (d20 == 20) return Outcome.CRITICAL;
        if (d20 + attackBonus < armourClass) return Outcome.MISS;
        return hitIsCritical ? Outcome.CRITICAL : Outcome.HIT;
    }
}
