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
import java.util.Set;
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
     * @param modifiers    what the Ongoing Modifiers of the attacker and of the target do to an attack roll against it
     *                     or to its save (D184)
     * @param evasion      it has Evasion (PHB p.79, p.96, D188), which changes a Dexterity save against damage halved by
     *                     a success
     * @param namedSaveSources the sources of advantage or disadvantage on the Effect's save the log names, such as a
     *                     monster's Magic Resistance against a spell (MM p.170, D191); each is in saveSources too
     */
    public record Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                         boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                         boolean autoSucceed, AttackRules.Senses senses, RollModifiers modifiers, boolean evasion,
                         List<AttackRules.Source> namedSaveSources) {

        public Target {
            Objects.requireNonNull(id, "id");
            conditions = List.copyOf(conditions);
            saveSources = saveSources != null ? List.copyOf(saveSources) : List.of();
            senses = senses != null ? senses : AttackRules.Senses.NONE;
            modifiers = modifiers != null ? modifiers : RollModifiers.NONE;
            namedSaveSources = namedSaveSources != null ? List.copyOf(namedSaveSources) : List.of();
        }

        /** A target whose save has no source the log names. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                      boolean autoSucceed, AttackRules.Senses senses, RollModifiers modifiers, boolean evasion) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides,
                    autoSucceed, senses, modifiers, evasion, List.of());
        }

        /**
         * The same target with another source of advantage or disadvantage on the Effect's save, which the log names, as
         * "advantage: Magic Resistance" (D191).
         */
        public Target withNamedSaveSource(AttackRules.Source source) {
            List<RollMode> sources = new ArrayList<>(saveSources);
            sources.add(source.mode());
            List<AttackRules.Source> named = new ArrayList<>(namedSaveSources);
            named.add(source);
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, sources,
                    inspirationSides, autoSucceed, senses, modifiers, evasion, named);
        }

        /** A target without Evasion. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                      boolean autoSucceed, AttackRules.Senses senses, RollModifiers modifiers) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides,
                    autoSucceed, senses, modifiers, false);
        }

        /**
         * The same target with Evasion or without (D188): on a Dexterity save against an effect that halves its damage
         * on a success, it takes none on a success and half on a failure (PHB p.79, p.96).
         */
        public Target withEvasion(boolean has) {
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources,
                    inspirationSides, autoSucceed, senses, modifiers, has, namedSaveSources);
        }

        /** A target no Ongoing Modifier changes. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                      boolean autoSucceed, AttackRules.Senses senses) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides,
                    autoSucceed, senses, RollModifiers.NONE);
        }

        /** The same target with these Ongoing Modifiers' changes (D184). */
        public Target withModifiers(RollModifiers changed) {
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources,
                    inspirationSides, autoSucceed, senses, changed, evasion, namedSaveSources);
        }

        /** A target with no special senses. */
        public Target(UUID id, int armourClass, int saveModifier, Collection<String> conditions,
                      boolean strDexDisadvantage, List<RollMode> saveSources, int inspirationSides,
                      boolean autoSucceed) {
            this(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources, inspirationSides,
                    autoSucceed, AttackRules.Senses.NONE);
        }

        /** Its side of an attack roll against it, without invisibility when it can't benefit from it (D184). */
        public AttackRules.Side side() {
            AttackRules.Side side = new AttackRules.Side(List.copyOf(conditions), senses);
            return modifiers.revealed() ? side.revealed() : side;
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
                    inspirationSides, autoSucceed, senses, modifiers, evasion, namedSaveSources);
        }

        /** The same target succeeding on the Effect's save without rolling (Careful Spell, PHB p.102). */
        public Target succeedingAutomatically() {
            return new Target(id, armourClass, saveModifier, conditions, strDexDisadvantage, saveSources,
                    inspirationSides, true, senses, modifiers, evasion, namedSaveSources);
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
     * @param bonuses       what the Ongoing Modifiers added to the attack roll or the save, already in total (D184)
     * @param riders        the extra damage the Ongoing Modifiers added to a hit, already in damage (D184)
     * @param spentOneShots the one-shot Ongoing Modifiers this roll used, which are now spent (D184)
     */
    public record TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                                RollMode rollMode, boolean autoFailed, List<Damage> damage,
                                List<Damage> successDamage, int healing, List<String> conditions,
                                SaveRoll hitSave, Inspiration inspiration, boolean guidedStrike,
                                List<Integer> rerolledD20s, List<AttackRules.Source> automaticSources,
                                List<RollModifiers.RolledBonus> bonuses, List<RollModifiers.RolledDamage> riders,
                                Set<UUID> spentOneShots) {

        public TargetOutcome {
            d20s = List.copyOf(d20s);
            damage = List.copyOf(damage);
            successDamage = List.copyOf(successDamage);
            conditions = List.copyOf(conditions);
            rerolledD20s = rerolledD20s != null ? List.copyOf(rerolledD20s) : null;
            automaticSources = automaticSources != null ? List.copyOf(automaticSources) : List.of();
            bonuses = bonuses != null ? List.copyOf(bonuses) : List.of();
            riders = riders != null ? List.copyOf(riders) : List.of();
            spentOneShots = spentOneShots != null ? Set.copyOf(spentOneShots) : Set.of();
        }

        /** The same outcome with this damage, as Ray of Enfeeblement's halved damage (PHB p.271, D197). */
        public TargetOutcome withDamage(List<Damage> changed) {
            return new TargetOutcome(targetId, outcome, d20s, d20, total, rollMode, autoFailed, changed, successDamage,
                    healing, conditions, hitSave, inspiration, guidedStrike, rerolledD20s, automaticSources, bonuses,
                    riders, spentOneShots);
        }

        /** An outcome no Ongoing Modifier changed. */
        public TargetOutcome(UUID targetId, Outcome outcome, List<Integer> d20s, Integer d20, Integer total,
                             RollMode rollMode, boolean autoFailed, List<Damage> damage, List<Damage> successDamage,
                             int healing, List<String> conditions, SaveRoll hitSave, Inspiration inspiration,
                             boolean guidedStrike, List<Integer> rerolledD20s,
                             List<AttackRules.Source> automaticSources) {
            this(targetId, outcome, d20s, d20, total, rollMode, autoFailed, damage, successDamage, healing,
                    conditions, hitSave, inspiration, guidedStrike, rerolledD20s, automaticSources, List.of(),
                    List.of(), Set.of());
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
                           RollMode rollMode, List<RollModifiers.RolledBonus> bonuses, Set<UUID> spentOneShots) {

        public SaveRoll {
            d20s = List.copyOf(d20s);
            Objects.requireNonNull(rollMode, "rollMode");
            bonuses = bonuses != null ? List.copyOf(bonuses) : List.of();
            spentOneShots = spentOneShots != null ? Set.copyOf(spentOneShots) : Set.of();
        }

        /** A save no Ongoing Modifier added to. */
        public SaveRoll(Integer d20, Integer total, boolean success, boolean autoFailed, List<Integer> d20s,
                        RollMode rollMode) {
            this(d20, total, success, autoFailed, d20s, rollMode, List.of(), Set.of());
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
        return savingThrow(ability, modifier, dc, conditions, strDexDisadvantage, otherSources, List.of());
    }

    /**
     * As {@link #savingThrow(Ability, int, int, Collection, boolean)}, with other sources of advantage or disadvantage,
     * combined once with the conditions' (PHB p.173), and the dice and amounts Ongoing Modifiers add to it or take from
     * it (D184), such as Bless's d4 (PHB p.219), rolled after the d20. A save failed automatically rolls nothing, so a
     * one-shot modifier isn't spent on it.
     */
    public SaveRoll savingThrow(Ability ability, int modifier, int dc, Collection<String> conditions,
                                boolean strDexDisadvantage, List<RollMode> otherSources,
                                List<RollModifiers.Bonus> bonuses) {
        if (SaveRules.autoFails(ability, conditions)) {
            return new SaveRoll(null, null, false, true);
        }
        List<RollMode> sources = new ArrayList<>(otherSources);
        sources.add(SaveRules.rollMode(ability, conditions, strDexDisadvantage));
        RollMode mode = RollMode.combine(sources);
        List<Integer> d20s = rollD20s(mode);
        int d20 = kept(mode, d20s);
        List<RollModifiers.RolledBonus> rolled = rollBonuses(bonuses);
        int total = d20 + modifier + sum(rolled);
        return new SaveRoll(d20, total, total >= dc, false, d20s, mode, rolled, oneShots(bonuses));
    }

    /**
     * Rolls the dice the Ongoing Modifiers add to a d20 roll or take from it (D184), each signed, in order; a flat
     * amount rolls nothing.
     */
    public List<RollModifiers.RolledBonus> rollBonuses(List<RollModifiers.Bonus> bonuses) {
        List<RollModifiers.RolledBonus> rolled = new ArrayList<>();
        for (RollModifiers.Bonus bonus : bonuses) {
            rolled.add(new RollModifiers.RolledBonus(bonus.label(), bonus.dice(), bonus.sign(),
                    bonus.sign() * bonus.dice().roll(rng)));
        }
        return rolled;
    }

    /** The signed total of the rolled bonuses. */
    public static int sum(List<RollModifiers.RolledBonus> rolled) {
        return rolled.stream().mapToInt(RollModifiers.RolledBonus::total).sum();
    }

    // The one-shot modifiers among the bonuses, spent once they are rolled
    private static Set<UUID> oneShots(List<RollModifiers.Bonus> bonuses) {
        Set<UUID> spent = new java.util.HashSet<>();
        for (RollModifiers.Bonus bonus : bonuses) {
            if (bonus.oneShot() && bonus.modifierId() != null) {
                spent.add(bonus.modifierId());
            }
        }
        return spent;
    }

    /** One d20, such as a death saving throw. */
    public int d20() {
        return rng.roll(20);
    }

    /** One die of these sides, such as Confusion's d10 (PHB p.224, D185). */
    public int roll(int sides) {
        return rng.roll(sides);
    }

    private TargetOutcome attack(Effect effect, Effect.Attack attack, AttackRules.Side attacker, Target target,
                                 int empoweredDice) {
        RollModifiers modifiers = target.modifiers();
        AttackRules.Setup setup = AttackRules.setup(attack.attackerSources(), attacker, target.side(),
                attack.withinFiveFeet(), modifiers.attackSources());
        List<AttackRules.Source> automatic = setup.automatic();
        List<Integer> d20s = rollD20s(setup.rollMode());
        int d20 = kept(setup.rollMode(), d20s);
        // What the Ongoing Modifiers add to the roll, such as Bless's d4 (PHB p.219), rolled once after the d20 (D184)
        List<RollModifiers.RolledBonus> bonuses = rollBonuses(modifiers.attackBonuses());
        int rolledBonus = attack.bonus() + sum(bonuses);
        Set<UUID> spent = new java.util.HashSet<>(modifiers.attackOneShots());
        spent.addAll(oneShots(modifiers.attackBonuses()));
        Outcome outcome = attack.forcedCritical()
                ? Outcome.CRITICAL
                : AttackRules.outcome(d20, rolledBonus, target.armourClass(), setup.hitIsCritical(),
                        attack.critRange());
        // Unerring Accuracy rolls a missed attack roll again, as it was rolled, and the new roll stands (XGE p.35)
        List<Integer> rerolledD20s = null;
        if (outcome == Outcome.MISS && attack.rescue().reroll()) {
            rerolledD20s = d20s;
            d20s = rollD20s(setup.rollMode());
            d20 = kept(setup.rollMode(), d20s);
            outcome = AttackRules.outcome(d20, rolledBonus, target.armourClass(), setup.hitIsCritical(),
                    attack.critRange());
        }
        int bonus = rolledBonus;
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
                    List.of(), List.of(), 0, List.of(), null, inspiration, false, rerolledD20s, automatic, bonuses,
                    List.of(), spent);
        }
        boolean critical = outcome == Outcome.CRITICAL;
        List<Damage> damage = rollDamage(effect.damage(), critical, empoweredDice);
        // The Ongoing Modifiers' extra damage on a hit, its dice doubled on a critical hit (PHB p.196, D184)
        String ownType = effect.damage().isEmpty() ? null : effect.damage().getFirst().type();
        List<RollModifiers.RolledDamage> riders = rollRiders(modifiers.hitDamage(), critical, ownType);
        damage = withRiders(damage, riders);
        modifiers.hitDamage().stream()
                .filter(rider -> rider.oneShot() && rider.modifierId() != null)
                .forEach(rider -> spent.add(rider.modifierId()));
        int healing = rollHealing(effect.healing());
        Effect.HitSave hitSave = attack.hitSave();
        if (hitSave == null) {
            return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                    damage, List.of(), healing, effect.conditions(), null, inspiration, guidedStrike, rerolledD20s,
                    automatic, bonuses, riders, spent);
        }
        // The save decides only the conditions it guards, so a success would leave all of the damage
        SaveRoll save = target.autoSucceed() ? new SaveRoll(null, null, true, false)
                : savingThrow(hitSave.ability(), target.saveModifier(), hitSave.dc(), target.conditions(),
                        target.strDexDisadvantage(), target.saveSources(), modifiers.saveBonuses());
        spent.addAll(save.spentOneShots());
        List<String> conditions = effect.conditions().stream()
                .filter(condition -> !save.success() || !hitSave.conditions().contains(condition))
                .toList();
        return new TargetOutcome(target.id(), outcome, d20s, d20, total, setup.rollMode(), false,
                damage, save.success() ? List.of() : damage, healing, conditions, save, inspiration, guidedStrike,
                rerolledD20s, automatic, bonuses, riders, spent);
    }

    // Each rider rolled, its dice doubled on a critical hit; one with no type of its own takes the attack's own type
    private List<RollModifiers.RolledDamage> rollRiders(List<RollModifiers.HitDamage> riders, boolean critical,
                                                        String ownType) {
        List<RollModifiers.RolledDamage> rolled = new ArrayList<>();
        for (RollModifiers.HitDamage rider : riders) {
            Dice dice = critical ? rider.dice().doubled() : rider.dice();
            String type = rider.type() != null ? rider.type().trim().toLowerCase() : ownType;
            rolled.add(new RollModifiers.RolledDamage(rider.label(), dice, rider.sign(), type, dice.roll(rng)));
        }
        return rolled;
    }

    /**
     * The damage with the riders: each added to its type, and each taken away from its type, never leaving less than 1
     * of damage that was dealt, as Reduce's -1d4 (PHB p.237).
     */
    static List<Damage> withRiders(List<Damage> damage, List<RollModifiers.RolledDamage> riders) {
        if (riders.isEmpty()) {
            return damage;
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        damage.forEach(each -> byType.merge(each.type(), each.amount(), Integer::sum));
        for (RollModifiers.RolledDamage rider : riders) {
            if (rider.sign() > 0) {
                byType.merge(rider.type(), rider.amount(), Integer::sum);
            }
        }
        for (RollModifiers.RolledDamage rider : riders) {
            if (rider.sign() < 0 && byType.containsKey(rider.type())) {
                int before = byType.get(rider.type());
                byType.put(rider.type(), before > 0 ? Math.max(1, before - rider.amount()) : before);
            }
        }
        return byType.entrySet().stream().map(e -> new Damage(e.getKey(), e.getValue())).toList();
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
                        target.strDexDisadvantage(), target.saveSources(), target.modifiers().saveBonuses());
        // A success halves the damage, prevents it, or leaves it whole, as Mental Prison's (XGE p.161, D188)
        List<Damage> onSuccess = save.fullDamageOnSuccess() ? damage
                : save.halfDamageOnSuccess() ? halved(damage) : List.of();
        // Evasion: on a Dexterity save against damage halved by a success, a success takes none and a failure half
        // (PHB p.79, p.96, D188)
        boolean evading = target.evasion() && save.ability() == Ability.DEX && save.halfDamageOnSuccess()
                && !save.fullDamageOnSuccess();
        if (evading) {
            damage = halved(damage);
            onSuccess = List.of();
        }
        // A held Bardic Inspiration die is rolled on a failed save its holder rolled, not on one failed
        // automatically (PHB p.53)
        Inspiration inspiration = null;
        if (!roll.success() && !roll.autoFailed() && target.inspirationSides() > 0) {
            inspiration = new Inspiration(target.inspirationSides(), rng.roll(target.inspirationSides()));
            int total = roll.total() + inspiration.roll();
            roll = new SaveRoll(roll.d20(), total, total >= save.dc(), false, roll.d20s(), roll.rollMode(),
                    roll.bonuses(), roll.spentOneShots());
        }
        if (roll.success()) {
            return new TargetOutcome(target.id(), Outcome.SAVE_SUCCESS, roll.d20s(), roll.d20(), roll.total(),
                    roll.rollMode(), false, onSuccess, List.of(), healing, List.of(), null, inspiration, false, null,
                    target.namedSaveSources(), roll.bonuses(), List.of(), roll.spentOneShots());
        }
        return new TargetOutcome(target.id(), Outcome.SAVE_FAIL, roll.d20s(), roll.d20(), roll.total(),
                roll.rollMode(), roll.autoFailed(), damage, onSuccess, healing, effect.conditions(), null,
                inspiration, false, null, target.namedSaveSources(), roll.bonuses(), List.of(), roll.spentOneShots());
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
            Dice rolled = critical ? part.onCritical() : part.dice();
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
