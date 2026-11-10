package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Exhaustion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Attack rolls under the 2014 rules (D161): advantage and disadvantage, natural 1s and 20s, and what the attacker's
 * and the target's conditions do to an attack roll (PHB appendix A, p.290-292). Attacks never hit automatically.
 */
public final class AttackRules {

    /** The d20 that is a critical hit for every attacker (PHB p.194). */
    public static final int CRITICAL_D20 = 20;

    /**
     * How an attack is rolled against a target, and whether a hit on it is a critical hit.
     *
     * @param automatic the sources of advantage and disadvantage the attacker's and the target's conditions gave it,
     *                  each with its reason, so the log and the attack form can name them; the attack's own sources,
     *                  such as the attacker's choice, aren't among them
     */
    public record Setup(RollMode rollMode, boolean hitIsCritical, List<Source> automatic) {

        public Setup {
            Objects.requireNonNull(rollMode, "rollMode");
            automatic = automatic != null ? List.copyOf(automatic) : List.of();
        }
    }

    /** One source of advantage or disadvantage the rules add, with its reason, such as "target restrained". */
    public record Source(RollMode mode, String reason) {

        public Source {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The special senses that let a creature perceive what it can't see (MM p.8-9). Blindsight perceives without
     * sight, so it works while blinded and finds invisible creatures; truesight sees invisible creatures, but it is
     * sight, so a blinded creature loses it. Their ranges aren't checked: there is no map, so the other creature is
     * taken to be within range.
     *
     * @param seesInvisible it sees invisible creatures as if they were visible, as See Invisibility gives (PHB p.274,
     *                      D199): sight, as truesight is, but nothing more, so it doesn't see through Blur (PHB p.219)
     */
    public record Senses(boolean blindsight, boolean truesight, boolean seesInvisible) {

        public static final Senses NONE = new Senses(false, false);

        /** Senses with nothing that sees only invisible creatures. */
        public Senses(boolean blindsight, boolean truesight) {
            this(blindsight, truesight, false);
        }

        /** The senses a stat block's senses text names, such as "blindsight 30 ft., darkvision 60 ft.". */
        public static Senses of(String sensesText) {
            if (sensesText == null || sensesText.isBlank()) {
                return NONE;
            }
            String text = sensesText.toLowerCase(Locale.ROOT);
            return new Senses(text.contains("blindsight"), text.contains("truesight"));
        }
    }

    /** One creature's side of an attack roll: the names of its conditions and its senses. */
    public record Side(List<String> conditions, Senses senses) {

        public static final Side NONE = new Side(List.of(), Senses.NONE);

        public Side {
            conditions = conditions != null ? Collections.unmodifiableList(new ArrayList<>(conditions)) : List.of();
            senses = senses != null ? senses : Senses.NONE;
        }

        /** A creature with these conditions and no special senses. */
        public static Side of(Collection<String> conditions) {
            return new Side(conditions != null ? new ArrayList<>(conditions) : List.of(), Senses.NONE);
        }

        boolean has(String condition) {
            return Conditions.has(conditions, condition);
        }

        /**
         * The same side without invisibility, for a creature that can't benefit from being invisible, as Faerie Fire's
         * targets can't (PHB p.239, D184).
         */
        public Side revealed() {
            return new Side(conditions.stream()
                    .filter(condition -> condition == null || !condition.trim().equalsIgnoreCase(Conditions.INVISIBLE))
                    .toList(), senses);
        }

        // Whether it perceives an invisible creature: blindsight works blinded, truesight only while it can see
        boolean perceivesInvisible() {
            return senses.blindsight() || ((senses.truesight() || senses.seesInvisible())
                    && !has(Conditions.BLINDED));
        }

        // Whether it can see or otherwise perceive the other creature (PHB p.194, MM p.8-9)
        boolean perceives(Side other) {
            boolean sightless = has(Conditions.BLINDED) && !senses.blindsight();
            boolean unseen = other.has(Conditions.INVISIBLE) && !perceivesInvisible();
            return !sightless && !unseen;
        }
    }

    // The target conditions that give attack rolls against it advantage (PHB appendix A), blinded and prone apart
    private static final List<String> EXPOSING = List.of(Conditions.PARALYZED, Conditions.PETRIFIED,
            Conditions.RESTRAINED, Conditions.STUNNED, Conditions.UNCONSCIOUS);

    // The attacker conditions that give its attack rolls disadvantage (PHB appendix A), blinded apart
    private static final List<String> HAMPERING = List.of(Conditions.POISONED, Conditions.PRONE,
            Conditions.RESTRAINED);

    private AttackRules() {}

    /**
     * As {@link #setup(Collection, Side, Side, boolean)}, for an attacker with no conditions and a target with no
     * special senses.
     */
    public static Setup setup(Collection<RollMode> sources, Collection<String> targetConditions, boolean withinFiveFeet) {
        return setup(sources, Side.NONE, Side.of(targetConditions), withinFiveFeet);
    }

    /**
     * Combines the attack's own sources of advantage and disadvantage with what the attacker's and the target's
     * conditions add (PHB appendix A, p.290-292); any advantage and any disadvantage cancel (PHB p.173).
     * <ul>
     *   <li>The attacker: blinded, poisoned, prone or restrained gives disadvantage; invisible gives advantage; an
     *       exhaustion level of 3 or more gives disadvantage (PHB p.291, {@link Exhaustion}).</li>
     *   <li>The target: blinded, paralyzed, petrified, restrained, stunned or unconscious gives advantage; invisible
     *       gives disadvantage. Prone gives advantage from within 5 feet and disadvantage from beyond, and an
     *       unconscious creature is prone too, counted once (PHB p.292), so beyond 5 feet an unconscious target is
     *       attacked normally.</li>
     *   <li>Perceiving: blindness and invisibility matter because a creature can't see the other (PHB p.194). A
     *       creature with blindsight ignores its own blindness, and one with blindsight or truesight (while it can see)
     *       ignores the other's invisibility (MM p.8-9), so that source isn't added.</li>
     *   <li>A target taking the Dodge action gives disadvantage while it can perceive the attacker (PHB p.192,
     *       {@link StandardActions#dodging}); one that attacked recklessly gives advantage until its next turn (PHB
     *       p.48, {@link ClassFeatures#reckless}).</li>
     * </ul>
     * A hit on an unconscious or paralyzed target from within 5 feet is a critical hit. Frightened isn't read here: its
     * disadvantage depends on whether the source of the fear is in sight ({@link Fear}), so the encounter adds it as a
     * named source, "attacker frightened (Adult Red Dragon in sight)" (PHB p.290, D201).
     */
    public static Setup setup(Collection<RollMode> sources, Side attacker, Side target, boolean withinFiveFeet) {
        return setup(sources, attacker, target, withinFiveFeet, List.of());
    }

    /**
     * As {@link #setup(Collection, Side, Side, boolean)}, with the named sources the creatures' Ongoing Modifiers add
     * (D184), such as Faerie Fire's advantage on attacks against its target (PHB p.239); they are listed with the
     * conditions' and combine with them once (PHB p.173).
     */
    public static Setup setup(Collection<RollMode> sources, Side attacker, Side target, boolean withinFiveFeet,
                              List<Source> modifierSources) {
        attacker = attacker != null ? attacker : Side.NONE;
        target = target != null ? target : Side.NONE;
        List<Source> automatic = new ArrayList<>();

        // The attacker's conditions
        if (attacker.has(Conditions.BLINDED) && !attacker.senses().blindsight()) {
            automatic.add(new Source(RollMode.DISADVANTAGE, "attacker blinded"));
        }
        for (String condition : HAMPERING) {
            if (attacker.has(condition)) automatic.add(new Source(RollMode.DISADVANTAGE, "attacker " + condition));
        }
        if (attacker.has(Conditions.INVISIBLE) && !target.perceivesInvisible()) {
            automatic.add(new Source(RollMode.ADVANTAGE, "attacker invisible"));
        }
        int exhaustion = Exhaustion.level(attacker.conditions());
        if (Exhaustion.attackDisadvantage(exhaustion)) {
            automatic.add(new Source(RollMode.DISADVANTAGE, "attacker " + Exhaustion.conditionName(exhaustion)));
        }

        // The target's conditions
        if (target.has(Conditions.BLINDED) && !target.senses().blindsight()) {
            automatic.add(new Source(RollMode.ADVANTAGE, "target blinded"));
        }
        for (String condition : EXPOSING) {
            if (target.has(condition)) automatic.add(new Source(RollMode.ADVANTAGE, "target " + condition));
        }
        boolean unconscious = target.has(Conditions.UNCONSCIOUS);
        if (target.has(Conditions.PRONE) || unconscious) {
            automatic.add(withinFiveFeet ? new Source(RollMode.ADVANTAGE, "target prone within 5 ft")
                    : new Source(RollMode.DISADVANTAGE, "target prone beyond 5 ft"));
        }
        if (target.has(Conditions.INVISIBLE) && !attacker.perceivesInvisible()) {
            automatic.add(new Source(RollMode.DISADVANTAGE, "target invisible"));
        }
        if (StandardActions.dodging(target.conditions()) && target.perceives(attacker)) {
            automatic.add(new Source(RollMode.DISADVANTAGE, "target dodging"));
        }
        if (ClassFeatures.reckless(target.conditions())) {
            automatic.add(new Source(RollMode.ADVANTAGE, "target attacked recklessly"));
        }
        if (modifierSources != null) {
            automatic.addAll(modifierSources);
        }

        List<RollMode> all = new ArrayList<>(sources);
        automatic.forEach(source -> all.add(source.mode()));
        boolean downed = unconscious || target.has(Conditions.PARALYZED);
        return new Setup(RollMode.combine(all), downed && withinFiveFeet, automatic);
    }

    /**
     * The automatic sources of {@link #setup} as a note, such as "advantage: target restrained" or "advantage: target
     * restrained; disadvantage: attacker poisoned"; empty for none.
     */
    public static String describe(List<Source> automatic) {
        List<String> parts = new ArrayList<>();
        for (RollMode mode : List.of(RollMode.ADVANTAGE, RollMode.DISADVANTAGE)) {
            List<String> reasons = automatic.stream()
                    .filter(source -> source.mode() == mode)
                    .map(Source::reason)
                    .toList();
            if (!reasons.isEmpty()) {
                parts.add(mode.name().toLowerCase(Locale.ROOT) + ": " + String.join(", ", reasons));
            }
        }
        return String.join("; ", parts);
    }

    /**
     * Whether an attack hits, from the d20 as rolled. A 1 misses and a 20 is a critical hit, whatever the bonus
     * and AC (PHB p.194); otherwise the total must reach the target's AC, and the hit is a critical hit when
     * hitIsCritical says so.
     */
    public static Outcome outcome(int d20, int attackBonus, int armourClass, boolean hitIsCritical) {
        return outcome(d20, attackBonus, armourClass, hitIsCritical, CRITICAL_D20);
    }

    /**
     * As {@link #outcome(int, int, int, boolean)}, for an attacker who scores a critical hit on a d20 of critRange or
     * more, as a Champion does on 19 or 20 (PHB p.72). Like a 20, such a roll hits whatever the total.
     */
    public static Outcome outcome(int d20, int attackBonus, int armourClass, boolean hitIsCritical, int critRange) {
        if (d20 < 1 || d20 > 20) {
            throw new IllegalArgumentException("A d20 rolls 1 to 20, not " + d20);
        }
        if (d20 == 1) return Outcome.MISS;
        if (d20 >= Math.min(CRITICAL_D20, Math.max(2, critRange))) return Outcome.CRITICAL;
        if (d20 + attackBonus < armourClass) return Outcome.MISS;
        return hitIsCritical ? Outcome.CRITICAL : Outcome.HIT;
    }
}
