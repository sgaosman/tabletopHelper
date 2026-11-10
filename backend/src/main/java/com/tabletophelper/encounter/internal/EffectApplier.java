package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConcentrationCheck;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.state.SummonSpec;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.RollModifiers;
import com.tabletophelper.rules.combat.StandardActions;
import com.tabletophelper.rules.combat.Vitals;
import com.tabletophelper.rules.combat.VitalsChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only class that changes a participant's hit points, life state, conditions and concentration (D160),
 * following the 2014 rules of D161 to D163 on every path: weapon attacks, spells, monster actions, monster
 * spells and the DM's manual changes. It also writes the combat log entries that describe those changes:
 * one per target of an Effect, which the Player View drops for a hidden target (D157).
 *
 * <ul>
 *   <li>A condition the target is immune to, from its stat block or its sheet, isn't applied, and its entry says
 *       so; the DM can still add one by hand.</li>
 *   <li>Damage goes through the target's defences and then {@link DamageRules}: temporary hit points, dropping
 *       to 0, massive damage and death saves failed at 0 HP, two on a critical hit.</li>
 *   <li>A PC that drops to 0 falls unconscious and prone, and regaining hit points ends that unconsciousness;
 *       it stays prone.</li>
 *   <li>Each damage application to a concentrating creature that stays up makes one CON save, DC 10 or half
 *       the damage. Concentration ends on a failed save, at 0 HP or death, and on an incapacitating
 *       condition; ending it removes exactly the conditions its Concentration Instance created (D162), and the
 *       Spell Effects that belong to it (M15). A Spell Effect kept up without concentration, such as Spiritual
 *       Weapon, lasts its duration whatever happens to its caster (PHB p.278).</li>
 *   <li>A failed save by a creature with Legendary Resistance left is offered for undoing: its entry records
 *       the damage a success would have left and the conditions the save applied (D163). That includes the save
 *       an attack's hit calls for against some of its conditions, which leaves the hit's damage as it is.</li>
 *   <li>At the turn boundaries {@link TurnProcessor} reaches, it ends conditions, rolls Repeat Saves or waits
 *       for the player's, gives a creature its turn's actions and pools back, and marks a death save due
 *       (D162, D163).</li>
 *   <li>The class features a creature turns on for itself (M17) are entries among its conditions that only the rules
 *       read, as the Dodge action's is (D174): Rage ({@link ClassFeatures#RAGING}), which ends its concentration when
 *       it starts and ends when the creature drops to 0 HP, falls unconscious or dies, or at the end of a turn in which
 *       it hasn't attacked or taken damage since its last (PHB p.48); and Reckless Attack
 *       ({@link ClassFeatures#RECKLESS}). Damage the creature takes after its defences records that it was damaged,
 *       for Rage.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EffectApplier {

    /** The resource pool that counts a creature's Legendary Resistance uses. */
    public static final String LEGENDARY_RESISTANCE_POOL = "monster:legendary-resistance";

    // The conditions that include being incapacitated, which ends concentration (PHB p.203, appendix A)
    private static final Set<String> INCAPACITATING =
            Set.of("incapacitated", "paralyzed", "petrified", "stunned", "unconscious");

    private static final String UNCONSCIOUS = "unconscious";

    private final Resolver resolver;
    private final CreatureStats stats;
    private final CombatLogWriter combatLog;
    private final ResourcePoolService resourcePoolService;

    /**
     * Who uses an Effect, and how its entries read: the actor's name, the action and, for another target, the
     * preposition, as in "Wizard casts Fireball at Goblin" or "Rogue attacks Goblin".
     *
     * @param actor           the participant using it; null when the DM acts as no one
     * @param actorName       what the log calls the user, such as the participant's name or "DM"
     * @param actionType      the type of each target's entry
     * @param action          what the user does, such as "casts Fireball" or "uses Bite"
     * @param preposition     what joins the action to another target, such as "at" or "on"; blank for none
     * @param conditionSource what the conditions it applies record as their source: the spell or action
     * @param instance        the Concentration Instance its conditions belong to, if any (D162)
     */
    public record Use(EncounterParticipant actor, String actorName, CombatActionType actionType, String action,
                      String preposition, String conditionSource, ConcentrationInstance instance) {
    }

    /**
     * What an Effect did to one target.
     *
     * @param damageTaken                  the damage after the target's defences
     * @param damageType                   the first damage type, or null
     * @param healed                       the hit points it regained
     * @param conditionsAdded              the conditions it gained
     * @param legendaryResistanceOffered   it failed a save and has Legendary Resistance left (D163)
     * @param legendaryResistanceRemaining its Legendary Resistance uses left
     * @param description                  the entry written for it
     * @param logEntryId                   that entry's ID, as the log gave it
     * @param concentrationCheck           the concentration check its damage made the target roll, or the earlier one
     *                                     it was judged by again (PHB p.203); null when it made none
     */
    public record Applied(EncounterParticipant target, Resolver.TargetOutcome outcome, int damageTaken,
                          String damageType, int healed, List<ConditionEntry> conditionsAdded,
                          boolean legendaryResistanceOffered, int legendaryResistanceRemaining,
                          String description, UUID logEntryId, ConcentrationCheck concentrationCheck) {

        public List<String> conditionNames() {
            return conditionsAdded.stream().map(ConditionEntry::name).toList();
        }
    }

    /** What Legendary Resistance changed: hit points regained, whether that revived it, and conditions removed. */
    public record LegendaryResistanceUndo(int regained, boolean revived, List<String> conditionsRemoved) {
    }

    // ── Effects ──────────────────────────────────────────────────

    /**
     * Applies a resolved Effect to each target in turn, writing one entry per target. An attack, whether a weapon's, a
     * spell's or a monster action's, then ends its attacker's conditions that end when it attacks, as Invisibility's
     * does (PHB p.254): the attack was rolled while they lasted, so it had what they gave it.
     */
    public List<Applied> apply(Encounter encounter, Use use, Effect effect, List<Resolver.TargetOutcome> outcomes) {
        boolean attack = use.actor() != null && effect.delivery() instanceof Effect.Attack && !outcomes.isEmpty();
        Set<UUID> ending = attack ? endingOnAttackOrCast(use.actor()) : Set.of();
        List<Applied> applied = new ArrayList<>();
        for (Resolver.TargetOutcome outcome : outcomes) {
            applied.add(applyTo(encounter, use, effect, outcome, null));
        }
        endOnAttackOrCast(encounter, use.actor(), ending, "it attacks");
        return applied;
    }

    /**
     * Applies a resolved Effect whose damage is part of an earlier hit's damage, as Divine Smite's is (PHB p.85). A
     * creature makes one concentration check against the damage a hit deals (PHB p.203), so when the hit already made
     * it roll one for the same concentration, that roll is judged again against the DC of the hit's combined damage
     * instead of a second save being rolled; otherwise the added damage makes it roll as any damage does.
     *
     * @param hitCheck the concentration check the hit's damage made the target roll, or null for none
     */
    public List<Applied> applyToHit(Encounter encounter, Use use, Effect effect, List<Resolver.TargetOutcome> outcomes,
                                    ConcentrationCheck hitCheck) {
        List<Applied> applied = new ArrayList<>();
        for (Resolver.TargetOutcome outcome : outcomes) {
            applied.add(applyTo(encounter, use, effect, outcome, hitCheck));
        }
        return applied;
    }

    /** An entry about the Effect as a whole, which names no target, so it can't name a hidden one (D157). */
    public void summary(Encounter encounter, Use use, String description) {
        combatLog.write(encounter, use.actionType(), description, use.actor(), null);
    }

    private Applied applyTo(Encounter encounter, Use use, Effect effect, Resolver.TargetOutcome outcome,
                            ConcentrationCheck hitCheck) {
        EncounterParticipant target = CombatAccess.participant(encounter, outcome.targetId());
        Vitals before = vitals(target);
        Integer shownClass = CreatureStats.armourClass(target);
        int armourClass = shownClass != null ? shownClass : 0;

        Defended damage = defended(target, outcome.damage());
        Taken taken = takeDamage(target, damage.total(), outcome.outcome() == Outcome.CRITICAL);
        VitalsChange damageChange = taken.change();

        // A creature that can't regain hit points, as one Chill Touch hit, regains none (PHB p.221, D184)
        int healing = outcome.healing() > 0 && cannotRegain(target) ? 0 : outcome.healing();
        VitalsChange healingChange = DamageRules.heal(damageChange.vitals(), healing);
        setVitals(target, healingChange.vitals());
        int healed = healingChange.vitals().hp() - damageChange.vitals().hp();

        // A creature the Effect killed gets none of its conditions, and one immune to a condition doesn't get it
        List<String> immune = target.getLifeState() == LifeState.DEAD ? List.of()
                : outcome.conditions().stream().filter(name -> stats.immuneToCondition(target, name)).toList();
        List<ConditionEntry> added = target.getLifeState() == LifeState.DEAD ? List.of()
                : addEffectConditions(encounter, target,
                        outcome.conditions().stream().filter(name -> !immune.contains(name)).toList(), use, effect);

        int remaining = legendaryResistanceRemaining(target);
        // A hit's failed save gave only the conditions it guards, and gave nothing to a creature the hit killed
        boolean failedHitSave = outcome.hitSave() != null && !outcome.hitSave().success();
        List<ConditionEntry> fromSave = failedHitSave ? guardedByHitSave(effect, added) : added;
        boolean offered = remaining > 0 && (outcome.outcome() == Outcome.SAVE_FAIL
                || failedHitSave && target.getLifeState() != LifeState.DEAD);

        // A Bardic Inspiration die rolled on the roll is spent: the attacker's on an attack, the target's on a save
        if (outcome.inspiration() != null) {
            EncounterParticipant holder = effect.delivery() instanceof Effect.Attack ? use.actor() : target;
            if (holder != null) {
                spendInspiration(holder);
            }
        }
        String description = describe(use, effect, target, armourClass, outcome, damage, healed, added, immune)
                + (healing < outcome.healing() ? " " + target.getDisplayName() + " can't regain hit points ("
                        + noHealingSource(target) + ")." : "");
        CombatLog.CombatLogBuilder entry = CombatLogWriter.about(
                        combatLog.entry(encounter, use.actionType(), description), use.actor(), target)
                .rollValue(outcome.d20())
                .rollTotal(outcome.total())
                .damageDealt(damage.total() > 0 ? damage.total() : null)
                .healingDone(healed > 0 ? healed : null);
        if (offered) {
            entry.legendaryResistanceEligible(true)
                    .lrTargetId(target.getId())
                    .lrDamageDealt(hitPointsLost(before, damage.total()))
                    .lrConditionsApplied(String.join(",", fromSave.stream().map(ConditionEntry::name).toList()))
                    .lrResolved(false)
                    .lrSuccessDamage(defended(target, outcome.successDamage()).total())
                    .lrConditionIds(fromSave.stream().map(ConditionEntry::id).toList());
        }
        CombatLog saved = combatLog.save(entry);
        logReverted(encounter, target, taken);

        ConcentrationCheck check = afterDamage(encounter, target, taken.before(), damageChange, damage.total(),
                hitCheck);
        afterHealing(encounter, target, healingChange);
        afterConditions(encounter, target, added);
        // The one-shot Ongoing Modifiers the roll used are spent (D184)
        spendModifiers(encounter, outcome.spentOneShots());

        return new Applied(target, outcome, damage.total(), damage.firstType(), healed, added, offered, remaining,
                description, saved != null ? saved.getId() : null, check);
    }

    // ── Hit points ───────────────────────────────────────────────

    /**
     * The DM's damage by hand (D028, D161): untyped unless a type is given, when the target's defences count.
     *
     * @param actorName the participant named as the source, or "DM"
     */
    public void damage(Encounter encounter, EncounterParticipant actor, String actorName, EncounterParticipant target,
                       int amount, String damageType) {
        String type = damageType != null && !damageType.isBlank() ? damageType.trim().toLowerCase() : null;
        Set<Defence> defences = stats.defences(target, type);
        int total = DamageRules.applyDefences(amount, defences);
        Taken taken = takeDamage(target, total, false);
        Vitals before = taken.before();
        VitalsChange change = taken.change();

        String description = actorName + " deals " + total + " damage to " + target.getDisplayName()
                + (type != null ? " (" + type + defenceNote(defences) + ")" : "");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.DAMAGE, description),
                actor, target).damageDealt(total));
        logReverted(encounter, target, taken);
        afterDamage(encounter, target, before, change, total, null);
    }

    /** The DM's healing by hand (D028). The dead regain nothing: only Set HP revives them (D161). */
    public void heal(Encounter encounter, EncounterParticipant actor, String actorName, EncounterParticipant target,
                     int amount) {
        Vitals before = vitals(target);
        // A creature that can't regain hit points regains none (PHB p.221, D184)
        boolean blocked = amount > 0 && cannotRegain(target);
        VitalsChange change = DamageRules.heal(before, blocked ? 0 : amount);
        setVitals(target, change.vitals());
        int healed = change.vitals().hp() - before.hp();

        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.HEAL,
                actorName + " heals " + target.getDisplayName() + " for " + healed + " HP"
                        + (blocked ? " (can't regain hit points: " + noHealingSource(target) + ")" : "")), actor,
                target).healingDone(healed));
        afterHealing(encounter, target, change);
    }

    /**
     * Healing a class feature gives (M17), such as Second Wind or Lay on Hands, written as one HEAL entry that says
     * where it came from: "Vex regains 12 HP (Second Wind: 1d10(7) + 5)", or for another creature "Aria heals Vex for
     * 10 HP (Lay on Hands)". The dead regain nothing (D161).
     */
    public void heal(Encounter encounter, EncounterParticipant actor, EncounterParticipant target, int amount,
                     String source) {
        Vitals before = vitals(target);
        // A creature that can't regain hit points regains none (PHB p.221, D184)
        if (amount > 0 && cannotRegain(target)) {
            source = source + "; can't regain hit points: " + noHealingSource(target);
            amount = 0;
        }
        VitalsChange change = DamageRules.heal(before, amount);
        setVitals(target, change.vitals());
        int healed = change.vitals().hp() - before.hp();

        String description = actor == target
                ? target.getDisplayName() + " regains " + healed + " HP (" + source + ")"
                : actor.getDisplayName() + " heals " + target.getDisplayName() + " for " + healed + " HP (" + source
                        + ")";
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.HEAL, description), actor,
                target).healingDone(healed));
        afterHealing(encounter, target, change);
    }

    /**
     * The DM's Set HP / revive (D161): a correction, or the only way back from Dead, with the hit points the
     * revival gives. Temporary hit points are set exactly when given. Dropping a creature to 0 this way has
     * the same consequences as damage, except massive damage.
     */
    public void setHp(Encounter encounter, EncounterParticipant target, int hp, Integer tempHp) {
        // A druid's beast form set to 0 reverts, leaving the druid's own hit points as they were (PHB p.67)
        if (hp <= 0 && WildShapeForm.of(target).isPresent() && target.getLifeState() == LifeState.ALIVE) {
            combatLog.write(encounter, CombatActionType.HP_SET, "DM sets " + target.getDisplayName()
                    + "'s beast form to 0 HP", null, target);
            endWildShape(encounter, target, "its beast form drops to 0 HP");
            if (tempHp != null) {
                target.setHpTemp(Math.max(0, tempHp));
            }
            return;
        }
        Vitals before = vitals(target);
        // Exhaustion level 6 is death (PHB p.291): the level must come down before a revival can hold (D183)
        if (before.lifeState() == LifeState.DEAD && hp > 0
                && Exhaustion.dies(Exhaustion.clamp(target.getExhaustionLevel()))) {
            throw new ConflictException(target.getDisplayName() + " has 6 levels of exhaustion; lower the exhaustion "
                    + "level before reviving them");
        }
        VitalsChange change = DamageRules.setHp(before, hp);
        Vitals after = tempHp == null ? change.vitals() : withTempHp(change.vitals(), Math.max(0, tempHp));
        setVitals(target, after);

        String name = target.getDisplayName();
        StringBuilder description = new StringBuilder("DM sets ").append(name).append("'s HP to ").append(after.hp());
        if (tempHp != null) {
            description.append(" and temporary HP to ").append(after.tempHp());
        }
        if (before.lifeState() == LifeState.DEAD && after.lifeState() == LifeState.ALIVE) {
            description.append(": ").append(name).append(" is revived");
        } else if (before.lifeState() != LifeState.ALIVE && after.lifeState() == LifeState.ALIVE) {
            description.append(": ").append(name).append(" regains consciousness");
        } else if (change.died()) {
            description.append(": ").append(name).append(" is killed");
        } else if (change.droppedToZero()) {
            description.append(": ").append(name).append(" drops to 0 HP, unconscious and dying");
        }
        combatLog.write(encounter, CombatActionType.HP_SET, description.toString(), null, target);

        if (change.addUnconsciousAndProne()) {
            addUnconsciousAndProne(encounter, target);
        }
        if (change.concentrationEnds()) {
            endConcentrationAtZero(encounter, target);
        }
        if (change.removeUnconsciousFromDowned()) {
            removeUnconsciousFromZeroHp(target);
        }
        if (change.died()) {
            vanishAtZero(encounter, target);
        }
    }

    /**
     * A dying PC's death saving throw, rolled here (PHB p.197). A natural 20 brings it back with 1 HP and
     * ends the unconsciousness of dropping to 0.
     */
    public void deathSave(Encounter encounter, EncounterParticipant participant) {
        Vitals before = vitals(participant);
        int d20 = resolver.d20();
        // A death saving throw is a saving throw (PHB p.197), so exhaustion level 3 or more gives it disadvantage
        // (PHB p.291), and the Ongoing Modifiers on saves count: Beacon of Hope's advantage (PHB p.217), Bless's d4
        // (PHB p.219) (D184); two d20s with either mode, the higher or the lower counting
        String exhausted = "";
        int exhaustion = Exhaustion.clamp(participant.getExhaustionLevel());
        CreatureStats.SaveModifiers modifiers = CreatureStats.saveModifiers(participant, null);
        List<RollMode> modes = new ArrayList<>(modifiers.modes());
        List<String> reasons = new ArrayList<>();
        if (Exhaustion.saveDisadvantage(exhaustion)) {
            modes.add(RollMode.DISADVANTAGE);
            reasons.add(Exhaustion.conditionName(exhaustion));
        }
        RollMode mode = RollMode.combine(modes);
        if (mode != RollMode.NORMAL) {
            int second = resolver.d20();
            exhausted = " (2d20(" + d20 + ", " + second + ") with " + mode.name().toLowerCase()
                    + (reasons.isEmpty() ? "" : ", " + String.join(", ", reasons)) + ")";
            d20 = mode == RollMode.ADVANTAGE ? Math.max(d20, second) : Math.min(d20, second);
        }
        List<RollModifiers.RolledBonus> bonuses = resolver.rollBonuses(modifiers.bonuses());
        int total = d20 + Resolver.sum(bonuses);
        if (!bonuses.isEmpty()) {
            exhausted += " (" + bonusText(bonuses).trim() + " = " + total + ")";
        }
        VitalsChange change = DamageRules.deathSave(before, d20, total);
        Vitals after = change.vitals();
        setVitals(participant, after);

        String name = participant.getDisplayName();
        CombatActionType type;
        String description;
        if (d20 == 20) {
            type = CombatActionType.REVIVE;
            description = name + " rolls a natural 20 on a death save and regains 1 HP!";
        } else if (after.lifeState() == LifeState.DEAD) {
            type = CombatActionType.KILL;
            description = (d20 == 1 ? name + " rolls a natural 1 on a death save, two failures. "
                    : name + " fails a death save (roll: " + d20 + "). ") + name + " has died.";
        } else if (after.lifeState() == LifeState.STABLE) {
            type = CombatActionType.STABILIZE;
            description = name + " succeeds on a death save (roll: " + d20 + ") and is stable";
        } else if (d20 != 1 && total >= 10) {
            type = CombatActionType.DEATH_SAVE;
            description = name + " succeeds on a death save (roll: " + d20 + "): "
                    + after.deathSaveSuccesses() + "/3 successes";
        } else {
            type = CombatActionType.DEATH_SAVE;
            description = d20 == 1
                    ? name + " rolls a natural 1 on a death save, two failures (" + after.deathSaveFailures() + "/3)"
                    : name + " fails a death save (roll: " + d20 + "): " + after.deathSaveFailures() + "/3 failures";
        }
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, type, description + exhausted), null,
                participant).rollValue(d20).rollTotal(total));
        participant.setDeathSaveDue(false);

        if (change.removeUnconsciousFromDowned()) {
            removeUnconsciousFromZeroHp(participant);
        }
        spendModifiers(encounter, oneShotsOf(modifiers.bonuses()));
    }

    // ── Exhaustion ───────────────────────────────────────────────

    /**
     * Sets the creature's exhaustion level, 0 to 6 (PHB p.291, D183), logged as gaining or losing levels. Its effects
     * are read from the level wherever they apply: ability checks, attack rolls and saves, speed and the hit point
     * maximum. At level 4 or more its hit points are kept within the halved maximum, and when the level drops below 4
     * the maximum returns while its hit points stay. Level 6 kills it: a player character dies outright rather than
     * dying, and a monster dies.
     *
     * @throws ConflictException when the level would rise on a creature immune to exhaustion (MM condition
     *                           immunities) or on one already dead
     */
    public void setExhaustion(Encounter encounter, EncounterParticipant target, int level) {
        int before = Exhaustion.clamp(target.getExhaustionLevel());
        int after = Exhaustion.clamp(level);
        if (after == before) {
            return;
        }
        String name = target.getDisplayName();
        if (after > before) {
            if (stats.immuneToCondition(target, Exhaustion.CONDITION)) {
                throw new ConflictException(name + " is immune to exhaustion");
            }
            if (target.getLifeState() == LifeState.DEAD) {
                throw new ConflictException(name + " is dead");
            }
        }
        target.setExhaustionLevel(after);

        StringBuilder description = new StringBuilder();
        if (after == 0) {
            description.append(name).append(" is no longer exhausted (was level ").append(before).append(")");
        } else {
            description.append(name).append(after > before ? " gains" : " loses").append(" exhaustion: level ")
                    .append(after).append(" (was ").append(before).append("): ")
                    .append(String.join(", ", Exhaustion.effects(after)));
        }
        if (!Exhaustion.dies(after)) {
            int maximum = CreatureStats.hpMax(target);
            if (target.getHpCurrent() != null && target.getHpCurrent() > maximum) {
                target.setHpCurrent(maximum);
                description.append("; hit points reduced to the halved maximum of ").append(maximum);
            }
        }
        combatLog.write(encounter, after > before ? CombatActionType.CONDITION_ADD : CombatActionType.CONDITION_REMOVE,
                description.toString(), null, target);

        if (Exhaustion.dies(after) && target.getLifeState() != LifeState.DEAD) {
            dieOfExhaustion(encounter, target);
        }
    }

    // Exhaustion level 6 (PHB p.291): death outright, with what follows a death. A druid in beast form dies as itself,
    // since the exhaustion is the druid's
    private void dieOfExhaustion(Encounter encounter, EncounterParticipant target) {
        if (WildShapeForm.of(target).isPresent()) {
            endWildShape(encounter, target, "it dies of exhaustion");
        }
        target.setHpCurrent(0);
        target.setHpTemp(0);
        target.setLifeState(LifeState.DEAD);
        target.setDeathSaveDue(false);
        combatLog.write(encounter, CombatActionType.KILL, target.getDisplayName() + " dies of exhaustion", null,
                target);
        endRage(encounter, target, "dead");
        endConcentration(encounter, target, "dead");
        vanishAtZero(encounter, target);
    }

    // ── Conditions ───────────────────────────────────────────────

    /**
     * A condition added by hand, by the DM or a player on their own character (D028): it ends when removed, or
     * after its duration. A condition of the same name already there makes this do nothing. An incapacitating
     * condition ends the creature's concentration.
     */
    public void addCondition(Encounter encounter, EncounterParticipant target, String condition, Integer duration) {
        String name = condition.trim().toLowerCase();
        List<ConditionEntry> conditions = conditions(target);
        if (conditions.stream().anyMatch(c -> c.name().equalsIgnoreCase(name))) {
            return;
        }
        conditions.add(ConditionEntry.manual(name, duration, encounter.getRoundNumber()));
        target.setActiveConditions(conditions);
        combatLog.write(encounter, CombatActionType.CONDITION_ADD, target.getDisplayName() + " gains condition: " + name
                + (duration != null ? " (" + duration + " rounds)" : ""), null, target);
        if (INCAPACITATING.contains(name)) {
            endConcentration(encounter, target, name);
        }
        if (UNCONSCIOUS.equals(name)) {
            endRage(encounter, target, UNCONSCIOUS);
            endWildShape(encounter, target, "falls unconscious");
        }
    }

    /**
     * Removes every condition of the name by hand, whatever its source. Every removal is logged, so the DM sees
     * what players remove (D162).
     */
    public void removeCondition(Encounter encounter, EncounterParticipant target, String condition) {
        String name = condition.trim().toLowerCase();
        // Removing a Wild Shape's entry ends the Wild Shape, with the druid's own hit points and AC back
        if (BeastShapes.ENTRY.equals(name) && WildShapeForm.of(target).isPresent()) {
            endWildShape(encounter, target, "removed by hand");
            return;
        }
        List<ConditionEntry> conditions = conditions(target);
        if (!conditions.removeIf(c -> c.name().equalsIgnoreCase(name))) {
            return;
        }
        target.setActiveConditions(conditions.isEmpty() ? null : conditions);
        combatLog.write(encounter, CombatActionType.CONDITION_REMOVE,
                target.getDisplayName() + " loses condition: " + name, null, target);
    }

    // ── Turn boundaries ──────────────────────────────────────────

    /**
     * Ends one of the participant's conditions as its end rule says, at the turn boundary {@link TurnProcessor}
     * reached (D162), writing why, as in "blinded ends on Goblin (its duration ends)". Nothing happens when the
     * condition is already gone.
     */
    public void endCondition(Encounter encounter, EncounterParticipant participant, UUID conditionId, String why) {
        List<ConditionEntry> conditions = conditions(participant);
        Optional<ConditionEntry> ended = find(conditions, conditionId);
        if (ended.isEmpty()) {
            return;
        }
        conditions.remove(ended.get());
        participant.setActiveConditions(conditions.isEmpty() ? null : conditions);
        combatLog.write(encounter, CombatActionType.CONDITION_REMOVE,
                ended.get().name() + " ends on " + participant.getDisplayName() + " (" + why + ")", null, participant);
    }

    /**
     * The IDs of the participant's conditions that end when it attacks or casts a spell, as the Invisibility spell's
     * invisibility (PHB p.254) and Cloak of Shadows' (PHB p.63) do. Taken before the attack or spell, so that a spell
     * the creature casts on itself, such as Invisibility, doesn't end what it has just given.
     */
    public static Set<UUID> endingOnAttackOrCast(EncounterParticipant participant) {
        return conditions(participant).stream()
                .filter(ConditionEntry::endsWhenAttackingOrCasting)
                .map(ConditionEntry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Ends those of the conditions, by ID, the participant still has, once its attack or spell is resolved, writing
     * why, as in "invisible ends on Rogue (it attacks)". Nothing happens for none.
     */
    public void endOnAttackOrCast(Encounter encounter, EncounterParticipant participant, Set<UUID> conditionIds,
                                  String why) {
        for (UUID id : conditionIds) {
            endCondition(encounter, participant, id, why);
        }
    }

    /**
     * The participant's Repeat Save against one of its conditions, rolled here and logged as REPEAT_SAVE (D162):
     * a success ends the condition. A creature with Legendary Resistance left that fails is offered it, which
     * would end the condition (D163). Nothing happens when the condition is gone or has no Repeat Save.
     */
    public void repeatSave(Encounter encounter, EncounterParticipant participant, UUID conditionId) {
        List<ConditionEntry> conditions = conditions(participant);
        Optional<ConditionEntry> found = find(conditions, conditionId);
        if (found.isEmpty() || found.get().repeatSave() == null) {
            return;
        }
        ConditionEntry condition = found.get();
        RepeatSave save = condition.repeatSave();
        int modifier = stats.saveModifier(participant, save.ability());
        CreatureStats.SaveModifiers modifiers = CreatureStats.saveModifiers(participant, save.ability());
        Resolver.SaveRoll roll = resolver.savingThrow(save.ability(), modifier, save.dc(),
                CreatureStats.conditionNames(participant), stats.armourPenalty(participant), modifiers.modes(),
                modifiers.bonuses());

        String name = participant.getDisplayName();
        StringBuilder description = new StringBuilder(name).append(" repeats the ").append(save.ability().name())
                .append(" save against ").append(condition.name());
        if (roll.autoFailed()) {
            description.append(" vs DC ").append(save.dc()).append(" — fails automatically");
        } else {
            description.append(": ").append(saveDice(roll)).append(signed(modifier)).append(bonusText(roll.bonuses()))
                    .append(" = ").append(roll.total()).append(" vs DC ").append(save.dc())
                    .append(roll.success() ? " — succeeds" : " — fails");
        }
        description.append(roll.success() ? ", and " + condition.name() + " ends." : ", and stays " + condition.name() + ".");

        int remaining = legendaryResistanceRemaining(participant);
        CombatLog.CombatLogBuilder entry = CombatLogWriter.about(combatLog.entry(encounter,
                        CombatActionType.REPEAT_SAVE, description.toString()), null, participant)
                .rollValue(roll.d20())
                .rollTotal(roll.total());
        if (!roll.success() && remaining > 0) {
            entry.legendaryResistanceEligible(true)
                    .lrTargetId(participant.getId())
                    .lrDamageDealt(0)
                    .lrConditionsApplied(condition.name())
                    .lrResolved(false)
                    .lrSuccessDamage(0)
                    .lrConditionIds(List.of(condition.id()));
        }
        combatLog.save(entry);

        int index = conditions.indexOf(condition);
        if (roll.success()) {
            conditions.remove(index);
        } else {
            conditions.set(index, condition.withRepeatSave(save.withAwaitingRoll(false)));
        }
        participant.setActiveConditions(conditions.isEmpty() ? null : conditions);
        spendModifiers(encounter, roll.spentOneShots());
    }

    /**
     * Leaves the participant's Repeat Save against the condition for its player to roll, as the campaign's
     * prompt_player_repeat_saves asks (D162).
     */
    public void awaitRepeatSave(EncounterParticipant participant, UUID conditionId) {
        List<ConditionEntry> conditions = conditions(participant);
        for (int i = 0; i < conditions.size(); i++) {
            ConditionEntry condition = conditions.get(i);
            if (conditionId.equals(condition.id()) && condition.repeatSave() != null) {
                conditions.set(i, condition.withRepeatSave(condition.repeatSave().withAwaitingRoll(true)));
                participant.setActiveConditions(conditions);
                return;
            }
        }
    }

    /**
     * The start of the participant's turn gives it a fresh turn economy, with its action, bonus action, reaction
     * and object interaction back (D174), refills its per-turn pools, such as legendary actions, and rolls the d6 of
     * each spent Recharge ability, logging the roll (M12, D146).
     */
    public void refreshTurn(Encounter encounter, EncounterParticipant participant) {
        // Rage asks whether it attacked or took damage since its last turn ended, which the new turn doesn't reset
        participant.setTurnEconomy(TurnEconomy.fresh().withAttackedOrDamaged(economy(participant).attackedOrDamaged()));

        List<ResourcePoolEntry> pools = participant.getResourcePoolsCurrent();
        if (pools == null || pools.isEmpty()) {
            return;
        }
        ResourcePoolService.ResetPoolsResult reset = resourcePoolService.resetPools(pools, "turn", Map.of());
        participant.setResourcePoolsCurrent(reset.pools());

        for (ResourcePoolService.RechargeLogEntry recharge : reset.rechargeResults()) {
            String dice = recharge.checkExpression() != null
                    ? recharge.checkExpression().replaceAll(">=.*", "") : "1d6";
            String name = participant.getDisplayName();
            String description = name + " rolls " + dice + " (" + recharge.rollValue() + "). "
                    + (recharge.success() ? name + " recharges " + recharge.displayName() + "!"
                    : name + " fails to recharge " + recharge.displayName() + ".");
            combatLog.save(CombatLogWriter.about(combatLog.entry(encounter,
                    recharge.success() ? CombatActionType.RECHARGE_SUCCESS : CombatActionType.RECHARGE_FAILURE,
                    description), participant, participant).rollValue(recharge.rollValue()));
        }
    }

    /** A Dying PC's turn starts, so it is due a death save (D163); rolling one or no longer dying clears it. */
    public void deathSaveDue(EncounterParticipant participant) {
        if (participant.getParticipantType() == ParticipantType.PLAYER
                && participant.getLifeState() == LifeState.DYING) {
            participant.setDeathSaveDue(true);
        }
    }

    // ── Concentration ────────────────────────────────────────────

    /**
     * Starts a Concentration Instance for the caster, ending the one it holds first, so the new casting's
     * conditions are never the ones removed (D162).
     */
    public ConcentrationInstance startConcentration(Encounter encounter, EncounterParticipant caster, String spell,
                                                    Integer slotLevel, Integer durationRounds) {
        endConcentration(encounter, caster, "starts concentrating on " + spell);
        ConcentrationInstance instance = ConcentrationInstance.start(spell, slotLevel, encounter.getRoundNumber(),
                durationRounds);
        caster.setConcentration(instance);
        return instance;
    }

    /**
     * Ends the participant's Concentration Instance, if it holds one, removing exactly the conditions it
     * created, on whoever has them (D162).
     *
     * @param why what ended it, for the log, or null
     */
    public void endConcentration(Encounter encounter, EncounterParticipant caster, String why) {
        ConcentrationInstance instance = caster.getConcentration();
        if (instance == null) {
            return;
        }
        caster.setConcentration(null);
        combatLog.write(encounter, CombatActionType.CONCENTRATION_LOST, caster.getDisplayName()
                + " loses concentration on " + instance.spell() + (why != null ? " (" + why + ")" : ""), null, caster);
        for (SpellEffectEntry effect : effects(caster)) {
            if (instance.id().equals(effect.concentrationInstanceId())) {
                endEffect(encounter, caster, effect.id(), "concentration lost");
            }
        }

        for (EncounterParticipant participant : encounter.getParticipants()) {
            List<ConditionEntry> conditions = conditions(participant);
            List<ConditionEntry> ended = conditions.stream()
                    .filter(c -> instance.id().equals(c.concentrationInstanceId()))
                    .toList();
            if (ended.isEmpty()) {
                continue;
            }
            conditions.removeAll(ended);
            participant.setActiveConditions(conditions.isEmpty() ? null : conditions);
            for (ConditionEntry condition : ended) {
                combatLog.write(encounter, CombatActionType.CONDITION_REMOVE, condition.name() + " ends on "
                        + participant.getDisplayName() + " (" + instance.spell() + " concentration lost)", null,
                        participant);
            }
        }
    }

    // ── Spell Effects (M15) ──────────────────────────────────────

    /**
     * Leaves a Spell Effect on its caster: a lasting spell's beam, weapon or area, linked to the Concentration
     * Instance the casting started, or to none for a spell that needs no concentration. A spell that needs none ends
     * the effect its caster's earlier casting left, as Spiritual Weapon's does "until you cast this spell again"
     * (PHB p.278); a concentration spell's earlier effect has already ended with its concentration.
     */
    public SpellEffectEntry startEffect(Encounter encounter, EncounterParticipant caster, String spell,
                                        Integer slotLevel, ConcentrationInstance instance, Integer durationRounds,
                                        Integer uses) {
        return startEffect(encounter, caster, spell, slotLevel, instance, durationRounds, uses, true);
    }

    /**
     * As {@link #startEffect(Encounter, EncounterParticipant, String, Integer, ConcentrationInstance, Integer, Integer)};
     * without replaceEarlier, an earlier casting's effect stays, as a second Mage Armor on another creature leaves the
     * first (D184).
     */
    public SpellEffectEntry startEffect(Encounter encounter, EncounterParticipant caster, String spell,
                                        Integer slotLevel, ConcentrationInstance instance, Integer durationRounds,
                                        Integer uses, boolean replaceEarlier) {
        if (instance == null && replaceEarlier) {
            for (SpellEffectEntry earlier : effects(caster)) {
                if (earlier.concentrationInstanceId() == null && earlier.spell().equalsIgnoreCase(spell)) {
                    endEffect(encounter, caster, earlier.id(), "cast again");
                }
            }
        }
        SpellEffectEntry effect = SpellEffectEntry.start(spell, slotLevel, instance != null ? instance.id() : null,
                encounter.getRoundNumber(), durationRounds, uses);
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        effects.add(effect);
        caster.setActiveEffects(effects);
        return effect;
    }

    /**
     * Ends one of the caster's Spell Effects, logging why; nothing when it has already ended. It ends only the effect:
     * a caller ending a concentration spell's effect by hand ends the concentration, which ends the effect.
     *
     * @param why what ended it, for the log, such as "its duration ends"
     */
    public void endEffect(Encounter encounter, EncounterParticipant caster, UUID effectId, String why) {
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        Optional<SpellEffectEntry> ended = effects.stream().filter(effect -> effect.id().equals(effectId)).findFirst();
        if (ended.isEmpty()) {
            return;
        }
        effects.remove(ended.get());
        caster.setActiveEffects(effects);
        combatLog.write(encounter, CombatActionType.SPELL_EFFECT_END, caster.getDisplayName() + "'s "
                + ended.get().spell() + " ends (" + why + ")", null, caster);
        afterModifiersEnd(encounter, ended.get(), ended.get().affected());
        // A summoning's creatures leave with it: its concentration lost, its duration over, or ended by hand (D182)
        dismissSummons(encounter, effectId, ended.get().spell() + " ends");
    }

    // ── Ongoing Modifiers (D184) ─────────────────────────────────

    /**
     * Puts Ongoing Modifiers on one of the caster's Spell Effects, on these creatures (D184). Aid's hit points come with
     * its higher maximum: each living creature it reaches gains them now (PHB p.211).
     *
     * @param only true when the effect exists only for its modifiers, so it ends with the last of them
     */
    public SpellEffectEntry startModifiers(Encounter encounter, EncounterParticipant caster, UUID effectId,
                                           List<EncounterParticipant> affected, List<OngoingModifier> modifiers,
                                           String option, SpellEffectEntry.EffectSave repeatSave, String endReminder,
                                           boolean only) {
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        SpellEffectEntry changed = null;
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i).id().equals(effectId)) {
                changed = effects.get(i).withModifiers(affected.stream().map(EncounterParticipant::getId).toList(),
                        modifiers, option, repeatSave, endReminder, only);
                effects.set(i, changed);
            }
        }
        if (changed == null) {
            throw new IllegalArgumentException("No Spell Effect " + effectId);
        }
        caster.setActiveEffects(effects);
        int raised = modifiers.stream().filter(m -> m.kind() == OngoingModifier.Kind.HP_MAX)
                .mapToInt(OngoingModifier::flat).sum();
        if (raised > 0) {
            for (EncounterParticipant creature : affected) {
                if (creature.getLifeState() == LifeState.ALIVE) {
                    creature.setHpCurrent(Math.min(CreatureStats.hpMax(creature), creature.getHpCurrent() + raised));
                }
            }
        }
        return changed;
    }

    /**
     * Spends the one-shot Ongoing Modifiers a roll used (D184), such as Resistance's d4 (PHB p.272) or Vicious
     * Mockery's disadvantage (PHB p.285), logging each. An effect that exists only for its modifiers ends once the last
     * is spent, and its concentration with it, as Guidance's "the spell then ends" (PHB p.248).
     */
    public void spendModifiers(Encounter encounter, Set<UUID> modifierIds) {
        if (modifierIds == null || modifierIds.isEmpty()) {
            return;
        }
        for (EncounterParticipant caster : List.copyOf(encounter.getParticipants())) {
            for (SpellEffectEntry effect : effects(caster)) {
                List<OngoingModifier> spent = effect.modifiers().stream()
                        .filter(modifier -> modifierIds.contains(modifier.id()))
                        .toList();
                if (spent.isEmpty()) {
                    continue;
                }
                combatLog.write(encounter, CombatActionType.SPELL_EFFECT_END, caster.getDisplayName() + "'s "
                        + ActiveModifiers.label(effect) + " is used: " + spent.stream().map(OngoingModifier::describe)
                        .collect(Collectors.joining("; ")), null, caster);
                keepModifiers(encounter, caster, effect, effect.modifiers().stream()
                        .filter(modifier -> !modifierIds.contains(modifier.id())).toList(), "used");
            }
        }
    }

    /** The one-shot modifiers among bonuses, which rolling them spends. */
    public static Set<UUID> oneShotsOf(List<RollModifiers.Bonus> bonuses) {
        Set<UUID> spent = new java.util.HashSet<>();
        for (RollModifiers.Bonus bonus : bonuses) {
            if (bonus.oneShot() && bonus.modifierId() != null) {
                spent.add(bonus.modifierId());
            }
        }
        return spent;
    }

    /**
     * The start of a creature's turn for the Ongoing Modifiers (D184): those that last until the start of its next turn
     * as their caster end, as Ray of Frost's -10 feet (PHB p.271); those that last until the end of its next turn, as
     * their caster or as the creature they affect, are armed, so that turn's end ends them; and Heroism gives the
     * creature its temporary hit points, which don't add to any it has (PHB p.250, p.198).
     */
    public void modifiersAtTurnStart(Encounter encounter, EncounterParticipant creature) {
        UUID id = creature.getId();
        for (EncounterParticipant caster : List.copyOf(encounter.getParticipants())) {
            for (SpellEffectEntry effect : effects(caster)) {
                boolean casterTurn = caster.getId().equals(id);
                boolean affectedTurn = !effect.affected().isEmpty() && effect.affected().getFirst().equals(id);
                List<OngoingModifier> kept = new ArrayList<>();
                boolean changed = false;
                for (OngoingModifier modifier : effect.modifiers()) {
                    if (modifier.until() == OngoingModifier.Until.CASTER_TURN_START && casterTurn) {
                        changed = true;
                        continue;
                    }
                    boolean arms = !modifier.armed()
                            && ((modifier.until() == OngoingModifier.Until.CASTER_TURN_END && casterTurn)
                            || (modifier.until() == OngoingModifier.Until.TARGET_TURN_END && affectedTurn));
                    kept.add(arms ? modifier.armedNow() : modifier);
                    changed |= arms;
                }
                if (changed) {
                    keepModifiers(encounter, caster, effect, kept, "its time is up");
                }
            }
        }
        // Heroism: temporary hit points equal to the caster's modifier at the start of each of its turns (PHB p.250)
        for (ActiveModifiers.Found found : ActiveModifiers.on(creature)) {
            OngoingModifier modifier = found.modifier();
            if (modifier.kind() != OngoingModifier.Kind.TEMP_HP_EACH_TURN || modifier.flat() <= 0
                    || creature.getLifeState() == LifeState.DEAD) {
                continue;
            }
            int held = creature.getHpTemp() != null ? creature.getHpTemp() : 0;
            if (modifier.flat() > held) {
                creature.setHpTemp(modifier.flat());
                combatLog.write(encounter, CombatActionType.SPELL_EFFECT_REPEAT, creature.getDisplayName() + " gains "
                        + modifier.flat() + " temporary hit points from " + found.effect().spell(), found.caster(),
                        creature);
            }
        }
    }

    /**
     * The end of a creature's turn for the Ongoing Modifiers (D184): those armed at its start end, as Blade Ward's
     * resistance at the end of its caster's next turn (PHB p.218) or Vicious Mockery's disadvantage at the end of its
     * target's (PHB p.285); and each Spell Effect whose creatures repeat a save at the end of their turns, as Slow's
     * Wisdom save (PHB p.277), rolls it for this creature, a success ending the modifiers for it.
     */
    public void modifiersAtTurnEnd(Encounter encounter, EncounterParticipant creature) {
        UUID id = creature.getId();
        for (EncounterParticipant caster : List.copyOf(encounter.getParticipants())) {
            for (SpellEffectEntry effect : effects(caster)) {
                boolean casterTurn = caster.getId().equals(id);
                boolean affectedTurn = !effect.affected().isEmpty() && effect.affected().getFirst().equals(id);
                List<OngoingModifier> kept = effect.modifiers().stream()
                        .filter(modifier -> !(modifier.armed()
                                && ((modifier.until() == OngoingModifier.Until.CASTER_TURN_END && casterTurn)
                                || (modifier.until() == OngoingModifier.Until.TARGET_TURN_END && affectedTurn))))
                        .toList();
                if (kept.size() != effect.modifiers().size()) {
                    keepModifiers(encounter, caster, effect, kept, "its time is up");
                }
            }
        }
        if (creature.getLifeState() == LifeState.DEAD) {
            return;
        }
        for (EncounterParticipant caster : List.copyOf(encounter.getParticipants())) {
            for (SpellEffectEntry effect : effects(caster)) {
                if (effect.repeatSave() != null && effect.affected().contains(id)) {
                    repeatModifierSave(encounter, caster, effect, creature);
                }
            }
        }
    }

    // A creature's save at the end of its turn against a Spell Effect's modifiers, as Slow's (PHB p.277)
    private void repeatModifierSave(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect,
                                    EncounterParticipant creature) {
        Optional<Ability> ability = Ability.parse(effect.repeatSave().ability());
        if (ability.isEmpty()) {
            return;
        }
        int dc = effect.repeatSave().dc();
        int modifier = stats.saveModifier(creature, ability.get());
        CreatureStats.SaveModifiers modifiers = CreatureStats.saveModifiers(creature, ability.get());
        Resolver.SaveRoll roll = resolver.savingThrow(ability.get(), modifier, dc,
                CreatureStats.conditionNames(creature), stats.armourPenalty(creature), modifiers.modes(),
                modifiers.bonuses());
        String name = creature.getDisplayName();
        String rolled = roll.autoFailed() ? " vs DC " + dc + " — fails automatically"
                : ": " + saveDice(roll) + signed(modifier) + bonusText(roll.bonuses()) + " = " + roll.total()
                        + " vs DC " + dc + (roll.success() ? " — succeeds" : " — fails");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.REPEAT_SAVE,
                        name + " repeats the " + ability.get().name() + " save against " + effect.spell() + rolled
                                + (roll.success() ? ", and " + effect.spell() + " ends for " + name + "."
                                : ", and stays under " + effect.spell() + ".")), null, creature)
                .rollValue(roll.d20()).rollTotal(roll.total()));
        spendModifiers(encounter, roll.spentOneShots());
        if (roll.success()) {
            release(encounter, caster, effect.id(), creature, "succeeds on the save");
        }
    }

    /**
     * Ends a Spell Effect's modifiers for one creature (D184), as a successful save against Slow does for the saver
     * (PHB p.277). An effect that exists only for its modifiers and has nobody left ends, with its concentration.
     */
    public void release(Encounter encounter, EncounterParticipant caster, UUID effectId, EncounterParticipant creature,
                        String why) {
        Optional<SpellEffectEntry> found = effects(caster).stream()
                .filter(effect -> effect.id().equals(effectId))
                .findFirst();
        if (found.isEmpty() || !found.get().affected().contains(creature.getId())) {
            return;
        }
        SpellEffectEntry effect = found.get();
        List<UUID> left = effect.affected().stream().filter(id -> !id.equals(creature.getId())).toList();
        replaceEffect(caster, effect.withAffected(left));
        afterModifiersEnd(encounter, effect, List.of(creature.getId()));
        if (left.isEmpty() && effect.onlyForModifiers()) {
            endWholeEffect(encounter, caster, effect, "nobody is left under it (" + why + ")");
        }
    }

    /**
     * Moves a Spell Effect's modifiers to another creature (D184), as Hunter's Mark and Hex move to a new creature once
     * theirs drops to 0 hit points (PHB p.251).
     */
    public SpellEffectEntry moveTo(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect,
                                   EncounterParticipant creature) {
        SpellEffectEntry moved = effect.withAffected(List.of(creature.getId()));
        replaceEffect(caster, moved);
        combatLog.write(encounter, CombatActionType.SPELL_EFFECT_REPEAT, caster.getDisplayName() + " moves "
                + effect.spell() + " to " + creature.getDisplayName(), caster, creature);
        return moved;
    }

    // Keeps only these of the effect's modifiers; one that exists only for its modifiers and has none left ends
    private void keepModifiers(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect,
                               List<OngoingModifier> kept, String why) {
        replaceEffect(caster, effect.withModifiersLeft(kept));
        if (kept.isEmpty() && effect.onlyForModifiers()) {
            endWholeEffect(encounter, caster, effect, why);
        }
    }

    // Ends an effect, and the concentration it belongs to, which ends it too
    private void endWholeEffect(Encounter encounter, EncounterParticipant caster, SpellEffectEntry effect,
                                String why) {
        ConcentrationInstance held = caster.getConcentration();
        if (held != null && held.id().equals(effect.concentrationInstanceId())) {
            endConcentration(encounter, caster, effect.spell() + " " + why);
        } else {
            endEffect(encounter, caster, effect.id(), why);
        }
    }

    private static void replaceEffect(EncounterParticipant caster, SpellEffectEntry changed) {
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i).id().equals(changed.id())) {
                effects.set(i, changed);
            }
        }
        caster.setActiveEffects(effects);
    }

    /*
     * What follows a Spell Effect's modifiers ending for some creatures (D184): the reminder its spell gives, such as
     * Haste's lethargy (PHB p.250), and hit points kept within a maximum Aid no longer raises (PHB p.211).
     */
    private void afterModifiersEnd(Encounter encounter, SpellEffectEntry effect, List<UUID> creatures) {
        boolean raisedMaximum = effect.modifiers().stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.HP_MAX);
        for (UUID id : creatures) {
            Optional<EncounterParticipant> creature = encounter.getParticipants().stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst();
            if (creature.isEmpty()) {
                continue;
            }
            EncounterParticipant affected = creature.get();
            if (raisedMaximum && affected.getHpCurrent() != null
                    && affected.getHpCurrent() > CreatureStats.hpMax(affected)) {
                affected.setHpCurrent(CreatureStats.hpMax(affected));
            }
            if (effect.endReminder() != null && !effect.endReminder().isBlank()) {
                combatLog.write(encounter, CombatActionType.SPELL_EFFECT_END, effect.endReminder()
                        .replace("{target}", affected.getDisplayName()), null, affected);
            }
        }
    }

    // Whether the creature can't regain hit points now (Chill Touch, PHB p.221)
    private static boolean cannotRegain(EncounterParticipant target) {
        return OngoingModifiers.noHealing(ActiveModifiers.modifiersOn(target));
    }

    // The spell that keeps the creature from regaining hit points
    private static String noHealingSource(EncounterParticipant target) {
        return ActiveModifiers.on(target).stream()
                .filter(found -> found.modifier().kind() == OngoingModifier.Kind.NO_HEALING)
                .map(found -> found.effect().spell())
                .findFirst()
                .orElse("a spell");
    }

    // ── Summoned creatures (D182) ────────────────────────────────

    /**
     * Leaves a summoning spell's Spell Effect on its caster (D182), which owns the creatures it brings: linked to the
     * Concentration Instance the casting started, or to none. Unlike {@link #startEffect}, an earlier casting's effect
     * stays, as Animate Dead's undead stay when it is cast again (PHB p.212); the caller ends one the spell replaces,
     * such as an earlier familiar (PHB p.240).
     */
    public SpellEffectEntry startSummonEffect(Encounter encounter, EncounterParticipant caster, String spell,
                                              Integer slotLevel, ConcentrationInstance instance,
                                              Integer durationRounds, SummonSpec summon) {
        SpellEffectEntry effect = SpellEffectEntry.start(spell, slotLevel, instance != null ? instance.id() : null,
                encounter.getRoundNumber(), durationRounds, null).withSummon(summon);
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        effects.add(effect);
        caster.setActiveEffects(effects);
        return effect;
    }

    /** Puts the summoning on the caster's Spell Effect, as when the DM places its creatures. */
    public SpellEffectEntry updateSummon(EncounterParticipant caster, UUID effectId, SummonSpec summon) {
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i).id().equals(effectId)) {
                SpellEffectEntry changed = effects.get(i).withSummon(summon);
                effects.set(i, changed);
                caster.setActiveEffects(effects);
                return changed;
            }
        }
        throw new IllegalArgumentException("No Spell Effect " + effectId);
    }

    /** The creatures still in the fight that the Spell Effect summoned. */
    public static List<EncounterParticipant> summonedBy(Encounter encounter, UUID effectId) {
        return encounter.getParticipants().stream()
                .filter(p -> !Boolean.TRUE.equals(p.getDismissed()) && p.getSummon() != null
                        && effectId.equals(p.getSummon().effectId()))
                .toList();
    }

    /** Dismisses every creature the Spell Effect summoned that is still in the fight. */
    public void dismissSummons(Encounter encounter, UUID effectId, String why) {
        for (EncounterParticipant creature : summonedBy(encounter, effectId)) {
            dismiss(encounter, creature, why);
        }
    }

    /**
     * Ends every summoning of the caster's and dismisses every creature it summoned, as when the caster leaves the
     * encounter (D182): with nobody to command them, they go.
     */
    public void dismissSummonsOf(Encounter encounter, EncounterParticipant caster, String why) {
        for (SpellEffectEntry effect : effects(caster)) {
            if (effect.summon() != null) {
                endEffect(encounter, caster, effect.id(), why);
            }
        }
        for (EncounterParticipant creature : List.copyOf(encounter.getParticipants())) {
            if (!Boolean.TRUE.equals(creature.getDismissed())
                    && caster.getId().equals(creature.getSummonedByParticipantId())) {
                dismiss(encounter, creature, why);
            }
        }
    }

    /**
     * A summoned creature disappears (D182): it leaves the turn order and both views but keeps its row, so undo can
     * bring it back. Its own concentration ends. A summoning kept up without concentration, such as Find Familiar's,
     * ends once its last creature has gone; one kept up by concentration goes on until it ends, as its spell does.
     *
     * @param why what made it disappear, for the log
     */
    public void dismiss(Encounter encounter, EncounterParticipant creature, String why) {
        dismiss(encounter, creature, why, true);
    }

    /**
     * As {@link #dismiss(Encounter, EncounterParticipant, String)}; without endEmpty, a summoning left with no
     * creatures goes on, as when the DM replaces them.
     */
    public void dismiss(Encounter encounter, EncounterParticipant creature, String why, boolean endEmpty) {
        if (Boolean.TRUE.equals(creature.getDismissed())) {
            return;
        }
        endConcentration(encounter, creature, "disappears");
        creature.setDismissed(true);
        combatLog.write(encounter, CombatActionType.SUMMON_END, creature.getDisplayName() + " disappears (" + why + ")",
                null, creature);
        SummonLink link = creature.getSummon();
        if (!endEmpty || link == null || creature.getSummonedByParticipantId() == null) {
            return;
        }
        encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(creature.getSummonedByParticipantId()))
                .findFirst()
                .ifPresent(caster -> effects(caster).stream()
                        .filter(effect -> effect.id().equals(link.effectId()))
                        .filter(effect -> effect.concentrationInstanceId() == null && effect.summon() != null
                                && !effect.summon().awaitingDm())
                        .filter(effect -> summonedBy(encounter, effect.id()).isEmpty())
                        .findFirst()
                        .ifPresent(effect -> endEffect(encounter, caster, effect.id(), "its creatures are gone")));
    }

    // A summoned creature that disappears at 0 hit points does so once it dies there (PHB p.225, p.240; XGE p.167;
    // TCE p.109); Animate Dead's undead and a tiny servant stay, dead (PHB p.212, XGE p.168)
    private void vanishAtZero(Encounter encounter, EncounterParticipant target) {
        if (target.getSummon() != null && target.getSummon().vanishesAtZero()
                && target.getLifeState() == LifeState.DEAD) {
            dismiss(encounter, target, "drops to 0 hit points");
        }
    }

    /**
     * The command a caster gives its creature with a bonus action (PHB p.212, XGE p.168), which lets the caster's
     * player act for it until the creature's next turn ends; null clears it.
     */
    public void command(EncounterParticipant creature, Integer round) {
        if (creature.getSummon() != null) {
            creature.setSummon(creature.getSummon().withCommandedRound(round));
        }
    }

    /** Spends uses of a Spell Effect that has a count, such as meteors, leaving the effect in place. */
    public SpellEffectEntry spendEffectUses(EncounterParticipant caster, UUID effectId, int uses) {
        List<SpellEffectEntry> effects = new ArrayList<>(effects(caster));
        for (int i = 0; i < effects.size(); i++) {
            SpellEffectEntry effect = effects.get(i);
            if (effect.id().equals(effectId) && effect.usesLeft() != null) {
                SpellEffectEntry spent = effect.withUsesLeft(Math.max(0, effect.usesLeft() - uses));
                effects.set(i, spent);
                caster.setActiveEffects(effects);
                return spent;
            }
        }
        return effects.stream().filter(effect -> effect.id().equals(effectId)).findFirst().orElse(null);
    }

    /** The participant's Spell Effects, never null. */
    public static List<SpellEffectEntry> effects(EncounterParticipant participant) {
        return participant.getActiveEffects() == null ? List.of()
                : participant.getActiveEffects().stream().filter(Objects::nonNull).toList();
    }

    // ── Legendary Resistance ─────────────────────────────────────

    /**
     * Turns the failed save an entry recorded into a success (D163): the target regains the damage beyond what
     * a success would have left, which brings back a monster the save killed, and loses exactly the conditions
     * the save applied. Concentration the save ended stays ended.
     */
    public LegendaryResistanceUndo undoFailedSave(EncounterParticipant target, CombatLog entry) {
        int lost = entry.getLrDamageDealt() != null ? entry.getLrDamageDealt() : 0;
        int refund = Math.max(0, lost - entry.getLrSuccessDamage());
        Vitals before = vitals(target);
        int regained = 0;
        boolean revived = false;
        if (refund > 0) {
            VitalsChange change = before.lifeState() == LifeState.DEAD
                    ? DamageRules.setHp(before, refund)
                    : DamageRules.heal(before, refund);
            setVitals(target, change.vitals());
            regained = change.vitals().hp() - before.hp();
            revived = before.lifeState() == LifeState.DEAD && change.vitals().lifeState() == LifeState.ALIVE;
            if (change.removeUnconsciousFromDowned()) {
                removeUnconsciousFromZeroHp(target);
            }
        }

        Set<UUID> ids = entry.getLrConditionIds() != null ? new HashSet<>(entry.getLrConditionIds()) : Set.of();
        List<ConditionEntry> conditions = conditions(target);
        List<ConditionEntry> removed = conditions.stream().filter(c -> ids.contains(c.id())).toList();
        if (!removed.isEmpty()) {
            conditions.removeAll(removed);
            target.setActiveConditions(conditions.isEmpty() ? null : conditions);
        }
        return new LegendaryResistanceUndo(regained, revived, removed.stream().map(ConditionEntry::name).toList());
    }

    /** The participant's Legendary Resistance uses left, 0 without the pool. */
    public static int legendaryResistanceRemaining(EncounterParticipant participant) {
        if (participant.getResourcePoolsCurrent() == null) {
            return 0;
        }
        return participant.getResourcePoolsCurrent().stream()
                .filter(pool -> LEGENDARY_RESISTANCE_POOL.equals(pool.poolId()))
                .findFirst()
                .map(ResourcePoolEntry::currentUses)
                .orElse(0);
    }

    // ── Class features (M17) ─────────────────────────────────────

    /**
     * The Dodge action's entry, replacing one left from earlier, ending at the start of the dodger's next turn (PHB
     * p.192, D162, D174), whether it came from the action or from Patient Defense (PHB p.78).
     *
     * @param source what took it, which the entry records, such as "Dodge" or "Patient Defense"
     */
    public void dodge(Encounter encounter, EncounterParticipant participant, String source) {
        List<ConditionEntry> conditions = conditions(participant);
        conditions.removeIf(c -> StandardActions.DODGING.equalsIgnoreCase(c.name()));
        conditions.add(new ConditionEntry(UUID.randomUUID(), StandardActions.DODGING, null,
                encounter.getRoundNumber(), source, participant.getId(), null, null,
                EndRule.startOfTurn(participant.getId()), null));
        participant.setActiveConditions(conditions);
    }

    /**
     * The barbarian starts raging (PHB p.48): its {@link ClassFeatures#RAGING} entry lasts 1 minute, ending at the start
     * of its turn ten rounds on, and its concentration ends, since a raging creature can't concentrate on spells. The
     * caller logs the use.
     */
    public void startRage(Encounter encounter, EncounterParticipant barbarian) {
        endConcentration(encounter, barbarian, "starts raging");
        List<ConditionEntry> conditions = conditions(barbarian);
        conditions.removeIf(c -> ClassFeatures.RAGING.equalsIgnoreCase(c.name()));
        conditions.add(new ConditionEntry(UUID.randomUUID(), ClassFeatures.RAGING, ClassFeatures.RAGE_ROUNDS,
                encounter.getRoundNumber(), "Rage", barbarian.getId(), null, null,
                EndRule.afterRounds(barbarian.getId()), null));
        barbarian.setActiveConditions(conditions);
    }

    /**
     * Ends the participant's rage, if it rages, writing why, as in "Rage ends on Grog (unconscious)". Nothing happens
     * when it doesn't rage.
     */
    public void endRage(Encounter encounter, EncounterParticipant barbarian, String why) {
        List<ConditionEntry> conditions = conditions(barbarian);
        if (!conditions.removeIf(c -> ClassFeatures.RAGING.equalsIgnoreCase(c.name()))) {
            return;
        }
        barbarian.setActiveConditions(conditions.isEmpty() ? null : conditions);
        combatLog.write(encounter, CombatActionType.CONDITION_REMOVE,
                "Rage ends on " + barbarian.getDisplayName() + " (" + why + ")", null, barbarian);
    }

    /**
     * The barbarian attacks recklessly this turn (PHB p.48): its {@link ClassFeatures#RECKLESS} entry gives its melee
     * Strength attacks advantage for the rest of the turn and attacks against it advantage, ending at the start of its
     * next turn. Nothing happens when it already attacks recklessly.
     */
    public void attackRecklessly(Encounter encounter, EncounterParticipant barbarian) {
        List<ConditionEntry> conditions = conditions(barbarian);
        if (conditions.stream().anyMatch(c -> ClassFeatures.RECKLESS.equalsIgnoreCase(c.name()))) {
            return;
        }
        conditions.add(new ConditionEntry(UUID.randomUUID(), ClassFeatures.RECKLESS, null,
                encounter.getRoundNumber(), ClassFeatures.RECKLESS_ATTACK, barbarian.getId(), null, null,
                EndRule.startOfTurn(barbarian.getId()), null));
        barbarian.setActiveConditions(conditions);
    }

    /** Records that the participant attacked or took damage, which keeps its rage going past its turn (PHB p.48). */
    public void markAttackedOrDamaged(EncounterParticipant participant) {
        TurnEconomy economy = economy(participant);
        if (!economy.attackedOrDamaged()) {
            participant.setTurnEconomy(economy.withAttackedOrDamaged(true));
        }
    }

    /**
     * The end of the participant's own turn for Rage (PHB p.48): a rage ends when the creature hasn't attacked a
     * creature or taken damage since its last turn ended, and the count starts again for the next turn.
     */
    public void endTurnForRage(Encounter encounter, EncounterParticipant participant) {
        TurnEconomy economy = economy(participant);
        if (!economy.attackedOrDamaged()) {
            endRage(encounter, participant, "it neither attacked nor took damage since its last turn");
            return;
        }
        participant.setTurnEconomy(economy.withAttackedOrDamaged(false));
    }

    /**
     * Puts a class feature's entry on the creature (M17), replacing one of the same name and source, so a feature used
     * again starts afresh. The caller logs the use.
     */
    public void addFeatureEntry(EncounterParticipant participant, ConditionEntry entry) {
        List<ConditionEntry> conditions = conditions(participant);
        conditions.removeIf(c -> c.name().equalsIgnoreCase(entry.name())
                && Objects.equals(c.sourceParticipantId(), entry.sourceParticipantId()));
        conditions.add(entry);
        participant.setActiveConditions(conditions);
    }

    /** Removes the creature's entries of the name from the source, without a log entry; whether any was there. */
    public boolean removeFeatureEntries(EncounterParticipant participant, String name, UUID sourceId) {
        List<ConditionEntry> conditions = conditions(participant);
        boolean removed = conditions.removeIf(c -> c.name().equalsIgnoreCase(name)
                && (sourceId == null || sourceId.equals(c.sourceParticipantId())));
        if (removed) {
            participant.setActiveConditions(conditions.isEmpty() ? null : conditions);
        }
        return removed;
    }

    /** The creature's Bardic Inspiration entry, or empty (PHB p.53). */
    public static Optional<ConditionEntry> inspiration(EncounterParticipant participant) {
        return conditions(participant).stream()
                .filter(c -> ClassFeatures.INSPIRED.equalsIgnoreCase(c.name()) && c.heldDie() != null)
                .findFirst();
    }

    /** Spends the creature's Bardic Inspiration die, once it is rolled (PHB p.53). */
    public void spendInspiration(EncounterParticipant holder) {
        removeFeatureEntries(holder, ClassFeatures.INSPIRED, null);
    }

    /** Ends the paladin's Sacred Weapon, if it has one, writing why (PHB p.86). */
    public void endSacredWeapon(Encounter encounter, EncounterParticipant paladin, String why) {
        if (removeFeatureEntries(paladin, ChannelDivinity.SACRED_WEAPON, null)) {
            combatLog.write(encounter, CombatActionType.CONDITION_REMOVE,
                    "Sacred Weapon ends on " + paladin.getDisplayName() + " (" + why + ")", null, paladin);
        }
    }

    /**
     * The druid becomes the beast (PHB p.66): its {@link BeastShapes#ENTRY} entry holds the form, with the druid's own
     * hit points, maximum and AC, and the participant takes the beast's hit points, maximum and AC. Its concentration
     * continues; temporary hit points stay. The caller logs the use.
     */
    public void startWildShape(Encounter encounter, EncounterParticipant druid, WildShapeForm form) {
        List<ConditionEntry> conditions = conditions(druid);
        conditions.removeIf(c -> BeastShapes.ENTRY.equalsIgnoreCase(c.name()));
        conditions.add(new ConditionEntry(UUID.randomUUID(), BeastShapes.ENTRY, null, encounter.getRoundNumber(),
                "Wild Shape", druid.getId(), null, null, EndRule.MANUAL, null, null, form));
        druid.setActiveConditions(conditions);
        druid.setHpMax(form.hpMax());
        druid.setHpCurrent(form.hpMax());
        druid.setArmourClass(form.armourClass());
    }

    /**
     * Ends the druid's Wild Shape, if it is transformed (PHB p.67): it takes back the hit points, maximum and AC it had
     * when it transformed, and the entry says why, as in "Vex reverts from Wolf form to normal form (bonus action)".
     * Nothing happens when it isn't transformed.
     *
     * @return the form it left, or empty
     */
    public Optional<WildShapeForm> endWildShape(Encounter encounter, EncounterParticipant druid, String why) {
        Optional<WildShapeForm> form = revert(druid);
        form.ifPresent(beast -> writeReverted(encounter, druid, beast, why));
        return form;
    }

    // The druid's own form back, without a log entry
    private static Optional<WildShapeForm> revert(EncounterParticipant druid) {
        Optional<WildShapeForm> form = WildShapeForm.of(druid);
        if (form.isEmpty()) {
            return form;
        }
        List<ConditionEntry> conditions = conditions(druid);
        conditions.removeIf(c -> BeastShapes.ENTRY.equalsIgnoreCase(c.name()));
        druid.setActiveConditions(conditions.isEmpty() ? null : conditions);
        WildShapeForm beast = form.get();
        druid.setHpMax(beast.normalHpMax());
        // Within the maximum exhaustion leaves the druid, should it have reached level 4 in beast form (D183)
        druid.setHpCurrent(Math.min(beast.normalHpCurrent(), CreatureStats.hpMax(druid)));
        druid.setArmourClass(beast.normalArmourClass());
        return form;
    }

    private void writeReverted(Encounter encounter, EncounterParticipant druid, WildShapeForm beast, String why) {
        combatLog.write(encounter, CombatActionType.CONDITION_REMOVE, druid.getDisplayName() + " reverts from "
                + beast.beastName() + " form to normal form (" + why + "), with " + druid.getHpCurrent() + "/"
                + CreatureStats.hpMax(druid) + " HP and AC " + druid.getArmourClass(), null, druid);
    }

    /**
     * Damage taken, which a druid's beast form takes first (PHB p.67): damage that drops the form to 0 ends the Wild
     * Shape, and what is left carries over to the druid's normal form, which drops to 0 only if that brings it there.
     *
     * @param before  the hit points the damage met: the normal form's when the form dropped
     * @param change  what the damage did to them
     * @param form    the beast form that dropped, or null
     * @param carried the damage that carried over to the normal form
     */
    private record Taken(Vitals before, VitalsChange change, WildShapeForm form, int carried) {
    }

    private static Taken takeDamage(EncounterParticipant target, int damage, boolean critical) {
        Vitals before = vitals(target);
        if (before.lifeState() == LifeState.ALIVE && WildShapeForm.of(target).isPresent()
                && BeastShapes.dropsForm(damage, before.hp(), before.tempHp())) {
            int carried = BeastShapes.carriedOver(damage, before.hp(), before.tempHp());
            target.setHpTemp(0);
            WildShapeForm form = revert(target).orElseThrow();
            Vitals normal = vitals(target);
            VitalsChange change = DamageRules.takeDamage(normal, carried, critical);
            setVitals(target, change.vitals());
            return new Taken(normal, change, form, carried);
        }
        VitalsChange change = DamageRules.takeDamage(before, damage, critical);
        setVitals(target, change.vitals());
        return new Taken(before, change, null, 0);
    }

    private void logReverted(Encounter encounter, EncounterParticipant target, Taken taken) {
        if (taken.form() != null) {
            writeReverted(encounter, target, taken.form(), "its beast form drops to 0 HP"
                    + (taken.carried() > 0 ? "; " + taken.carried() + " damage carries over" : ""));
        }
    }

    /**
     * A monster destroyed outright, as an undead failing its save against a cleric's Turn Undead is by Destroy Undead
     * (PHB p.59): it drops to 0 hit points and dies, with what follows a death, and the entry says what destroyed it.
     * Nothing happens to a creature already dead or to a player character.
     */
    public void destroy(Encounter encounter, EncounterParticipant target, String source) {
        destroy(encounter, target, source, null);
    }

    /**
     * A monster destroyed outright because it failed a save, as Destroy Undead destroys an undead that fails its save
     * against Turn Undead (PHB p.59). A creature that uses Legendary Resistance on that save succeeds instead (D145,
     * D163), so it is neither turned nor destroyed: the failed save's entry records the hit points the destruction took,
     * and using Legendary Resistance on it gives them back, bringing the creature back as the save that killed it would.
     *
     * @param failedSave what the save did to the creature, with its entry; null when no save is involved
     */
    public void destroy(Encounter encounter, EncounterParticipant target, String source, Applied failedSave) {
        if (target.getParticipantType() == ParticipantType.PLAYER || target.getLifeState() == LifeState.DEAD) {
            return;
        }
        if (failedSave != null && failedSave.legendaryResistanceOffered()) {
            Vitals before = vitals(target);
            combatLog.addLegendaryResistanceLoss(failedSave.logEntryId(), before.hp() + before.tempHp());
        }
        target.setHpCurrent(0);
        target.setHpTemp(0);
        target.setLifeState(LifeState.DEAD);
        target.setDeathSaveDue(false);
        combatLog.write(encounter, CombatActionType.KILL, target.getDisplayName() + " is destroyed (" + source + ")",
                null, target);
        endRage(encounter, target, "dead");
        endConcentration(encounter, target, "dead");
        vanishAtZero(encounter, target);
    }

    private static TurnEconomy economy(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }

    // ── Vitals ───────────────────────────────────────────────────

    /** The participant's hit points and life state as the rules read them (D161). */
    public static Vitals vitals(EncounterParticipant participant) {
        return new Vitals(participant.getHpCurrent(), CreatureStats.hpMax(participant),
                participant.getHpTemp() != null ? participant.getHpTemp() : 0, participant.getLifeState(),
                orZero(participant.getDeathSaveSuccesses()), orZero(participant.getDeathSaveFailures()),
                participant.getParticipantType() == ParticipantType.PLAYER);
    }

    private static void setVitals(EncounterParticipant participant, Vitals vitals) {
        participant.setHpCurrent(vitals.hp());
        participant.setHpTemp(vitals.tempHp());
        participant.setLifeState(vitals.lifeState());
        participant.setDeathSaveSuccesses(vitals.deathSaveSuccesses());
        participant.setDeathSaveFailures(vitals.deathSaveFailures());
        // Only the dying owe death saves
        if (vitals.lifeState() != LifeState.DYING) {
            participant.setDeathSaveDue(false);
        }
    }

    private static Vitals withTempHp(Vitals vitals, int tempHp) {
        return new Vitals(vitals.hp(), vitals.maxHp(), tempHp, vitals.lifeState(), vitals.deathSaveSuccesses(),
                vitals.deathSaveFailures(), vitals.playerCharacter());
    }

    // The hit points and temporary hit points the damage took: what Legendary Resistance can give back
    private static int hitPointsLost(Vitals before, int damage) {
        return switch (before.lifeState()) {
            case ALIVE -> Math.min(damage, before.hp() + before.tempHp());
            case DYING, STABLE -> Math.min(damage, before.tempHp());
            case DEAD -> 0;
        };
    }

    // What follows damage: falling unconscious and prone, the log of dropping, dying or failing death saves,
    // and concentration ending at 0 HP or being tested by a CON save, or by the hit's own check for damage added to a
    // hit; the concentration check it made or judged again, or null
    private ConcentrationCheck afterDamage(Encounter encounter, EncounterParticipant target, Vitals before,
                                           VitalsChange change, int damage, ConcentrationCheck hitCheck) {
        ConcentrationCheck check = afterDamageTaken(encounter, target, before, change, damage, hitCheck);
        if (change.died()) {
            vanishAtZero(encounter, target);
        }
        return check;
    }

    private ConcentrationCheck afterDamageTaken(Encounter encounter, EncounterParticipant target, Vitals before,
                                                VitalsChange change, int damage, ConcentrationCheck hitCheck) {
        if (damage > 0) {
            markAttackedOrDamaged(target);
            endOnDamage(encounter, target);
        }
        if (change.addUnconsciousAndProne()) {
            addUnconsciousAndProne(encounter, target);
        }
        logLifeChange(encounter, target, before, change);
        if (change.died()) {
            endRage(encounter, target, "dead");
            endSacredWeapon(encounter, target, "dead");
        }
        if (change.concentrationEnds()) {
            endConcentrationAtZero(encounter, target);
        } else if (damage > 0 && target.getConcentration() != null && target.getLifeState() == LifeState.ALIVE) {
            return hitCheck != null && target.getConcentration().id().equals(hitCheck.instanceId())
                    ? concentrationCheckAgain(encounter, target, hitCheck, damage)
                    : concentrationCheck(encounter, target, damage);
        }
        return null;
    }

    private void logLifeChange(Encounter encounter, EncounterParticipant target, Vitals before, VitalsChange change) {
        Vitals after = change.vitals();
        String name = target.getDisplayName();
        if (change.died()) {
            String description;
            if (change.massiveDamage()) {
                description = before.lifeState() == LifeState.ALIVE
                        ? name + " drops to 0 HP with massive damage and dies instantly"
                        : name + " takes damage of at least their hit point maximum and dies";
            } else if (before.lifeState() == LifeState.ALIVE) {
                description = name + " is killed";
            } else {
                description = name + " suffers " + (after.deathSaveFailures() - before.deathSaveFailures())
                        + " death save failure" + (after.deathSaveFailures() - before.deathSaveFailures() == 1 ? "" : "s")
                        + " and dies";
            }
            combatLog.write(encounter, CombatActionType.KILL, description, null, target);
        } else if (change.droppedToZero()) {
            combatLog.write(encounter, CombatActionType.KILL,
                    name + " drops to 0 HP, falls unconscious and prone, and is dying", null, target);
        } else if (before.lifeState() != LifeState.ALIVE && after.deathSaveFailures() > before.deathSaveFailures()) {
            int failures = after.deathSaveFailures() - before.deathSaveFailures();
            combatLog.write(encounter, CombatActionType.DEATH_SAVE, name + " suffers " + failures + " death save failure"
                    + (failures == 1 ? "" : "s") + " (" + after.deathSaveFailures() + "/3)"
                    + (before.lifeState() == LifeState.STABLE ? " and is no longer stable" : ""), null, target);
        }
    }

    // Healing that wakes a PC ends the unconsciousness of dropping to 0
    private void afterHealing(Encounter encounter, EncounterParticipant target, VitalsChange change) {
        if (change.removeUnconsciousFromDowned()) {
            removeUnconsciousFromZeroHp(target);
            combatLog.write(encounter, CombatActionType.REVIVE, target.getDisplayName()
                    + " regains consciousness with " + target.getHpCurrent() + " HP (still prone)", null, target);
        }
    }

    private void afterConditions(Encounter encounter, EncounterParticipant target, List<ConditionEntry> added) {
        added.stream()
                .map(ConditionEntry::name)
                .filter(INCAPACITATING::contains)
                .findFirst()
                .ifPresent(name -> endConcentration(encounter, target, name));
        if (added.stream().anyMatch(c -> UNCONSCIOUS.equalsIgnoreCase(c.name()))) {
            endRage(encounter, target, UNCONSCIOUS);
            endSacredWeapon(encounter, target, UNCONSCIOUS);
            endWildShape(encounter, target, "falls unconscious");
        }
    }

    // Conditions that last until their creature takes damage end once it does, as a turned undead's (PHB p.59)
    private void endOnDamage(Encounter encounter, EncounterParticipant target) {
        for (ConditionEntry condition : conditions(target)) {
            if (condition.endRule() != null && condition.endRule().type() == EndRule.Type.ON_DAMAGE) {
                endCondition(encounter, target, condition.id(), "it takes damage");
            }
        }
    }

    private ConcentrationCheck concentrationCheck(Encounter encounter, EncounterParticipant target, int damage) {
        int dc = ConcentrationCheck.dcFor(damage);
        int modifier = stats.saveModifier(target, Ability.CON);
        // A concentration check is a Constitution saving throw (PHB p.203), so Bless's d4 counts (D184)
        CreatureStats.SaveModifiers modifiers = CreatureStats.saveModifiers(target, Ability.CON);
        Resolver.SaveRoll roll = resolver.savingThrow(Ability.CON, modifier, dc, CreatureStats.conditionNames(target),
                false, modifiers.modes(), modifiers.bonuses());
        String spell = target.getConcentration().spell();
        String dice = roll.d20s().size() > 1 ? saveDice(roll) : "d20(" + roll.d20() + ")";
        String description = target.getDisplayName() + " makes a concentration check: " + dice
                + signed(modifier) + bonusText(roll.bonuses()) + " = " + roll.total() + " vs DC " + dc
                + (roll.success() ? ", and maintains " + spell : ", and fails");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.CONCENTRATION_CHECK,
                description), null, target).rollValue(roll.d20()).rollTotal(roll.total()));
        ConcentrationCheck check = new ConcentrationCheck(target.getConcentration().id(), damage, dc, roll.total(),
                roll.success());
        if (!roll.success()) {
            endConcentration(encounter, target, "failed concentration check");
        }
        spendModifiers(encounter, roll.spentOneShots());
        return check;
    }

    /*
     * Damage added to a hit whose damage already made the creature roll a concentration check, as Divine Smite's is
     * (PHB p.85): the hit's damage is one amount, so the creature makes one save against it all (PHB p.203). The total
     * it rolled is judged again against the DC of the combined damage, never rolled a second time, so it never has a
     * second chance to fail or to succeed. A DC no higher than the first leaves the outcome as it was, unlogged; a higher
     * one the total doesn't reach ends the concentration. The concentration is still held, so the first check succeeded.
     */
    private ConcentrationCheck concentrationCheckAgain(Encounter encounter, EncounterParticipant target,
                                                       ConcentrationCheck hitCheck, int damage) {
        int combined = hitCheck.damage() + damage;
        int dc = Math.max(hitCheck.dc(), ConcentrationCheck.dcFor(combined));
        boolean success = hitCheck.total() >= dc;
        ConcentrationCheck check = new ConcentrationCheck(hitCheck.instanceId(), combined, dc, hitCheck.total(),
                success);
        if (dc == hitCheck.dc()) {
            return check;
        }
        String spell = target.getConcentration().spell();
        String description = target.getDisplayName() + "'s concentration check covers the hit's whole damage ("
                + combined + "): its total of " + hitCheck.total() + " vs DC " + dc
                + (success ? ", and maintains " + spell : ", and fails");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.CONCENTRATION_CHECK,
                description), null, target).rollTotal(hitCheck.total()));
        if (!success) {
            endConcentration(encounter, target, "failed concentration check");
        }
        return check;
    }

    // Dropping to 0 or dying ends concentration (PHB p.203). A Spell Effect kept up without it, such as Spiritual
    // Weapon, lasts its duration all the same (PHB p.278): its caster just can't use it while unconscious (M15)
    private void endConcentrationAtZero(Encounter encounter, EncounterParticipant target) {
        endConcentration(encounter, target, target.getLifeState() == LifeState.DEAD ? "dead" : "unconscious");
    }

    private void addUnconsciousAndProne(Encounter encounter, EncounterParticipant target) {
        List<ConditionEntry> conditions = conditions(target);
        if (conditions.stream().noneMatch(c -> c.name().equalsIgnoreCase("unconscious"))) {
            conditions.add(ConditionEntry.unconsciousAtZeroHp(encounter.getRoundNumber()));
        }
        if (conditions.stream().noneMatch(c -> c.name().equalsIgnoreCase("prone"))) {
            conditions.add(ConditionEntry.manual("prone", null, encounter.getRoundNumber()));
        }
        target.setActiveConditions(conditions);
        // Rage ends when the barbarian is knocked unconscious (PHB p.48), and Sacred Weapon when the paladin falls
        // unconscious (PHB p.86)
        endRage(encounter, target, UNCONSCIOUS);
        endSacredWeapon(encounter, target, UNCONSCIOUS);
    }

    private static void removeUnconsciousFromZeroHp(EncounterParticipant target) {
        List<ConditionEntry> conditions = conditions(target);
        if (conditions.removeIf(c -> EndRule.HP_REGAINED.equals(c.endRule()))) {
            target.setActiveConditions(conditions.isEmpty() ? null : conditions);
        }
    }

    // The Effect's conditions, apart from one the target already has from the same source. Each lasts as its
    // data says, or as long as the Effect, and keeps any Repeat Save its data gives (D162).
    private List<ConditionEntry> addEffectConditions(Encounter encounter, EncounterParticipant target,
                                                     List<String> names, Use use, Effect effect) {
        if (names.isEmpty()) {
            return List.of();
        }
        List<ConditionEntry> conditions = conditions(target);
        List<ConditionEntry> added = new ArrayList<>();
        UUID sourceId = use.actor() != null ? use.actor().getId() : null;
        for (String name : names) {
            Effect.ConditionEnd end = effect.conditionEnds().get(name);
            Integer duration = end != null && end.durationRounds() != null ? end.durationRounds()
                    : effect.durationRounds();
            RepeatSave repeatSave = end != null && end.repeatSave() != null
                    ? new RepeatSave(end.repeatSave().ability(), end.repeatSave().dc(), false) : null;
            // A condition that lasts until the end of its user's turn, as Stunning Strike's stun does (PHB p.79), or
            // until its creature takes damage, as a turned undead's does (PHB p.59)
            ConditionEntry entry;
            if (end != null && end.endOfUsersTurn() && sourceId != null) {
                entry = new ConditionEntry(UUID.randomUUID(), name, end.durationRounds(), encounter.getRoundNumber(),
                        use.conditionSource(), sourceId, null, null, EndRule.endOfTurn(sourceId), null);
            } else if (end != null && end.endsOnDamage()) {
                entry = new ConditionEntry(UUID.randomUUID(), name, duration, encounter.getRoundNumber(),
                        use.conditionSource(), sourceId, null, null, EndRule.ON_DAMAGE, repeatSave);
            } else {
                entry = ConditionEntry.fromEffect(name, duration, encounter.getRoundNumber(),
                        use.conditionSource(), sourceId, use.instance(), repeatSave);
            }
            // Whatever else ends it, attacking or casting a spell may too, as Invisibility's does (PHB p.254)
            ConditionEntry made = end != null && end.endsOnAttackOrCast() ? entry.endingOnAttackOrCast() : entry;
            boolean duplicate = conditions.stream().anyMatch(c -> c.name().equalsIgnoreCase(name)
                    && Objects.equals(c.sourceParticipantId(), made.sourceParticipantId())
                    && Objects.equals(c.sourceSpellName(), made.sourceSpellName())
                    && Objects.equals(c.concentrationInstanceId(), made.concentrationInstanceId()));
            if (!duplicate) {
                conditions.add(made);
                added.add(made);
            }
        }
        if (!added.isEmpty()) {
            target.setActiveConditions(conditions);
        }
        return added;
    }

    // The conditions added that the Effect's attack gives only on a failed hit save
    private static List<ConditionEntry> guardedByHitSave(Effect effect, List<ConditionEntry> added) {
        if (!(effect.delivery() instanceof Effect.Attack attack) || attack.hitSave() == null) {
            return List.of();
        }
        return added.stream().filter(c -> attack.hitSave().conditions().contains(c.name())).toList();
    }

    // ── Damage after defences ────────────────────────────────────

    private record DefendedPart(String type, int amount, Set<Defence> defences) {
    }

    private record Defended(List<DefendedPart> parts) {

        int total() {
            return parts.stream().mapToInt(DefendedPart::amount).sum();
        }

        String firstType() {
            return parts.isEmpty() ? null : parts.getFirst().type();
        }
    }

    // Each type's damage after the target's defences against it (D161)
    private Defended defended(EncounterParticipant target, List<Resolver.Damage> damage) {
        List<DefendedPart> parts = new ArrayList<>();
        for (Resolver.Damage part : damage) {
            Set<Defence> defences = stats.defences(target, part.type());
            parts.add(new DefendedPart(part.type(), DamageRules.applyDefences(part.amount(), defences), defences));
        }
        return new Defended(parts);
    }

    // ── Descriptions ─────────────────────────────────────────────

    private String describe(Use use, Effect effect, EncounterParticipant target, int armourClass,
                            Resolver.TargetOutcome outcome,
                            Defended damage, int healed, List<ConditionEntry> added, List<String> immune) {
        StringBuilder description = new StringBuilder(use.actorName()).append(' ').append(use.action());
        if (target != use.actor()) {
            boolean noPreposition = use.preposition() == null || use.preposition().isBlank();
            description.append(noPreposition ? " " : " " + use.preposition() + " ").append(target.getDisplayName());
        }

        List<String> results = new ArrayList<>();
        switch (effect.delivery()) {
            case Effect.Attack attack -> {
                // The advantage and disadvantage the conditions gave it, such as "(advantage: target restrained)"
                String automatic = AttackRules.describe(outcome.automaticSources());
                if (!automatic.isEmpty()) {
                    description.append(" (").append(automatic).append(')');
                }
                description.append(": ").append(rerollText(outcome)).append(rolls(outcome))
                        .append(signed(attack.bonus()))
                        .append(bonusText(outcome.bonuses()))
                        .append(inspirationText(outcome))
                        .append(outcome.guidedStrike() ? " + Guided Strike 10" : "")
                        .append(" = ").append(outcome.total()).append(" vs AC ").append(armourClass);
                results.add(switch (outcome.outcome()) {
                    case CRITICAL -> attack.forcedCritical() ? "critical hit (forced)" : "critical hit";
                    case MISS -> outcome.d20() == 1 ? "natural 1, miss" : "miss";
                    default -> "hit";
                });
            }
            case Effect.Save save -> {
                String ability = save.ability() != null ? save.ability().name() + " save" : "Save";
                description.append(": ").append(ability);
                if (outcome.autoFailed()) {
                    description.append(" vs DC ").append(save.dc());
                    results.add("fails automatically");
                } else if (outcome.d20() == null) {
                    // Careful Spell (PHB p.102)
                    description.append(" vs DC ").append(save.dc());
                    results.add("succeeds automatically");
                } else {
                    int inspired = outcome.inspiration() != null ? outcome.inspiration().roll() : 0;
                    int fromModifiers = Resolver.sum(outcome.bonuses());
                    description.append(' ').append(rolls(outcome))
                            .append(signed(outcome.total() - outcome.d20() - inspired - fromModifiers))
                            .append(bonusText(outcome.bonuses()))
                            .append(inspirationText(outcome)).append(" = ").append(outcome.total())
                            .append(" vs DC ").append(save.dc());
                    results.add(outcome.outcome() == Outcome.SAVE_SUCCESS ? "succeeds" : "fails");
                }
            }
            case Effect.Automatic automatic -> {
            }
            case Effect.Self self -> {
            }
        }

        if (outcome.outcome() != Outcome.MISS && !damage.parts().isEmpty()) {
            results.add(damageText(damage) + (outcome.outcome() == Outcome.SAVE_SUCCESS ? " (half)" : "")
                    + (outcome.riders().isEmpty() ? "" : " (with " + outcome.riders().stream()
                    .map(RollModifiers.RolledDamage::describe).collect(Collectors.joining(", ")) + ")"));
        }
        if (outcome.hitSave() != null && effect.delivery() instanceof Effect.Attack attack && attack.hitSave() != null) {
            results.add(hitSaveText(attack.hitSave(), outcome.hitSave()));
        }
        if (healed > 0) {
            results.add("regains " + healed + " HP");
        }
        if (!added.isEmpty()) {
            results.add("gains " + added.stream().map(condition -> conditionText(effect, condition.name()))
                    .collect(Collectors.joining(", ")));
        }
        if (!immune.isEmpty()) {
            results.add("immune to " + String.join(", ", immune));
        }
        if (outcome.outcome() == Outcome.SAVE_SUCCESS && results.size() == 1) {
            results.add("no effect");
        }
        if (results.isEmpty()) {
            results.add("affected");
        }
        boolean rolled = effect.delivery() instanceof Effect.Attack || effect.delivery() instanceof Effect.Save;
        return description.append(rolled ? " — " : ": ").append(String.join(", ", results)).append('.').toString();
    }

    // " +1d4 (3) Bless" for each bonus an Ongoing Modifier added to the roll (D184), or nothing
    private static String bonusText(List<RollModifiers.RolledBonus> bonuses) {
        return bonuses.stream().map(bonus -> " " + bonus.describe()).collect(Collectors.joining());
    }

    // " + Bardic Inspiration d8(5)" when a held die was added to the roll (PHB p.53), or nothing
    private static String inspirationText(Resolver.TargetOutcome outcome) {
        Resolver.Inspiration inspiration = outcome.inspiration();
        return inspiration == null ? ""
                : " + Bardic Inspiration d" + inspiration.sides() + "(" + inspiration.roll() + ")";
    }

    // A condition gained, with the DC that escapes it when the Effect gives one: "grappled (escape DC 14)"
    private static String conditionText(Effect effect, String name) {
        Effect.ConditionEnd end = effect.conditionEnds().get(name);
        return end != null && end.escapeDc() != null ? name + " (escape DC " + end.escapeDc() + ")" : name;
    }

    // "STR save d20(8) + 0 = 8 vs DC 13 fails", "... succeeds against prone", or "STR save vs DC 13 fails
    // automatically"
    private static String hitSaveText(Effect.HitSave save, Resolver.SaveRoll roll) {
        String ability = save.ability().name() + " save";
        if (roll.autoFailed()) {
            return ability + " vs DC " + save.dc() + " fails automatically";
        }
        if (roll.d20() == null) {
            return ability + " vs DC " + save.dc() + " succeeds automatically against "
                    + String.join(", ", save.conditions());
        }
        String rolled = ability + " " + saveDice(roll) + signed(roll.total() - roll.d20() - Resolver.sum(roll.bonuses()))
                + bonusText(roll.bonuses()) + " = " + roll.total() + " vs DC " + save.dc();
        return roll.success() ? rolled + " succeeds against " + String.join(", ", save.conditions())
                : rolled + " fails";
    }

    // A save's dice as rolls() writes an attack's, such as a dodging creature's Dexterity save with advantage
    private static String saveDice(Resolver.SaveRoll roll) {
        if (roll.d20s().size() < 2) {
            return "d20(" + roll.d20() + ")";
        }
        return "2d20(" + roll.d20s().get(0) + ", " + roll.d20s().get(1) + ") with "
                + roll.rollMode().name().toLowerCase() + " → " + roll.d20();
    }

    // The missed roll Unerring Accuracy rolled again (XGE p.35), before the roll that stands: "d20(4) misses, Unerring
    // Accuracy rerolls: "; empty when nothing was rerolled
    private static String rerollText(Resolver.TargetOutcome outcome) {
        if (!outcome.rerolled()) {
            return "";
        }
        List<Integer> missed = outcome.rerolledD20s();
        String dice = missed.size() < 2 ? "d20(" + missed.getFirst() + ")"
                : "2d20(" + missed.get(0) + ", " + missed.get(1) + ")";
        return dice + " misses, Unerring Accuracy rerolls: ";
    }

    // "d20(14)", or both dice with advantage or disadvantage and the one that counts
    private static String rolls(Resolver.TargetOutcome outcome) {
        if (outcome.d20s().size() < 2) {
            return "d20(" + outcome.d20() + ")";
        }
        return "2d20(" + outcome.d20s().get(0) + ", " + outcome.d20s().get(1) + ") with "
                + outcome.rollMode().name().toLowerCase() + " → " + outcome.d20();
    }

    private static String damageText(Defended damage) {
        if (damage.parts().size() == 1) {
            DefendedPart part = damage.parts().getFirst();
            return part.amount() + (part.type() != null ? " " + part.type() : "") + " damage" + defenceNote(part.defences());
        }
        return damage.total() + " damage (" + damage.parts().stream()
                .map(part -> part.amount() + (part.type() != null ? " " + part.type() : "") + defenceNote(part.defences()))
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String defenceNote(Set<Defence> defences) {
        if (defences.contains(Defence.IMMUNITY)) return ", immune";
        if (defences.contains(Defence.RESISTANCE) && defences.contains(Defence.VULNERABILITY)) {
            return ", resisted and doubled by vulnerability";
        }
        if (defences.contains(Defence.RESISTANCE)) return ", halved by resistance";
        if (defences.contains(Defence.VULNERABILITY)) return ", doubled by vulnerability";
        return "";
    }

    private static String signed(int value) {
        return value < 0 ? " - " + Math.abs(value) : " + " + value;
    }

    // Copies, so callers can change them and set them back
    private static List<ConditionEntry> conditions(EncounterParticipant participant) {
        return participant.getActiveConditions() != null
                ? new ArrayList<>(participant.getActiveConditions()) : new ArrayList<>();
    }

    private static Optional<ConditionEntry> find(List<ConditionEntry> conditions, UUID conditionId) {
        return conditions.stream().filter(c -> conditionId.equals(c.id())).findFirst();
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
