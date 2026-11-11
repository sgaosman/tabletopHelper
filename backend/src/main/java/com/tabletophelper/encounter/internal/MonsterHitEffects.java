package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a monster's action leaves on the creatures it reached once it is applied (D203), reusing what the spells do
 * (D183-D199) rather than rules of its own. Each lasting rider is a Spell Effect on the monster, named for its action
 * ("Wight's Life Drain"), holding the creature in its affected list and saying what it is in its
 * {@link SpellEffectEntry.MonsterRider}, so it lasts, shows and ends as a spell's effect does:
 * <ul>
 *   <li>a hit point maximum reduced by the damage taken, or by dice, as Harm's (D188), until the creature finishes a
 *       long rest, which the encounter doesn't reach, so the log reminds the table to carry it over after Write-back;
 *       the creature dies if it takes the maximum to 0, through D189's kill outright, which Death Ward stops once
 *       (wight, MM p.300; vampire, MM p.297);</li>
 *   <li>an Ongoing Modifier (D184, D189, D199): can't regain hit points, speed reduced or halved, disadvantage on its
 *       rolls, advantage on attacks against it, no reactions, a resistance lost (D185), for a duration, until the end or
 *       start of a turn, until a rest, or while one of the hit's conditions lasts;</li>
 *   <li>damage at the start of each of the creature's turns, or the monster's, while one of the hit's conditions lasts,
 *       as a chain devil's (MM p.72) or a stirge's blood drain (MM p.284), with the most it deals in all;</li>
 *   <li>life drain: the monster regains hit points, or gains temporary ones, equal to the damage, or half of it, as
 *       Vampiric Touch's (D187);</li>
 *   <li>exhaustion levels (D183), which a rest removes, so they stay on the creature.</li>
 * </ul>
 * A rider with a save of its own comes only when the hit's save fails; one tied to a condition comes only with it, and
 * a creature of a type the rider passes over doesn't get it. The conditions the action gave are stamped with its name,
 * and one that lasts only while another of the hit's does is tied to it ({@link ConditionEntry#linkedTo}). A saving
 * throw action whose success gives immunity records the creature as immune, from now on or once the condition it gave
 * ends, for the rest of the encounter.
 */
@Component
@RequiredArgsConstructor
public class MonsterHitEffects {

    private static final String LONG_REST = "LONG_REST";
    private static final String SHORT_REST = "SHORT_REST";

    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final CombatLogWriter combatLog;
    private final Rng rng;

    /**
     * Applies what the step leaves on each creature it reached, logging it, and gives back the log's words.
     *
     * @param logType the type of the action's entries, such as MONSTER_ACTION
     */
    public List<String> after(Encounter encounter, CombatActionType logType, EncounterParticipant monster,
                              MonsterEffects.Step step, List<EffectApplier.Applied> applied) {
        MonsterEffects.Lasting lasting = step.riders().lasting();
        String action = lasting.action() != null ? lasting.action() : step.effect().name();
        List<String> notes = new ArrayList<>();
        for (EffectApplier.Applied result : applied) {
            EncounterParticipant target = result.target();
            stamp(target, result.conditionsAdded(), action);
            link(encounter, monster, action, lasting, target, result, notes);
            if (lasting.immuneAfterSave() != null && step.effect().delivery() instanceof Effect.Save) {
                immunity(encounter, monster, action, lasting, result, notes);
            }
            if (!reached(result)) {
                continue;
            }
            boolean saved = result.outcome().hitSave() != null && result.outcome().hitSave().success();
            List<Anchored> modifiers = new ArrayList<>();
            for (MonsterTemplates.Part part : lasting.parts()) {
                if (part.ownSave() && saved && lasting.guardedByHit()) {
                    continue;
                }
                apply(encounter, monster, action, lasting, part, result, notes, modifiers);
            }
            if (!modifiers.isEmpty()) {
                modify(encounter, monster, action, lasting, target, modifiers, notes);
            }
        }
        notes.forEach(note -> combatLog.write(encounter, logType, note, monster, null));
        return notes;
    }

