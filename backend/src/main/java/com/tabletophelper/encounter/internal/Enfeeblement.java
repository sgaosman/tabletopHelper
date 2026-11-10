package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.Resolver;

import java.util.List;

/**
 * Ray of Enfeeblement's weakness (PHB p.271, D197): a creature under it deals only half damage with weapon attacks that
 * use Strength. A character's weapon attack uses Strength when its numbers do; an entered attack or a monster's weapon
 * attack counts as using Strength when it is made from within 5 feet, as a melee one is.
 */
final class Enfeeblement {

    /** What the attack's log name gains when its damage is halved. */
    static final String NOTE = "half damage, Ray of Enfeeblement";

    private Enfeeblement() {
    }

    /** Whether the attacker deals only half damage with its Strength weapon attacks. */
    static boolean weakened(EncounterParticipant attacker) {
        return attacker != null && ActiveModifiers.modifiersOn(attacker).stream()
                .anyMatch(modifier -> modifier.kind() == OngoingModifier.Kind.HALF_STRENGTH_DAMAGE);
    }

    /** Whether an attack made without a character's numbers counts as using Strength: a weapon attack from within 5 feet. */
    static boolean strengthAttack(Effect effect) {
        return effect.delivery() instanceof Effect.Attack attack && attack.weapon() && attack.withinFiveFeet();
    }

    /** The effect named for the log as halved. */
    static Effect noted(Effect effect) {
        return new Effect(effect.name() + " (" + NOTE + ")", effect.delivery(), effect.damage(), effect.healing(),
                effect.conditions(), effect.concentration(), effect.durationRounds(), effect.conditionEnds());
    }

    /** The outcomes with each type of damage halved, rounded down (PHB p.197). */
    static List<Resolver.TargetOutcome> halved(List<Resolver.TargetOutcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.withDamage(halvedDamage(outcome.damage())))
                .toList();
    }

    /** Each damage halved, rounded down. */
    static List<Resolver.Damage> halvedDamage(List<Resolver.Damage> damage) {
        return damage.stream().map(each -> new Resolver.Damage(each.type(), each.amount() / 2)).toList();
    }
}
