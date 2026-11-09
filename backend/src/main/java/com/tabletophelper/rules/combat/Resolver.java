package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
     * @param autoSucceed  it succeeds on the Effect's save without rolling, as a creature Careful Spell protects does
     *                     (PHB p.102, M18)
     * @param senses       its blindsight and truesight, which let it perceive an invisible attacker (MM p.8-9)
     */
    public record Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                         boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                         boolean autoSucceed, AttackRules.Senses senses) {

        public Target {
            Objects.requireNonNull(id, "id");
            conditions = List.copyOf(conditions);
            saveSources = saveSources != null ? List.copyOf(saveSources) : List.of();
            senses = senses != null ? senses : AttackRules.Senses.NONE;
        }

        /** A target with no special senses. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                      boolean autoSucceed) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides,
                    autoSucceed, AttackRules.Senses.NONE);
        }

        /** Its side of an attack roll against it. */
        public AttackRules.Side side() {
            return new AttackRules.Side(List.copyOf(conditions), senses);
        }

        /** A target that rolls its save. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides, false);
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
                    inspirationSides, autoSucceed, senses);
        }

        /** The same target succeeding on the Effect's save without rolling (Careful Spell, PHB p.102). */
        public Target succeedingAutomatically() {
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources,
                    inspirationSides, true, senses);
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
     * @param rerolledD20s  the d20s of the missed attack roll Unerring Accuracy rolled again (XGE p.35), d20s being the
     *                      new roll's; null when nothing was rerolled
     * @param automaticSources the advantage and disadvantage the attacker's and the target's conditions gave an attack
     *                      roll, with their reasons ({@link AttackRules#setup}); empty for anything else
     */
    public record TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                                RollMode rollMode, boolean autoFailed, List<Damage> damage,
                                List<Damage> successDamage, int healing, List<String> conditions,
                                SaveRoll hitSave, Inspiration inspiration, boolean guidedStrike,
                                List<Integer> rerolledD20s, List<AttackRules.Source> automaticSources) {

        public TargetOutcome {
            d20s = List.copyOf(d20s);
            damage = List.copyOf(damage);
            successDamage = List.copyOf(successDamage);
            conditions = List.copyOf(conditions);
            rerolledD20s = rerolledD20s != null ? List.copyOf(rerolledD20s) : null;
            automaticSources = automaticSources != null ? List.copyOf(automaticSources) : List.of();
        }

        /** An outcome with no automatic sources named. */
        public TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                             RollMode rollMode, boolean autoFailed, List<Damage> damage, List<Damage> successDamage,
                             int healing, List<String> conditions, SaveRoll hitSave, Inspiration inspiration,
                             boolean guidedStrike, List<Integer> rerolledD20s) {
            this(targetId, outcome, d20s, d20, total, rollMode, autoFailed, damage, successDamage, healing,
                    conditions, hitSave, inspiration, guidedStrike, rerolledD20s, List.of());
        }

        /** An outcome no roll was rerolled for. */
        public TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                             RollMode rollMode, boolean autoFailed, List<Damage> damage, List<Damage> successDamage,
                             int healing, List<String> conditions, SaveRoll hitSave, Inspiration inspiration,
                             boolean guidedStrike) {
            this(targetId, outcome, d20s, d20, total, rollMode, autoFailed, damage, successDamage, healing,
                    conditions, hitSave, inspiration, guidedStrike, null);
        }

        /** Whether Unerring Accuracy rerolled its attack roll. */
        public boolean rerolled() {
            return rerolledD20s != null;
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

    /**
     * Each target's outcome, in the order the targets are given, for an Effect whose user has no conditions or senses
     * that change an attack roll, such as the DM's own attack.
     */
    public List<TargetOutcome> resolve(Effect effect, List<Target> targets) {
        return resolve(effect, AttackRules.Side.NONE, targets, 0);
    }

    /**
     * Each target's outcome, in the order the targets are given; an attack roll counts the attacker's conditions and
     * senses ({@link AttackRules#setup}).
     */
    public List<TargetOutcome> resolve(Effect effect, AttackRules.Side attacker, List<Target> targets) {
        return resolve(effect, attacker, targets, 0);
    }

    /** As {@link #resolve(Effect, AttackRules.Side, List, int)}, for a user with no conditions or senses. */
    public List<TargetOutcome> resolve(Effect effect, List<Target> targets, int empoweredDice) {
        return resolve(effect, AttackRules.Side.NONE, targets, empoweredDice);
    }

    /**
     * As {@link #resolve(Effect, AttackRules.Side, List)}, with Empowered Spell (PHB p.102, M18): each damage roll then
     * rerolls up to empoweredDice of its dice, those that rolled below their die's average, the furthest below first,
     * and keeps the new rolls. A damage roll shared by every target is rerolled once.
     */
    public List<TargetOutcome> resolve(Effect effect, AttackRules.Side attacker, List<Target> targets,
                                       int empoweredDice) {
        List<TargetOutcome> outcomes = new ArrayList<>();
        switch (effect.delivery()) {
            case Effect.Attack attack -> {
                for (Target target : targets) {
                    outcomes.add(attack(effect, attack, attacker, target, empoweredDice));
                }
            }
            case Effect.Save save -> {
                List<Damage> damage = rollDamage(effect.damage(), false, empoweredDice);
                int healing = rollHealing(effect.healing());
                for (Target target : targets) {
                    outcomes.add(save(effect, save, target, damage, healing));
                }
            }
            case Effect.Automatic automatic -> outcomes.addAll(automatic(effect, targets, empoweredDice));
            case Effect.Self self -> outcomes.addAll(automatic(effect, targets, empoweredDice));
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

    private TargetOutcome attack(Effect effect, Effect.Attack attack, AttackRules.Side attacker, Target target,
                                 int empoweredDice) {
        AttackRules.Setup setup = AttackRules.setup(attack.attackerSources(), attacker, target.side(),
                attack.withinFiveFeet());
        List<AttackRules.Source> automatic = setup.automatic();
        List<Integer> d20s = rollD20s(setup.rollMode());
        int d20 = kept(setup.rollMode(), d20s);
        Outcome outcome = attack.forcedCritical()
                ? Outcome.CRITICAL
                : AttackRules.outcome(d20, attack.bonus(), target.armourClass(), setup.hitIsCritical(),
                        attack.critRange());
        // Unerring Accuracy rolls a missed attack roll again, as it was rolled, and the new roll stands (XGE p.35)
        List<Integer> rerolledD20s = null;
        if (outcome == Outcome.MISS && attack.rescue().reroll()) {
            rerolledD20s = d20s;
            d20s = rollD20s(setup.rollMode());
            d20 = kept(setup.rollMode(), d20s);
            outcome = AttackRules.outcome(d20, attack.bonus(), target.armourClass(), setup.hitIsCritical(),
                    attack.critRange());
        }
        int bonus = attack.bonus();
        Inspiration inspiration = null;
        boolean guidedStrike = false;
        // What the attacker adds once it sees the roll would miss; a natural 1 misses whatever is added (PHB p.194)
        if (outcome == Outcome.MISS && d20 != 1
                && (attack.rescue().inspirationSides() > 0 || attack.rescue().guidedStrike())) {
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
                    List.of(), List.of(), 0, List.of(), null, inspiration, false, rerolledD20s, automatic);
        }
        List<Damage> damage = rollDamage(effect.damage(), outcome == Outcome.CRITICAL, empoweredDice);
        int healing = rollHealing(effect.healing());
        Effect.HitSave hitSave = attack.hitSave();
        if (hitSave == null) {
            return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                    damage, List.of(), healing, effect.conditions(), null, inspiration, guidedStrike, rerolledD20s,
                    automatic);
        }
        // The save decides only the conditions it guards, so a success would leave all of the damage
        SaveRoll save = target.autoSucceed() ? new SaveRoll(null, null, true, false)
                : savingThrow(hitSave.ability(), target.saveModifier(), hitSave.dc(), target.conditions(),
                        target.strDexDisadvantage(), target.saveSources());
        List<String> conditions = effect.conditions().stream()
                .filter(condition -> !save.success() || !hitSave.conditions().contains(condition))
                .toList();
        return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                damage, save.success() ? List.of() : damage, healing, conditions, save, inspiration, guidedStrike,
                rerolledD20s, automatic);
    }

    // One d20, or two with advantage or disadvantage
    private List<Integer> rollD20s(RollMode mode) {
        return mode == RollMode.NORMAL ? List.of(rng.roll(20)) : List.of(rng.roll(20), rng.roll(20));
    }

    // The d20 that counts of those rolled
    private static int kept(RollMode mode, List<Integer> d20s) {
        return switch (mode) {
            case ADVANTAGE -> Math.max(d20s.get(0), d20s.get(1));
            case DISADVANTAGE -> Math.min(d20s.get(0), d20s.get(1));
            case NORMAL -> d20s.get(0);
        };
    }

    private TargetOutcome save(Effect effect, Effect.Save save, Target target, List<Damage> damage, int healing) {
        // A creature Careful Spell protects succeeds without rolling (PHB p.102)
        SaveRoll roll = target.autoSucceed() ? new SaveRoll(null, null, true, false)
                : savingThrow(save.ability(), target.saveModifier(), save.dc(), target.conditions(),
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

    private List<TargetOutcome> automatic(Effect effect, List<Target> targets, int empoweredDice) {
        List<Damage> damage = rollDamage(effect.damage(), false, empoweredDice);
        int healing = rollHealing(effect.healing());
        return targets.stream()
                .map(target -> new TargetOutcome(target.id(), Outcome.AUTOMATIC, List.of(), null, null,
                        RollMode.NORMAL, false, damage, List.of(), healing, effect.conditions(), null))
                .toList();
    }

    // Each part rolled once, its dice doubled on a critical hit and those it rerolls rolled again (Great Weapon
    // Fighting), then up to empoweredDice of all the dice rolled again (Empowered Spell), and totalled by type in the
    // parts' order
    private List<Damage> rollDamage(List<Effect.DamagePart> parts, boolean critical, int empoweredDice) {
        List<Dice> dice = new ArrayList<>();
        List<int[]> rolls = new ArrayList<>();
        for (Effect.DamagePart part : parts) {
            Dice rolled = critical ? part.dice().doubled() : part.dice();
            dice.add(rolled);
            rolls.add(rolled.rollEach(rng, part.rerollUpTo()));
        }
        if (empoweredDice > 0) {
            empower(dice, rolls, empoweredDice);
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            int sum = 0;
            for (int roll : rolls.get(i)) {
                sum += roll;
            }
            byType.merge(parts.get(i).type(), dice.get(i).total(sum), Integer::sum);
        }
        return byType.entrySet().stream().map(e -> new Damage(e.getKey(), e.getValue())).toList();
    }

    // Empowered Spell (PHB p.102): the sorcerer may reroll up to that many damage dice and must use the new rolls; the
    // server rerolls those below their die's average, those furthest below first, the earlier die first on a tie
    private void empower(List<Dice> dice, List<int[]> rolls, int count) {
        record Rolled(int part, int index, int sides, double shortfall) {
        }
        List<Rolled> low = new ArrayList<>();
        for (int part = 0; part < dice.size(); part++) {
            int sides = dice.get(part).sides();
            double average = (sides + 1) / 2.0;
            int[] each = rolls.get(part);
            for (int index = 0; index < each.length; index++) {
                if (each[index] < average) {
                    low.add(new Rolled(part, index, sides, average - each[index]));
                }
            }
        }
        low.sort(Comparator.comparingDouble(Rolled::shortfall).reversed());
        for (Rolled rolled : low.subList(0, Math.min(count, low.size()))) {
            rolls.get(rolled.part())[rolled.index()] = rng.roll(rolled.sides());
        }
    }

    private int rollHealing(Dice healing) {
        return healing != null ? healing.roll(rng) : 0;
    }

    // Half of each type, rounding down with no minimum (PHB p.7)
    private static List<Damage> halved(List<Damage> damage) {
        return damage.stream().map(d -> new Damage(d.type(), DamageRules.halved(d.amount()))).toList();
    }
}