    /**
     * The targets immune to the monster's action (D203): each made its save against it before, or the effect it gave
     * them has ended since, as a dragon's Frightful Presence (MM p.87); or each is of a type the whole action passes
     * over, as a banshee's wail passes over constructs and undead (MM p.23). The log's words for each go in notes.
     */
    public List<EncounterParticipant> immune(EncounterParticipant monster, MonsterEffects.Step step,
                                             List<EncounterParticipant> targets, List<String> notes) {
        MonsterEffects.Lasting lasting = step.riders().lasting();
        if (!(step.effect().delivery() instanceof Effect.Save)) {
            return List.of();
        }
        String action = lasting.action() != null ? lasting.action() : step.effect().name();
        List<EncounterParticipant> immune = new ArrayList<>();
        for (EncounterParticipant target : targets) {
            if (!lasting.actionExempt().isEmpty() && MonsterRiders.exempt(lasting, List.of(), target)) {
                immune.add(target);
                notes.add(action + " has no effect on " + target.getDisplayName() + ": it passes over "
                        + String.join(" and ", lasting.actionExempt()) + " creatures.");
            } else if (lasting.immuneAfterSave() != null && immuneTo(monster, action, target)) {
                immune.add(target);
                notes.add(target.getDisplayName() + " is immune to " + monster.getDisplayName() + "'s " + action
                        + " (" + lasting.immuneAfterSave() + " after its save or once the effect ended for it).");
            }
        }
        return immune;
    }

    // ── What the step left ───────────────────────────────────────

    // Whether the action reached the target: a hit, a failed save, or no roll
    private static boolean reached(EffectApplier.Applied result) {
        return switch (result.outcome().outcome()) {
            case HIT, CRITICAL, SAVE_FAIL, AUTOMATIC -> true;
            default -> false;
        };
    }

    // The conditions the action gave name it, so its grapples can be counted and its links found (D203)
    private void stamp(EncounterParticipant target, List<ConditionEntry> added, String action) {
        for (ConditionEntry entry : added) {
            current(target, entry.id()).ifPresent(now -> effectApplier.replaceCondition(target, now.fromAction(action)));
        }
    }

    /*
     * Ties each condition the hit gave that lasts only while another does to that one, as a restraint to its grapple: the
     * other as this hit gave it, or as the monster's action gave it before. Without it the condition doesn't hold, as a
     * paralysis "until this poison ends" on a creature immune to poison.
     */
    private void link(Encounter encounter, EncounterParticipant monster, String action,
                      MonsterEffects.Lasting lasting, EncounterParticipant target, EffectApplier.Applied result,
                      List<String> notes) {
        for (ConditionEntry entry : result.conditionsAdded()) {
            String anchorName = lasting.endsWith().get(entry.name().toLowerCase(Locale.ROOT));
            if (anchorName == null) {
                continue;
            }
            Optional<ConditionEntry> anchor = anchorOf(target, monster, action, anchorName);
            if (anchor.isPresent()) {
                current(target, entry.id()).ifPresent(now ->
                        effectApplier.replaceCondition(target, now.linkedTo(anchor.get().id())));
            } else {
                effectApplier.dropCondition(encounter, target, entry.id(), "it lasts only while "
                        + target.getDisplayName() + " is " + anchorName + " by " + action + ", which it isn't");
            }
        }
    }

    // A MODIFIER part that reaches the creature, with the condition it lasts while, if any
    private record Anchored(MonsterTemplates.Part part, ConditionEntry anchor) {
    }

    // One lasting rider on one creature; the Ongoing Modifiers are gathered, to be left together
    private void apply(Encounter encounter, EncounterParticipant monster, String action,
                       MonsterEffects.Lasting lasting, MonsterTemplates.Part part, EffectApplier.Applied result,
                       List<String> notes, List<Anchored> modifiers) {
        EncounterParticipant target = result.target();
        String label = monster.getDisplayName() + "'s " + action;
        if (MonsterRiders.exempt(lasting, part.exemptTypes(), target)) {
            notes.add(label + " doesn't leave " + target.getDisplayName() + " with "
                    + MonsterEffects.riderText(part) + ": it passes over "
                    + String.join(" and ", !part.exemptTypes().isEmpty() ? part.exemptTypes()
                    : lasting.actionExempt()) + " creatures.");
            return;
        }
        ConditionEntry anchor = null;
        if (part.endsWith() != null && !part.endsWith().isBlank()) {
            anchor = anchorOf(target, monster, action, part.endsWith().trim().toLowerCase(Locale.ROOT)).orElse(null);
            if (anchor == null) {
                notes.add(target.getDisplayName() + " isn't " + part.endsWith().trim().toLowerCase(Locale.ROOT)
                        + " by " + label + ", so it isn't left with " + MonsterEffects.riderText(part) + ".");
                return;
            }
        }
        String type = part.effectType() != null ? part.effectType().toUpperCase(Locale.ROOT) : "";
        switch (type) {
            case MonsterEffects.HP_MAX_REDUCTION -> reduceMaximum(encounter, monster, action, label, part, result,
                    notes);
            case MonsterEffects.LIFE_DRAIN -> drain(encounter, monster, label, part, result);
            case MonsterEffects.EXHAUSTION -> exhaust(encounter, target, label, part, notes);
            case MonsterEffects.MODIFIER -> modifiers.add(new Anchored(part, anchor));
            case MonsterEffects.ONGOING_DAMAGE -> turnDamage(encounter, monster, action, label, part, target, anchor,
                    notes);
            default -> {
            }
        }
    }

