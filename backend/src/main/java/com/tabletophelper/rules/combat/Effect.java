package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a weapon attack, spell or monster action does (D160): how it is delivered, its damage parts and their
 * types, any healing, the conditions it applies, what a successful save leaves, and whether it needs
 * concentration and for how long. The encounter module adapts spell and monster templates into Effects, and
 * {@link Resolver} resolves them against their targets.
 *
 * @param name           what the combat log calls it, such as "Fireball" or "Bite"
 * @param delivery       how it reaches its targets
 * @param damage         its damage parts, all dealt together, each with its own type
 * @param healing        what it heals, or null when it heals nothing
 * @param conditions     the conditions it gives a target it hits, that fails its save, or that it reaches
 *                       automatically
 * @param concentration  whether keeping it going needs concentration
 * @param durationRounds how many rounds it lasts; null when instantaneous or unknown
 * @param conditionEnds  how a condition it applies ends, by condition name, when its data says (D162); a
 *                       condition missing here lasts as long as the Effect
 */
public record Effect(String name, Delivery delivery, List<DamagePart> damage, Dice healing,
                     List<String> conditions, boolean concentration, Integer durationRounds,
                     Map<String, ConditionEnd> conditionEnds) {

    public Effect {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(delivery, "delivery");
        damage = List.copyOf(damage);
        conditions = List.copyOf(conditions);
        conditionEnds = Map.copyOf(conditionEnds);
    }

    /** An Effect whose conditions all last as long as it does. */
    public Effect(String name, Delivery delivery, List<DamagePart> damage, Dice healing, List<String> conditions,
                  boolean concentration, Integer durationRounds) {
        this(name, delivery, damage, healing, conditions, concentration, durationRounds, Map.of());
    }

    /** How an Effect reaches its targets: an attack roll, a saving throw, automatically, or only its user. */
    public sealed interface Delivery permits Attack, Save, Automatic, Self {
    }

    /**
     * An attack roll against each target's AC.
     *
     * @param bonus          the attack bonus
     * @param rollMode       the attacker's own advantage or disadvantage; each target's conditions add theirs
     * @param withinFiveFeet whether the attacker is within 5 feet of the target, which decides whether a hit
     *                       on an unconscious or paralyzed target is a critical hit (D161)
     * @param forcedCritical the DM's override: the attack is a critical hit whatever the roll
     */
    public record Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical)
            implements Delivery {

        public Attack {
            Objects.requireNonNull(rollMode, "rollMode");
        }
    }

    /**
     * A saving throw by each target. A success prevents the conditions, and halves the damage or prevents it.
     *
     * @param ability             the save's ability; null when the data doesn't say, which no condition fails
     *                            automatically
     * @param dc                  the save's DC
     * @param halfDamageOnSuccess whether a success halves the damage; otherwise it prevents all of it
     */
    public record Save(Ability ability, int dc, boolean halfDamageOnSuccess) implements Delivery {
    }

    /** No roll: the Effect reaches each target. */
    public record Automatic() implements Delivery {
    }

    /** No roll: the Effect reaches only the creature using it. */
    public record Self() implements Delivery {
    }

    /** Damage of one type. type is null for untyped damage, which no defence changes. */
    public record DamagePart(Dice dice, String type) {

        public DamagePart {
            Objects.requireNonNull(dice, "dice");
        }
    }

    /**
     * How a condition an Effect applies ends (D162).
     *
     * @param durationRounds how many rounds the condition lasts; null for as long as the Effect
     * @param repeatSave     the save its creature repeats at the end of each of its turns to end it, or null
     */
    public record ConditionEnd(Integer durationRounds, RepeatSave repeatSave) {
    }

    /** A saving throw repeated to end a condition: the ability and the DC. */
    public record RepeatSave(Ability ability, int dc) {

        public RepeatSave {
            Objects.requireNonNull(ability, "ability");
        }
    }
}
