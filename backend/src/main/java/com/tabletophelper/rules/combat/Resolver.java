package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves Effects against their targets (D160): it rolls the attacks, saving throws, damage and healing and
 * returns each target's outcome, changing nothing. Its dice come from the {@link Rng} it is given, so tests
 * can script them, and it rolls them in a fixed order: an Effect with a saving throw or none rolls its damage
 * and healing once, shared by every target (D161), then each target's save; an attack rolls each target's
 * d20s and, on a hit, that hit's own damage, doubled on a critical hit (PHB p.196), then the target's save
 * against the conditions the hit calls for one against.
 */
public final class Resolver {

    private final Rng rng;

    public Resolver(Rng rng) {
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    /**
     * A creature an Effect is resolved against.
     *
     * @param armourClass  its AC, for attack rolls
     * @param saveModifier its modifier for the Effect's saving throw, when it has one ({@link Effect#saveAbility})
     * @param conditions   its conditions by name, which can change attack rolls and fail saves (D161)
     * @param strDexDisadvantage whether it makes Strength and Dexterity saves with disadvantage, as it does wearing
     *                     armour or a shield it isn't proficient with (PHB p.144)
     * @param saveSources  its other sources of advantage or disadvantage on the Effect's save, such as a vampire
     *                     spawn's Turn Resistance against Turn Undead (M17); they combine with its conditions' once
     * @param inspirationSides the sides of a Bardic Inspiration die it rolls and adds when it fails the Effect's save
     *                     (PHB p.53, M17); 0 for none
     */
    public record Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                         boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides) {

        public Target {
            Objects.requireNonNull(id, "id");
            conditions = List.copyOf(conditions);
            saveSources = saveSources != null ? List.copyOf(saveSources) : List.of();
        }

        /** A target with no other sources on its save and no die to add. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, List.of(), 0);
        }

        /** The same target with another source of advantage or disadvantage on the Effect's save. */
        public Target withSaveSource(RollMode source) {
            List<RollMode> sources = new ArrayList<>(saveSources);
            sources.add(source);
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, sources,
                    inspirationSides);
        }

        /** A target whose saves nothing but its conditions changes. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions) {
            this(id, armourClass, saveModifier, conditions, false);
        }
    }

    /** Damage of one type; type is null when untyped. */
    public record Damage(String type, int amount) {
    }

    /** A Bardic Inspiration die rolled and added to an attack roll or a save (PHB p.53): its sides and its roll. */
    public record Inspiration(int sides, int roll) {
    }