    /*
     * A hit point maximum reduced by the damage taken, of the part's type when it names one, or half of it, or by its
     * dice (D188's Harm, D203): held by the monster until the creature finishes a long rest. A second hit adds to it. A
     * maximum Aura of Life protects isn't reduced (PHB p.216). The creature dies if the reduction takes its maximum to 0,
     * as a wight's Life Drain says (MM p.300), killed outright through D189's path, which Death Ward stops (PHB p.230).
     */
    private void reduceMaximum(Encounter encounter, EncounterParticipant monster, String action, String label,
                               MonsterTemplates.Part part, EffectApplier.Applied result, List<String> notes) {
        EncounterParticipant target = result.target();
        if (target.getLifeState() == LifeState.DEAD) {
            return;
        }
        int amount = part.damageDice() != null && !part.damageDice().isBlank()
                ? rolled(part.damageDice()) : taken(result, part.damageType());
        if (part.half()) {
            amount = amount / 2;
        }
        if (amount <= 0) {
            return;
        }
        List<OngoingModifier> on = ActiveModifiers.modifiersOn(target);
        if (OngoingModifiers.maximumProtected(on)) {
            notes.add(target.getDisplayName() + "'s hit point maximum can't be reduced, so " + label
                    + " doesn't reduce it.");
            return;
        }
        // The maximum before any floor: its stored maximum and every change made to it
        int unfloored = (target.getHpMax() != null ? target.getHpMax() : 1) + on.stream()
                .filter(modifier -> modifier.kind() == OngoingModifier.Kind.HP_MAX)
                .mapToInt(OngoingModifier::flat).sum() - amount;
        Optional<SpellEffectEntry> held = held(monster, action, target, SpellEffectEntry.MonsterRider.HP_MAX);
        String rest = part.until() != null && part.until().equalsIgnoreCase(SHORT_REST) ? SHORT_REST : LONG_REST;
        if (held.isPresent()) {
            int before = held.get().modifiers().stream().mapToInt(OngoingModifier::flat).sum();
            effectApplier.startModifiers(encounter, monster, held.get().id(), List.of(target),
                    List.of(maximum(before - amount)), null, null, held.get().endReminder(), false);
        } else {
            SpellEffectEntry effect = effectApplier.startEffect(encounter, monster, label, null, null, null, null,
                    false);
            effectApplier.startModifiers(encounter, monster, effect.id(), List.of(target), List.of(maximum(-amount)),
                    null, null, "{target}'s hit point maximum returns to normal (" + label + ")", false);
            effectApplier.holdMonsterRider(monster, effect.id(), new SpellEffectEntry.MonsterRider(
                    SpellEffectEntry.MonsterRider.HP_MAX, action, null, null, rest, null), null);
        }
        int maximum = CreatureStats.hpMax(target);
        if (target.getHpCurrent() != null && target.getHpCurrent() > maximum) {
            target.setHpCurrent(maximum);
        }
        String until = SHORT_REST.equals(rest) ? "a short or long rest" : "a long rest";
        notes.add(target.getDisplayName() + "'s hit point maximum is reduced by " + amount + " (" + label
                + ") until it finishes " + until + "; it is now " + maximum + "."
                + (target.getParticipantType() == ParticipantType.PLAYER ? " If the rest comes after the encounter, "
                + "note it on the sheet after Write-back." : ""));
        if (part.diesAtZero() && unfloored <= 0) {
            notes.add(effectApplier.killOutright(encounter, monster, target, action
                    + " (its hit point maximum reduced to 0)"));
        }
    }

