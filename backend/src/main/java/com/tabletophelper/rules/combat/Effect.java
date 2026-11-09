package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.ArrayList;
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
     * @param otherSources   the attacker's other sources of advantage or disadvantage, such as armour worn without
     *                       proficiency (PHB p.144); they combine with rollMode and the target's once (PHB p.173)
     * @param critRange      the lowest d20 that is a critical hit: 20, or 19 with the Champion's Improved Critical
     *                       (PHB p.72) and 18 with its Superior Critical (PHB p.73)
     */
    public record Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical,
                         HitSave hitSave, List<RollMode> otherSources, int critRange) implements Delivery {

        public Attack {
            Objects.requireNonNull(rollMode, "rollMode");
            otherSources = List.copyOf(otherSources);
            if (critRange < 2 || critRange > 20) {
                throw new IllegalArgumentException("A critical hit needs a d20 of 2 to 20, not " + critRange);
            }
        }

        /** An attack that is a critical hit only on a 20. */
        public Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical, HitSave hitSave,
                      List<RollMode> otherSources) {
            this(bonus, rollMode, withinFiveFeet, forcedCritical, hitSave, otherSources, AttackRules.CRITICAL_D20);
        }

        /** An attack whose only source of its own is rollMode. */
        public Attack(int bonus, RollMode rollMode, boolean withinFiveFeet, boolean forcedCritical, HitSave hitSave) {
            this(bonus, rollMode, withinFiveFeet, forcedCritical, hitSave, List.of());
        }

        /** Every source of advantage or disadvantage the attacker brings: its own choice, then the others. */
        public List<RollMode> attackerSources() {
            List<RollMode> sources = new ArrayList<>(List.of(rollMode));
            sources.addAll(otherSources);
            return sources;
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

    /**
     * Damage of one type. type is null for untyped damage, which no defence changes.
     *
     * @param rerollUpTo a die that shows this or less is rolled once more and the new roll kept, as Great Weapon
     *                   Fighting does with 1s and 2s (PHB p.72); 0 rerolls nothing
     */
    public record DamagePart(Dice dice, String type, int rerollUpTo) {

        public DamagePart {
            Objects.requireNonNull(dice, "dice");
            if (rerollUpTo < 0) {
                throw new IllegalArgumentException("A reroll needs a die face of 0 or more, not " + rerollUpTo);
            }
        }

        /** Damage whose dice are never rolled again. */
        public DamagePart(Dice dice, String type) {
            this(dice, type, 0);
        }
    }

    /**
     * How a condition an Effect applies ends (D162).
     *
     * @param durationRounds how many rounds the condition lasts; null for as long as the Effect
     * @param repeatSave     the save its creature repeats at the end of each of its turns to end it, or null
     * @param escapeDc       the DC of the check that escapes it, as a grapple's escape DC, which the combat log
     *                       shows; never a saving throw, so nothing rolls against it; null for none
     * @param endOfUsersTurn it ends at the end of a turn of the creature that used the Effect, once durationRounds
     *                       rounds have passed (at the next such end when null), not at the start of one, as
     *                       Stunning Strike's stun lasts until the end of the monk's next turn (PHB p.79)
     */
    public record ConditionEnd(Integer durationRounds, RepeatSave repeatSave, Integer escapeDc,
                               boolean endOfUsersTurn) {

        /** An end with an escape DC, at the start of a turn. */
        public ConditionEnd(Integer durationRounds, RepeatSave repeatSave, Integer escapeDc) {
            this(durationRounds, repeatSave, escapeDc, false);
        }

        /** An end with no escape DC, at the start of a turn. */
        public ConditionEnd(Integer durationRounds, RepeatSave repeatSave) {
            this(durationRounds, repeatSave, null, false);
        }
    }

    /** A saving throw repeated to end a condition: the ability and the DC. */
    public record RepeatSave(Ability ability, int dc) {

        public RepeatSave {
            Objects.requireNonNull(ability, "ability");
        }
    }
}