    /**
     * What an Effect comes to against one target.
     *
     * @param d20s          every d20 rolled for the attack or save: two with advantage or disadvantage, none
     *                      when nothing was rolled
     * @param d20           the d20 that counts, or null when none was rolled
     * @param total         that d20 plus the attack bonus or save modifier, or null when none was rolled
     * @param rollMode      how the attack or save was rolled once the target's conditions count; NORMAL otherwise
     * @param autoFailed    the target failed the Effect's save without rolling
     * @param damage        the damage it takes by type, before its own defences: after a successful save, half
     *                      of it or none
     * @param successDamage on a failed save, the damage a successful one would have left, kept so Legendary
     *                      Resistance can apply that outcome instead (D163): after a hit's save, all of it;
     *                      empty for any other outcome
     * @param healing       the hit points it regains
     * @param conditions    the conditions it gets
     * @param hitSave       the save it made, once an attack hit it, against the conditions the hit's save guards
     *                      ({@link Effect.HitSave}); null when it made none
     * @param inspiration   the Bardic Inspiration die added to the attack roll or the save, already in total; null
     *                      when none was rolled
     * @param guidedStrike  Guided Strike's +10 was added to the attack roll, already in total (PHB p.63)
     */
    public record TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                                RollMode rollMode, boolean autoFailed, List<Damage> damage,
                                List<Damage> successDamage, int healing, List<String> conditions,
                                SaveRoll hitSave, Inspiration inspiration, boolean guidedStrike) {

        public TargetOutcome {
            d20s = List.copyOf(d20s);
            damage = List.copyOf(damage);
            successDamage = List.copyOf(successDamage);
            conditions = List.copyOf(conditions);
        }

        /** An outcome nothing was added to after its roll. */
        public TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                             RollMode rollMode, boolean autoFailed, List<Damage> damage, List<Damage> successDamage,
                             int healing, List<String> conditions, SaveRoll hitSave) {
            this(targetId, outcome, d20s, d20, total, rollMode, autoFailed, damage, successDamage, healing,
                    conditions, hitSave, null, false);
        }
    }

    /**
     * A saving throw. d20 and total are null when it failed automatically.
     *
     * @param d20s     every d20 rolled: two with advantage, none when it failed automatically
     * @param rollMode how it was rolled ({@link SaveRules#rollMode})
     */
    public record SaveRoll(Integer d20, Integer total, boolean success, boolean autoFailed, List<Integer> d20s,
                           RollMode rollMode) {

        public SaveRoll {
            d20s = List.copyOf(d20s);
            Objects.requireNonNull(rollMode, "rollMode");
        }

        /** A save rolled once, or failed automatically when d20 is null. */
        public SaveRoll(Integer d20, Integer total, boolean success, boolean autoFailed) {
            this(d20, total, success, autoFailed, d20 != null ? List.of(d20) : List.of(), RollMode.NORMAL);
        }
    }

    /** Each target's outcome, in the order the targets are given. */
    public List<TargetOutcome> resolve(Effect effect, List<Target> targets) {
        List<TargetOutcome> outcomes = new ArrayList<>();
        switch (effect.delivery()) {
            case Effect.Attack attack -> {
                for (Target target : targets) {
                    outcomes.add(attack(effect, attack, target));
                }
            }
            case Effect.Save save -> {
                List<Damage> damage = rollDamage(effect.damage(), false);
                int healing = rollHealing(effect.healing());
                for (Target target : targets) {
                    outcomes.add(save(effect, save, target, damage, healing));
                }
            }
            case Effect.Automatic automatic -> outcomes.addAll(automatic(effect, targets));
            case Effect.Self self -> outcomes.addAll(automatic(effect, targets));
        }
        return List.copyOf(outcomes);
    }

    /**
     * A saving throw outside an Effect, such as a concentration check. It fails automatically when the
     * conditions say so (D161); otherwise the d20 plus the modifier must reach the DC, rolled twice keeping the
     * higher when the conditions give advantage, as the Dodge action does on Dexterity saves. A natural 1 or 20
     * means nothing special on a save.
     */
    public SaveRoll savingThrow(Ability ability, int modifier, int dc, Collection<String> conditions) {
        return savingThrow(ability, modifier, dc, conditions, false);
    }

    /**
     * As {@link #savingThrow(Ability, int, int, Collection)}, with disadvantage on a Strength or Dexterity save when
     * strDexDisadvantage is true, as for a creature in armour it isn't proficient with (PHB p.144).
     */
    public SaveRoll savingThrow(Ability ability, int modifier, int dc, Collection<String> conditions,
                                boolean strDexDisadvantage) {
        return savingThrow(ability, modifier, dc, conditions, strDexDisadvantage, List.of());
    }

    // As above, with the target's other sources of advantage or disadvantage on this save, combined once (PHB p.173)
    private SaveRoll savingThrow(Ability ability, int modifier, int dc, Collection<String> conditions,
                                 boolean strDexDisadvantage, List<RollMode> otherSources) {
        if (SaveRules.autoFails(ability, conditions)) {
            return new SaveRoll(null, null, false, true);
        }
        List<RollMode> sources = new ArrayList<>(otherSources);
        sources.add(SaveRules.rollMode(ability, conditions, strDexDisadvantage));
        RollMode mode = RollMode.combine(sources);
        if (mode == RollMode.NORMAL) {
            int d20 = rng.roll(20);
            int total = d20 + modifier;
            return new SaveRoll(d20, total, total >= dc, false);
        }
        List<Integer> d20s = List.of(rng.roll(20), rng.roll(20));
        int d20 = mode == RollMode.ADVANTAGE ? Math.max(d20s.get(0), d20s.get(1)) : Math.min(d20s.get(0), d20s.get(1));
        int total = d20 + modifier;
        return new SaveRoll(d20, total, total >= dc, false, d20s, mode);
    }

    /** One d20, such as a death saving throw. */
    public int d20() {
        return rng.roll(20);
    }

    private TargetOutcome attack(Effect effect, Effect.Attack attack, Target target) {
        AttackRules.Setup setup = AttackRules.setup(attack.attackerSources(), target.conditions(),
                attack.withinFiveFeet());
        List<Integer> d20s = setup.rollMode() == RollMode.NORMAL
                ? List.of(rng.roll(20))
                : List.of(rng.roll(20), rng.roll(20));
        int d20 = switch (setup.rollMode()) {
            case ADVANTAGE -> Math.max(d20s.get(0), d20s.get(1));
            case DISADVANTAGE -> Math.min(d20s.get(0), d20s.get(1));
            case NORMAL -> d20s.get(0);
        };
        Outcome outcome = attack.forcedCritical()
                ? Outcome.CRITICAL
                : AttackRules.outcome(d20, attack.bonus(), target.armourClass(), setup.hitIsCritical(),
                        attack.critRange());
        int bonus = attack.bonus();
        Inspiration inspiration = null;
        boolean guidedStrike = false;
        // What the attacker adds once it sees the roll would miss; a natural 1 misses whatever is added (PHB p.194)
        if (outcome == Outcome.MISS && d20 != 1 && attack.rescue().any()) {
            if (attack.rescue().inspirationSides() > 0) {
                inspiration = new Inspiration(attack.rescue().inspirationSides(),
                        rng.roll(attack.rescue().inspirationSides()));
                bonus += inspiration.roll();
                outcome = AttackRules.outcome(d20, bonus, target.armourClass(), setup.hitIsCritical(),
                        attack.critRange());
            }
            if (outcome == Outcome.MISS && attack.rescue().guidedStrike()) {
                Outcome guided = AttackRules.outcome(d20, bonus + ChannelDivinity.ATTACK_BONUS, target.armourClass(),
                        setup.hitIsCritical(), attack.critRange());
                if (guided != Outcome.MISS) {
                    bonus += ChannelDivinity.ATTACK_BONUS;
                    outcome = guided;
                    guidedStrike = true;
                }
            }
        }
        int total = d20 + bonus;
        if (outcome == Outcome.MISS) {
            return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                    List.of(), List.of(), 0, List.of(), null, inspiration, false);
        }
        List<Damage> damage = rollDamage(effect.damage(), outcome == Outcome.CRITICAL);
        int healing = rollHealing(effect.healing());
        Effect.HitSave hitSave = attack.hitSave();
        if (hitSave == null) {
            return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                    damage, List.of(), healing, effect.conditions(), null, inspiration, guidedStrike);
        }
        // The save decides only the conditions it guards, so a success would leave all of the damage
        SaveRoll save = savingThrow(hitSave.ability(), target.saveModifier(), hitSave.dc(), target.conditions(),
                target.strDexDisadvantage());
        List<String> conditions = effect.conditions().stream()
                .filter(condition -> !save.success() || !hitSave.conditions().contains(condition))
                .toList();
        return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                damage, save.success() ? List.of() : damage, healing, conditions, save, inspiration, guidedStrike);
    }

    private TargetOutcome save(Effect effect, Effect.Save save, Target target, List<Damage> damage, int healing) {
        SaveRoll roll = savingThrow(save.ability(), target.saveModifier(), save.dc(), target.conditions(),
                target.strDexDisadvantage(), target.saveSources());
        List<Damage> onSuccess = save.halfDamageOnSuccess() ? halved(damage) : List.of();
        // A held Bardic Inspiration die is rolled on a failed save its holder rolled, not on one failed
        // automatically (PHB p.53)
        Inspiration inspiration = null;
        if (!roll.success() && !roll.autoFailed() && target.inspirationSides() > 0) {
            inspiration = new Inspiration(target.inspirationSides(), rng.roll(target.inspirationSides()));
            int total = roll.total() + inspiration.roll();
            roll = new SaveRoll(roll.d20(), total, total >= save.dc(), false, roll.d20s(), roll.rollMode());
        }
        if (roll.success()) {
            return new TargetOutcome(target.id(), Outcome.SAVE_SUCCESS, roll.d20s(), roll.d20(), roll.total(),
                    roll.rollMode(), false, onSuccess, List.of(), healing, List.of(), null, inspiration, false);
        }
        return new TargetOutcome(target.id(), Outcome.SAVE_FAIL, roll.d20s(), roll.d20(), roll.total(),
                roll.rollMode(), roll.autoFailed(), damage, onSuccess, healing, effect.conditions(), null,
                inspiration, false);
    }

    private List<TargetOutcome> automatic(Effect effect, List<Target> targets) {
        List<Damage> damage = rollDamage(effect.damage(), false);
        int healing = rollHealing(effect.healing());
        return targets.stream()
                .map(target -> new TargetOutcome(target.id(), Outcome.AUTOMATIC, List.of(), null, null,
                        RollMode.NORMAL, false, damage, List.of(), healing, effect.conditions(), null))
                .toList();
    }

    // Each part rolled once, its dice doubled on a critical hit and those it rerolls rolled again (Great Weapon
    // Fighting), then totalled by type in the parts' order
    private List<Damage> rollDamage(List<Effect.DamagePart> parts, boolean critical) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Effect.DamagePart part : parts) {
            Dice dice = critical ? part.dice().doubled() : part.dice();
            byType.merge(part.type(), dice.total(dice.rollDice(rng, part.rerollUpTo())), Integer::sum);
        }
        return byType.entrySet().stream().map(e -> new Damage(e.getKey(), e.getValue())).toList();
    }

    private int rollHealing(Dice healing) {
        return healing != null ? healing.roll(rng) : 0;
    }

    // Half of each type, rounding down with no minimum (PHB p.7)
    private static List<Damage> halved(List<Damage> damage) {
        return damage.stream().map(d -> new Damage(d.type(), DamageRules.halved(d.amount()))).toList();
    }
}