    // An HP_MAX modifier lowering the maximum by this much
    private static OngoingModifier maximum(int flat) {
        return new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.HP_MAX, OngoingModifier.Scope.AFFECTED,
                null, flat, null, null, List.of(), false, false, false, null, false, null, List.of(), null, null);
    }

    /*
     * Life drain (D187, D203): the monster regains hit points equal to the damage the creature took, of the part's type
     * when it names one, or half of it, as a vampire's bite regains its necrotic (MM p.297); or gains that many
     * temporary hit points, which don't add to any it has (PHB p.198).
     */
    private void drain(Encounter encounter, EncounterParticipant monster, String label, MonsterTemplates.Part part,
                       EffectApplier.Applied result) {
        int amount = taken(result, part.damageType());
        if (part.half()) {
            amount = amount / 2;
        }
        if (amount <= 0 || monster.getLifeState() == LifeState.DEAD) {
            return;
        }
        if (part.tempHp()) {
            effectApplier.gainTempHp(encounter, monster, monster, amount, label);
        } else {
            effectApplier.heal(encounter, monster, monster, amount, label + ": "
                    + (part.half() ? "half " : "") + "the damage dealt");
        }
    }

    // Exhaustion levels (D183), which stay on the creature: a rest takes them away, which Write-back carries
    private void exhaust(Encounter encounter, EncounterParticipant target, String label, MonsterTemplates.Part part,
                         List<String> notes) {
        if (target.getLifeState() == LifeState.DEAD) {
            return;
        }
        int levels = part.levels() != null && part.levels() > 0 ? part.levels() : 1;
        try {
            effectApplier.setExhaustion(encounter, target, Exhaustion.clamp(target.getExhaustionLevel()) + levels);
        } catch (ConflictException e) {
            notes.add(target.getDisplayName() + " gains no exhaustion from " + label + ": " + e.getMessage() + ".");
        }
    }

    /*
     * Ongoing Modifiers on the creature (D184), as Chill Touch's "can't regain hit points" (PHB p.221) or Ray of Frost's
     * speed (PHB p.271): for a duration, until the end or start of a turn (each modifier's own end, so the effect goes
     * with the last), until a rest, or while the condition it is tied to lasts. A hit's modifiers that end alike share
     * one Spell Effect, since the same spell's effects don't add up (PHB p.205); those that end otherwise get one each,
     * named apart. Another hit renews them.
     */
    private void modify(Encounter encounter, EncounterParticipant monster, String action,
                        MonsterEffects.Lasting lasting, EncounterParticipant target, List<Anchored> parts,
                        List<String> notes) {
        if (target.getLifeState() == LifeState.DEAD) {
            return;
        }
        String label = monster.getDisplayName() + "'s " + action;
        EffectApplier.effects(monster).stream()
                .filter(effect -> effect.monsterRider() != null
                        && SpellEffectEntry.MonsterRider.MODIFIER.equals(effect.monsterRider().kind())
                        && action.equalsIgnoreCase(effect.monsterRider().action())
                        && effect.affected().contains(target.getId()))
                .toList()
                .forEach(earlier -> effectApplier.endEffect(encounter, monster, earlier.id(),
                        "renewed by another hit"));
        // What ends each group alike: a rest, the condition it lasts while, or its rounds
        java.util.Map<List<Object>, List<OngoingModifier>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<List<Object>, Anchored> firsts = new java.util.LinkedHashMap<>();
        for (Anchored each : parts) {
            MonsterTemplates.Part part = each.part();
            if (part.modifier() == null) {
                continue;
            }
            OngoingModifier modifier;
            try {
                modifier = MonsterEffects.lastingModifier(part.modifier(), part.until());
            } catch (IllegalArgumentException | NullPointerException e) {
                notes.add(label + "'s " + part.modifier().kind() + " on " + target.getDisplayName()
                        + " is left to the DM.");
                continue;
            }
            String rest = rest(part.until());
            Integer rounds = part.durationRounds() != null && part.durationRounds() > 0 ? part.durationRounds() : null;
            List<Object> key = java.util.Arrays.asList(rest, each.anchor() != null ? each.anchor().id() : null,
                    modifier.until() == null && rest == null && each.anchor() == null ? rounds : null,
                    modifier.until() != null);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(modifier);
            firsts.putIfAbsent(key, each);
        }
        // Another of the action's riders that changes numbers counts apart from these, as the same spell's would not
        boolean apart = lasting.parts().stream().anyMatch(other -> MonsterEffects.HP_MAX_REDUCTION
                .equalsIgnoreCase(other.effectType()));
        int index = 0;
        for (java.util.Map.Entry<List<Object>, List<OngoingModifier>> group : groups.entrySet()) {
            Anchored first = firsts.get(group.getKey());
            String rest = (String) group.getKey().get(0);
            Integer rounds = (Integer) group.getKey().get(2);
            boolean ownEnds = (Boolean) group.getKey().get(3);
            String name = label + (apart || index > 0 ? " (effects" + (index > 0 ? " " + (index + 1) : "") + ")" : "");
            SpellEffectEntry effect = effectApplier.startEffect(encounter, monster, name, null, null, rounds, null,
                    false);
            effectApplier.startModifiers(encounter, monster, effect.id(), List.of(target), group.getValue(), null,
                    null, null, ownEnds);
            ConditionEntry anchor = first.anchor();
            effectApplier.holdMonsterRider(monster, effect.id(), new SpellEffectEntry.MonsterRider(
                    SpellEffectEntry.MonsterRider.MODIFIER, action, anchor != null ? anchor.id() : null,
                    anchor != null ? anchor.name() : null, rest, null), null);
            notes.add(target.getDisplayName() + ": " + group.getValue().stream().map(OngoingModifier::describe)
                    .collect(java.util.stream.Collectors.joining("; ")) + " (" + label + ")"
                    + lastingWords(rest, anchor, rounds, ownEnds) + ".");
            index++;
        }
    }

    /*
     * Damage at the start of a turn while the condition it is tied to lasts (D203), as a chain devil's (MM p.72): a hit
     * on a creature it already deals this to deals no more, since the same effect doesn't add up.
     */
    private void turnDamage(Encounter encounter, EncounterParticipant monster, String action, String label,
                            MonsterTemplates.Part part, EncounterParticipant target, ConditionEntry anchor,
                            List<String> notes) {
        if (part.damageDice() == null || target.getLifeState() == LifeState.DEAD
                || held(monster, action, target, SpellEffectEntry.MonsterRider.TURN_DAMAGE).isPresent()) {
            return;
        }
        String type = part.damageType() != null && !part.damageType().isBlank()
                ? part.damageType().trim().toLowerCase(Locale.ROOT) : null;
        String turnOf = "USER".equalsIgnoreCase(part.turnOf()) ? "USER" : "TARGET";
        Integer rounds = part.durationRounds() != null && part.durationRounds() > 0 && anchor == null
                ? part.durationRounds() : null;
        SpellEffectEntry effect = effectApplier.startEffect(encounter, monster, label, null, null, rounds, null,
                false);
        effectApplier.startModifiers(encounter, monster, effect.id(), List.of(target), List.of(), null, null, null,
                false);
        effectApplier.holdMonsterRider(monster, effect.id(), new SpellEffectEntry.MonsterRider(
                SpellEffectEntry.MonsterRider.TURN_DAMAGE, action, anchor != null ? anchor.id() : null,
                anchor != null ? anchor.name() : null, rest(part.until()),
                new SpellEffectEntry.TurnDamage(part.damageDice(), type, turnOf)), part.total());
        notes.add(target.getDisplayName() + (type != null ? " takes " + part.damageDice() + " " + type + " damage"
                : " loses " + part.damageDice() + " hit points") + " at the start of each of "
                + ("USER".equals(turnOf) ? monster.getDisplayName() + "'s" : "its") + " turns (" + label + ")"
                + (part.total() != null ? ", " + part.total() + " in all" : "")
                + lastingWords(rest(part.until()), anchor, rounds, false) + ".");
    }

    /*
     * A creature's immunity to a saving throw action (D203): at once after a success, or once the condition a failure
     * gave has ended, as Frightful Presence's "if a creature's saving throw is successful or the effect ends for it"
     * (MM p.87). Kept for the encounter, which is shorter than the 24 hours.
     */
    private void immunity(Encounter encounter, EncounterParticipant monster, String action,
                          MonsterEffects.Lasting lasting, EffectApplier.Applied result, List<String> notes) {
        EncounterParticipant target = result.target();
        Outcome outcome = result.outcome().outcome();
        if (outcome != Outcome.SAVE_SUCCESS && outcome != Outcome.SAVE_FAIL) {
            return;
        }
        held(monster, action, target, SpellEffectEntry.MonsterRider.IMMUNITY).ifPresent(earlier ->
                effectApplier.endEffect(encounter, monster, earlier.id(), "used again"));
        ConditionEntry anchor = outcome == Outcome.SAVE_FAIL && !result.conditionsAdded().isEmpty()
                ? result.conditionsAdded().getFirst() : null;
        String label = monster.getDisplayName() + "'s " + action;
        SpellEffectEntry effect = effectApplier.startEffect(encounter, monster, label + " (immunity)", null, null,
                null, null, false);
        effectApplier.startModifiers(encounter, monster, effect.id(), List.of(target), List.of(), null, null, null,
                false);
        effectApplier.holdMonsterRider(monster, effect.id(), new SpellEffectEntry.MonsterRider(
                SpellEffectEntry.MonsterRider.IMMUNITY, action, anchor != null ? anchor.id() : null,
                anchor != null ? anchor.name() : null, null, null), null);
        if (anchor == null) {
            notes.add(target.getDisplayName() + " is immune to " + label + " " + lasting.immuneAfterSave() + ".");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    // Whether the creature is immune to the monster's action: made its save, or the effect has ended for it
    private static boolean immuneTo(EncounterParticipant monster, String action, EncounterParticipant target) {
        return held(monster, action, target, SpellEffectEntry.MonsterRider.IMMUNITY)
                .map(effect -> effect.monsterRider().anchor() == null || current(target,
                        effect.monsterRider().anchor()).isEmpty())
                .orElse(false);
    }

    // The monster's effect of this kind its action left on the creature
    private static Optional<SpellEffectEntry> held(EncounterParticipant monster, String action,
                                                   EncounterParticipant target, String kind) {
        return EffectApplier.effects(monster).stream()
                .filter(effect -> effect.monsterRider() != null && kind.equals(effect.monsterRider().kind())
                        && action.equalsIgnoreCase(effect.monsterRider().action())
                        && effect.affected().contains(target.getId()))
                .findFirst();
    }

    // The creature's entry of the condition the monster's action gave it
    private static Optional<ConditionEntry> anchorOf(EncounterParticipant target, EncounterParticipant monster,
                                                     String action, String condition) {
        List<ConditionEntry> entries = target.getActiveConditions() != null ? target.getActiveConditions()
                : List.of();
        return entries.stream()
                .filter(c -> c.name() != null && c.name().equalsIgnoreCase(condition)
                        && Objects.equals(c.sourceParticipantId(), monster.getId())
                        && (c.sourceAction() == null || c.sourceAction().equalsIgnoreCase(action)))
                .findFirst();
    }

    private static Optional<ConditionEntry> current(EncounterParticipant target, UUID id) {
        List<ConditionEntry> entries = target.getActiveConditions() != null ? target.getActiveConditions()
                : List.of();
        return entries.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    // The damage the creature took from the hit, of one type when one is named, after its defences
    private int taken(EffectApplier.Applied result, String type) {
        if (type == null || type.isBlank()) {
            return result.damageTaken();
        }
        int amount = result.outcome().damage().stream()
                .filter(damage -> type.trim().equalsIgnoreCase(damage.type()))
                .mapToInt(Resolver.Damage::amount)
                .sum();
        return DamageRules.applyDefences(amount, stats.defences(result.target(), type.trim().toLowerCase(Locale.ROOT)));
    }

    private int rolled(String dice) {
        return TemplateDice.read(dice).orElse(List.of()).stream().mapToInt(each -> each.roll(rng)).sum();
    }

    // LONG_REST or SHORT_REST for an end at a rest; null otherwise
    private static String rest(String until) {
        if (until == null) {
            return null;
        }
        return switch (until.toUpperCase(Locale.ROOT)) {
            case LONG_REST -> LONG_REST;
            case SHORT_REST -> SHORT_REST;
            default -> null;
        };
    }

    // ", until it finishes a long rest", ", while it is poisoned", ", for 10 rounds", or nothing
    private static String lastingWords(String rest, ConditionEntry anchor, Integer rounds, boolean ownEnd) {
        if (ownEnd) {
            return "";
        }
        if (rest != null) {
            return ", until it finishes " + (SHORT_REST.equals(rest) ? "a short or long rest" : "a long rest");
        }
        if (anchor != null) {
            return ", while it is " + anchor.name();
        }
        return rounds != null ? ", for " + rounds + " round" + (rounds == 1 ? "" : "s") : ", until the DM ends it";
    }
}
