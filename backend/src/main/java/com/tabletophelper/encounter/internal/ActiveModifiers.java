package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Ongoing Modifiers in force on a participant (D184), worked out from the Spell Effects of everyone in its
 * encounter each time a roll or a number needs them, so they can never fall out of step with the effects that hold
 * them: an effect that ends, by its duration, its concentration or the DM's hand, takes its modifiers with it.
 * <p>
 * The same spell's effects don't combine on one creature; the most potent applies (PHB p.205, "Combining Magical
 * Effects"). Here that is the casting with the highest slot level, the earliest on a tie, so two clerics' Bless on one
 * fighter add one d4.
 */
public final class ActiveModifiers {

    private ActiveModifiers() {}

    /**
     * One modifier in force.
     *
     * @param caster   the participant whose Spell Effect holds it
     * @param effect   that Spell Effect
     * @param modifier the modifier
     */
    public record Found(EncounterParticipant caster, SpellEffectEntry effect, OngoingModifier modifier) {

        /** The modifier with its label for the log. */
        public OngoingModifiers.Labelled labelled() {
            return new OngoingModifiers.Labelled(label(effect), modifier);
        }
    }

    /**
     * What the log and the chips call an effect's modifiers: its spell, or for a spell of two modes, such as
     * Enlarge/Reduce, the mode chosen ("Reduce").
     */
    public static String label(SpellEffectEntry effect) {
        if (effect.option() != null && effect.spell().contains("/")) {
            String option = effect.option();
            return option.isEmpty() ? effect.spell()
                    : option.substring(0, 1).toUpperCase(Locale.ROOT) + option.substring(1);
        }
        return effect.spell();
    }

    /** Every modifier held by a Spell Effect of anyone in the participant's encounter. */
    public static List<Found> all(EncounterParticipant anyone) {
        List<Found> found = new ArrayList<>();
        for (EncounterParticipant holder : everyone(anyone)) {
            if (Boolean.TRUE.equals(holder.getDismissed())) {
                continue;
            }
            for (SpellEffectEntry effect : EffectApplier.effects(holder)) {
                for (OngoingModifier modifier : effect.modifiers()) {
                    found.add(new Found(holder, effect, modifier));
                }
            }
        }
        return found;
    }

    /**
     * The modifiers on the participant's own rolls and numbers: those of the effects that affect it, the same spell
     * counted once.
     */
    public static List<Found> on(EncounterParticipant participant) {
        List<Found> mine = all(participant).stream()
                .filter(found -> found.modifier().scope() == OngoingModifier.Scope.AFFECTED
                        && found.effect().affected().contains(participant.getId()))
                .toList();
        return mostPotent(mine);
    }

    /** As {@link #on}, the modifiers alone. */
    public static List<OngoingModifier> modifiersOn(EncounterParticipant participant) {
        return on(participant).stream().map(Found::modifier).toList();
    }

    /** As {@link #on}, labelled. */
    public static List<OngoingModifiers.Labelled> labelledOn(EncounterParticipant participant) {
        return on(participant).stream().map(Found::labelled).toList();
    }

    /**
     * The modifiers that change the attacker's attack on the target: the attacker's own attack roll and hit damage
     * modifiers, the caster's against a creature it marked (Hunter's Mark, Hex, True Strike), an affected creature's
     * against its caster (Chill Touch on an undead) and against anyone else (Compelled Duel).
     */
    public static List<Found> attacking(EncounterParticipant attacker, EncounterParticipant target) {
        if (attacker == null) {
            return List.of();
        }
        List<Found> found = new ArrayList<>(on(attacker).stream()
                .filter(each -> switch (each.modifier().kind()) {
                    case ATTACK_ROLL, ATTACK_MODE, HIT_DAMAGE -> true;
                    default -> false;
                })
                .toList());
        UUID attackerId = attacker.getId();
        UUID targetId = target != null ? target.getId() : null;
        for (Found each : all(attacker)) {
            OngoingModifier.Scope scope = each.modifier().scope();
            UUID casterId = each.caster().getId();
            boolean affectsAttacker = each.effect().affected().contains(attackerId);
            boolean applies = switch (scope) {
                case CASTER_VS_AFFECTED -> casterId.equals(attackerId) && targetId != null
                        && each.effect().affected().contains(targetId);
                case AFFECTED_VS_CASTER -> affectsAttacker && casterId.equals(targetId);
                case AFFECTED_VS_OTHERS -> affectsAttacker && targetId != null && !casterId.equals(targetId);
                case AFFECTED -> false;
            };
            // A modifier on the affected creature's attacks may hold only for some creature types, as Chill Touch's on
            // an undead (PHB p.221)
            if (applies && attackerOfType(each.modifier(), attacker)) {
                found.add(each);
            }
        }
        return found;
    }

    // Whether the attacker is of a type the modifier names, or the modifier names none
    private static boolean attackerOfType(OngoingModifier modifier, EncounterParticipant attacker) {
        if (modifier.attackerTypes().isEmpty()) {
            return true;
        }
        String type = CreatureStats.creatureType(attacker);
        return type != null && modifier.attackerTypes().stream()
                .anyMatch(wanted -> type.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)));
    }

    /** The modifiers on attack rolls against the participant: its ATTACKED_MODE and REVEALED ones. */
    public static List<Found> attacked(EncounterParticipant target) {
        return on(target).stream()
                .filter(each -> each.modifier().kind() == OngoingModifier.Kind.ATTACKED_MODE
                        || each.modifier().kind() == OngoingModifier.Kind.REVEALED)
                .toList();
    }

    /** The labelled modifiers of a list. */
    public static List<OngoingModifiers.Labelled> labelled(List<Found> found) {
        return found.stream().map(Found::labelled).toList();
    }

    // The participants of its encounter, or itself alone without one, as in a unit test
    private static List<EncounterParticipant> everyone(EncounterParticipant anyone) {
        Encounter encounter = anyone.getEncounter();
        if (encounter == null || encounter.getParticipants() == null) {
            return List.of(anyone);
        }
        return encounter.getParticipants().stream().filter(Objects::nonNull).toList();
    }

    // The same spell's effects don't combine: only the most potent's modifiers count (PHB p.205)
    private static List<Found> mostPotent(List<Found> found) {
        Map<String, SpellEffectEntry> chosen = new HashMap<>();
        Comparator<SpellEffectEntry> potency = Comparator
                .comparingInt((SpellEffectEntry effect) -> effect.slotLevel() != null ? effect.slotLevel() : 0)
                .thenComparing(Comparator.comparingInt(SpellEffectEntry::startedRound).reversed());
        for (Found each : found) {
            String spell = each.effect().spell().toLowerCase(Locale.ROOT);
            SpellEffectEntry current = chosen.get(spell);
            if (current == null || potency.compare(each.effect(), current) > 0) {
                chosen.put(spell, each.effect());
            }
        }
        return found.stream()
                .filter(each -> chosen.get(each.effect().spell().toLowerCase(Locale.ROOT)).id()
                        .equals(each.effect().id()))
                .toList();
    }
}
