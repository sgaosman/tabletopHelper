package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.Vitals;
import com.tabletophelper.rules.combat.VitalsChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
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
 *       condition; ending it removes exactly the conditions its Concentration Instance created (D162).</li>
 *   <li>A failed save by a creature with Legendary Resistance left is offered for undoing: its entry records
 *       the damage a success would have left and the conditions the save applied (D163). That includes the save
 *       an attack's hit calls for against some of its conditions, which leaves the hit's damage as it is.</li>
 *   <li>At the turn boundaries {@link TurnProcessor} reaches, it ends conditions, rolls Repeat Saves or waits
 *       for the player's, gives a creature its turn's actions and pools back, and marks a death save due
 *       (D162, D163).</li>
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
     */
    public record Applied(EncounterParticipant target, Resolver.TargetOutcome outcome, int damageTaken,
                          String damageType, int healed, List<ConditionEntry> conditionsAdded,
                          boolean legendaryResistanceOffered, int legendaryResistanceRemaining,
                          String description) {

        public List<String> conditionNames() {
            return conditionsAdded.stream().map(ConditionEntry::name).toList();
        }
    }

    /** What Legendary Resistance changed: hit points regained, whether that revived it, and conditions removed. */
    public record LegendaryResistanceUndo(int regained, boolean revived, List<String> conditionsRemoved) {
    }

    // ── Effects ──────────────────────────────────────────────────

    /** Applies a resolved Effect to each target in turn, writing one entry per target. */
    public List<Applied> apply(Encounter encounter, Use use, Effect effect, List<Resolver.TargetOutcome> outcomes) {
        List<Applied> applied = new ArrayList<>();
        for (Resolver.TargetOutcome outcome : outcomes) {
            applied.add(applyTo(encounter, use, effect, outcome));
        }
        return applied;
    }

    /** An entry about the Effect as a whole, which names no target, so it can't name a hidden one (D157). */
    public void summary(Encounter encounter, Use use, String description) {
        combatLog.write(encounter, use.actionType(), description, use.actor(), null);
    }

    private Applied applyTo(Encounter encounter, Use use, Effect effect, Resolver.TargetOutcome outcome) {
        EncounterParticipant target = CombatAccess.participant(encounter, outcome.targetId());
        Vitals before = vitals(target);

        Defended damage = defended(target, outcome.damage());
        VitalsChange damageChange = DamageRules.takeDamage(before, damage.total(), outcome.outcome() == Outcome.CRITICAL);
        setVitals(target, damageChange.vitals());

        VitalsChange healingChange = DamageRules.heal(damageChange.vitals(), outcome.healing());
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

        String description = describe(use, effect, target, outcome, damage, healed, added, immune);
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
        combatLog.save(entry);

        afterDamage(encounter, target, before, damageChange, damage.total());
        afterHealing(encounter, target, healingChange);
        afterConditions(encounter, target, added);

        return new Applied(target, outcome, damage.total(), damage.firstType(), healed, added, offered, remaining,
                description);
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
        Vitals before = vitals(target);
        Set<Defence> defences = stats.defences(target, type);
        int total = DamageRules.applyDefences(amount, defences);
        VitalsChange change = DamageRules.takeDamage(before, total, false);
        setVitals(target, change.vitals());

        String description = actorName + " deals " + total + " damage to " + target.getDisplayName()
                + (type != null ? " (" + type + defenceNote(defences) + ")" : "");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.DAMAGE, description),
                actor, target).damageDealt(total));
        afterDamage(encounter, target, before, change, total);
    }

    /** The DM's healing by hand (D028). The dead regain nothing: only Set HP revives them (D161). */
    public void heal(Encounter encounter, EncounterParticipant actor, String actorName, EncounterParticipant target,
                     int amount) {
        Vitals before = vitals(target);
        VitalsChange change = DamageRules.heal(before, amount);
        setVitals(target, change.vitals());
        int healed = change.vitals().hp() - before.hp();

        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.HEAL,
                actorName + " heals " + target.getDisplayName() + " for " + healed + " HP"), actor, target)
                .healingDone(healed));
        afterHealing(encounter, target, change);
    }

    /**
     * The DM's Set HP / revive (D161): a correction, or the only way back from Dead, with the hit points the
     * revival gives. Temporary hit points are set exactly when given. Dropping a creature to 0 this way has
     * the same consequences as damage, except massive damage.
     */
    public void setHp(Encounter encounter, EncounterParticipant target, int hp, Integer tempHp) {
        Vitals before = vitals(target);
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
    }

    /**
     * A dying PC's death saving throw, rolled here (PHB p.197). A natural 20 brings it back with 1 HP and
     * ends the unconsciousness of dropping to 0.
     */
    public void deathSave(Encounter encounter, EncounterParticipant participant) {
        Vitals before = vitals(participant);
        int d20 = resolver.d20();
        VitalsChange change = DamageRules.deathSave(before, d20);
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
        } else if (d20 >= 10) {
            type = CombatActionType.DEATH_SAVE;
            description = name + " succeeds on a death save (roll: " + d20 + "): "
                    + after.deathSaveSuccesses() + "/3 successes";
        } else {
            type = CombatActionType.DEATH_SAVE;
            description = d20 == 1
                    ? name + " rolls a natural 1 on a death save, two failures (" + after.deathSaveFailures() + "/3)"
                    : name + " fails a death save (roll: " + d20 + "): " + after.deathSaveFailures() + "/3 failures";
        }
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, type, description), null, participant)
                .rollValue(d20).rollTotal(d20));
        participant.setDeathSaveDue(false);

        if (change.removeUnconsciousFromDowned()) {
            removeUnconsciousFromZeroHp(participant);
        }
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
    }

    /**
     * Removes every condition of the name by hand, whatever its source. Every removal is logged, so the DM sees
     * what players remove (D162).
     */
    public void removeCondition(Encounter encounter, EncounterParticipant target, String condition) {
        String name = condition.trim().toLowerCase();
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
        Resolver.SaveRoll roll = resolver.savingThrow(save.ability(), modifier, save.dc(),
                CreatureStats.conditionNames(participant));

        String name = participant.getDisplayName();
        StringBuilder description = new StringBuilder(name).append(" repeats the ").append(save.ability().name())
                .append(" save against ").append(condition.name());
        if (roll.autoFailed()) {
            description.append(" vs DC ").append(save.dc()).append(" — fails automatically");
        } else {
            description.append(": d20(").append(roll.d20()).append(')').append(signed(modifier)).append(" = ")
                    .append(roll.total()).append(" vs DC ").append(save.dc())
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
        participant.setTurnEconomy(TurnEconomy.fresh());

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

    // ── Vitals ───────────────────────────────────────────────────

    /** The participant's hit points and life state as the rules read them (D161). */
    public static Vitals vitals(EncounterParticipant participant) {
        return new Vitals(participant.getHpCurrent(), participant.getHpMax(),
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
    // and concentration ending at 0 HP or being tested by a CON save
    private void afterDamage(Encounter encounter, EncounterParticipant target, Vitals before, VitalsChange change,
                             int damage) {
        if (change.addUnconsciousAndProne()) {
            addUnconsciousAndProne(encounter, target);
        }
        logLifeChange(encounter, target, before, change);
        if (change.concentrationEnds()) {
            endConcentrationAtZero(encounter, target);
        } else if (damage > 0 && target.getConcentration() != null && target.getLifeState() == LifeState.ALIVE) {
            concentrationCheck(encounter, target, damage);
        }
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
    }

    private void concentrationCheck(Encounter encounter, EncounterParticipant target, int damage) {
        int dc = Math.max(10, damage / 2);
        int modifier = stats.saveModifier(target, Ability.CON);
        Resolver.SaveRoll roll = resolver.savingThrow(Ability.CON, modifier, dc, CreatureStats.conditionNames(target));
        String spell = target.getConcentration().spell();
        String description = target.getDisplayName() + " makes a concentration check: d20(" + roll.d20() + ")"
                + signed(modifier) + " = " + roll.total() + " vs DC " + dc
                + (roll.success() ? ", and maintains " + spell : ", and fails");
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.CONCENTRATION_CHECK,
                description), null, target).rollValue(roll.d20()).rollTotal(roll.total()));
        if (!roll.success()) {
            endConcentration(encounter, target, "failed concentration check");
        }
    }

    // Dropping to 0 or dying ends concentration, and any spell kept up without it
    private void endConcentrationAtZero(Encounter encounter, EncounterParticipant target) {
        endConcentration(encounter, target, target.getLifeState() == LifeState.DEAD ? "dead" : "unconscious");
        target.setActiveSpell(null);
        target.setActiveSpellSlotLevel(null);
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
            ConditionEntry entry = ConditionEntry.fromEffect(name, duration, encounter.getRoundNumber(),
                    use.conditionSource(), sourceId, use.instance(), repeatSave);
            boolean duplicate = conditions.stream().anyMatch(c -> c.name().equalsIgnoreCase(name)
                    && Objects.equals(c.sourceParticipantId(), entry.sourceParticipantId())
                    && Objects.equals(c.sourceSpellName(), entry.sourceSpellName())
                    && Objects.equals(c.concentrationInstanceId(), entry.concentrationInstanceId()));
            if (!duplicate) {
                conditions.add(entry);
                added.add(entry);
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

    private String describe(Use use, Effect effect, EncounterParticipant target, Resolver.TargetOutcome outcome,
                            Defended damage, int healed, List<ConditionEntry> added, List<String> immune) {
        StringBuilder description = new StringBuilder(use.actorName()).append(' ').append(use.action());
        if (target != use.actor()) {
            boolean noPreposition = use.preposition() == null || use.preposition().isBlank();
            description.append(noPreposition ? " " : " " + use.preposition() + " ").append(target.getDisplayName());
        }

        List<String> results = new ArrayList<>();
        switch (effect.delivery()) {
            case Effect.Attack attack -> {
                description.append(": ").append(rolls(outcome)).append(signed(attack.bonus())).append(" = ")
                        .append(outcome.total()).append(" vs AC ").append(target.getArmourClass());
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
                } else {
                    description.append(' ').append(rolls(outcome))
                            .append(signed(outcome.total() - outcome.d20())).append(" = ").append(outcome.total())
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
            results.add(damageText(damage) + (outcome.outcome() == Outcome.SAVE_SUCCESS ? " (half)" : ""));
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
        String rolled = ability + " d20(" + roll.d20() + ")" + signed(roll.total() - roll.d20()) + " = "
                + roll.total() + " vs DC " + save.dc();
        return roll.success() ? rolled + " succeeds against " + String.join(", ", save.conditions())
                : rolled + " fails";
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
