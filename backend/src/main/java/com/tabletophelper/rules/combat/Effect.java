package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a weapon attack, spell or monster action does (D160): how it is delivered, its damage parts and their
 * types, any healing, the conditions it applies, what a successful save leaves, and whether it needs
 * concentration and for how long. Its targets make one saving throw at most: its delivery's, or the one an
 * attack's hit calls for against some of its conditions. The encounter module adapts spell and monster
 * templates into Effects, and {@link Resolver} resolves them against their targets.
 *
 * @param name           what the combat log calls it, such as "Fireball" or "Bite"
 * @param delivery       how it reaches its targets
 * @param damage         its damage parts, all dealt together, each with its own type
 * @param healing        what it heals, or null when it heals nothing
 * @param conditions     the conditions it gives a target it hits, that fails its save, or that it reaches
 *                       automatically; a target it hits gets those its attack's hit save guards only on a
 *                       failed save
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

    /**
     * The ability of the saving throw its targets make: its delivery's, or the one its attack's hit calls for;
     * null when they make none, or the data doesn't say.
     */
    public Ability saveAbility() {
        return switch (delivery) {
            case Save save -> save.ability();
            case Attack attack -> attack.hitSave() != null ? attack.hitSave().ability() : null;
            case Automatic automatic -> null;
            case Self self -> null;
        };
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
     * @param hitSave        the saving throw a target it hits makes against some of the Effect's conditions, or
     *                       null when a hit gives them all
     */
    public record Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical,
                         HitSave hitSave) implements Delivery {

        public Attack {
            Objects.requireNonNull(rollMode, "rollMode");
        }

        /** An attack whose hit gives every condition of its Effect. */
        public Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical) {
            this(bonus, rollMode, withinFiveFeet, forcedCritical, null);
        }
    }

    /**
     * A saving throw a target makes when an attack hits it, against the conditions it names: the target gets them
     * only on a failure, as a dire wolf's bite knocks a creature prone only on a failed DC 13 Strength save (MM).
     * The hit's damage stands either way.
     *
     * @param conditions the Effect's conditions it guards; the others come with the hit
     */
    public record HitSave(Ability ability, int dc, List<String> conditions) {

        public HitSave {
            Objects.requireNonNull(ability, "ability");
            conditions = List.copyOf(conditions);
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
     * @param escapeDc       the DC of the check that escapes it, as a grapple's escape DC, which the combat log
     *                       shows; never a saving throw, so nothing rolls against it; null for none
     */
    public record ConditionEnd(Integer durationRounds, RepeatSave repeatSave, Integer escapeDc) {

        /** An end with no escape DC. */
        public ConditionEnd(Integer durationRounds, RepeatSave repeatSave) {
            this(durationRounds, repeatSave, null);
        }
    }

    /** A saving throw repeated to end a condition: the ability and the DC. */
    public record RepeatSave(Ability ability, int dc) {

        public RepeatSave {
            Objects.requireNonNull(ability, "ability");
        }
    }
}
