package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.WarlockPatrons;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;

import java.util.List;
import java.util.Optional;

/**
 * Genie's Wrath (Genie warlock 1, TCE, D242): "Once during each of your turns when you hit with an attack roll, you can
 * deal extra damage to the target equal to your proficiency bonus. The type of this damage is determined by your
 * patron: bludgeoning (dao), thunder (djinni), fire (efreeti), or cold (marid)."
 * <p>
 * The attack or the spell asks for it, as a weapon attack asks for Sneak Attack; it is dealt on the attack's first hit,
 * after the hit's own damage, as part of that hit's damage (a concentrating target makes one check against both, as
 * with Divine Smite, PHB p.203), and spent for the turn only then. Asked for once it was dealt this turn, it adds
 * nothing, so every attack of a turn may ask for it. The kind is the one the warlock's levels record (D240); the
 * vessel it is a use of isn't tracked, so the warlock counts as touching it.
 */
final class GeniesWrath {

    static final String NAME = "Genie's Wrath";

    private GeniesWrath() {
    }

    /** What the attack options show for it, such as "+3 fire"; null for a creature without it. */
    static String offered(PlayerCharacter character) {
        return damageType(character)
                .map(type -> "+" + proficiencyBonus(character) + " " + type)
                .orElse(null);
    }

    /**
     * Checks a request for it before anything is spent.
     *
     * @throws ValidationException for a creature without it: not a Genie warlock, or one whose kind isn't recorded
     * @throws ConflictException   off the creature's own turn, since it is once during each of its turns
     */
    static void require(Encounter encounter, EncounterParticipant actor) {
        PlayerCharacter character = actor != null ? actor.getCharacter() : null;
        if (character == null || WarlockPatrons.patronLevel(character, WarlockPatrons.GENIE) < 1) {
            throw new ValidationException((actor != null ? actor.getDisplayName() : "The DM") + " doesn't have "
                    + NAME + " (The Genie)");
        }
        if (damageType(character).isEmpty()) {
            throw new ValidationException(actor.getDisplayName() + "'s genie kind isn't chosen yet, which "
                    + NAME + "'s damage type follows");
        }
        if (!EncounterTurns.isCurrentTurn(encounter, actor.getId())) {
            throw new ConflictException(NAME + " is once during each of " + actor.getDisplayName()
                    + "'s own turns");
        }
    }

    /**
     * Deals it on the first hit among the results, unless it was dealt already this turn, and records it for the
     * turn; nothing on all misses. The request has passed {@link #require}.
     */
    static void afterHits(Encounter encounter, EncounterParticipant actor, List<EffectApplier.Applied> results,
                          EffectApplier effectApplier) {
        TurnEconomy economy = economyOf(actor);
        if (economy.geniesWrathUsed()) {
            return;
        }
        Optional<EffectApplier.Applied> hit = results.stream()
                .filter(result -> result.outcome().outcome() == Outcome.HIT
                        || result.outcome().outcome() == Outcome.CRITICAL)
                .findFirst();
        if (hit.isEmpty()) {
            return;
        }
        PlayerCharacter character = actor.getCharacter();
        String type = damageType(character).orElseThrow();
        int amount = proficiencyBonus(character);
        Effect effect = new Effect(NAME, new Effect.Automatic(),
                List.of(new Effect.DamagePart(new Dice(0, 0, amount), type)), null, List.of(), false, null);
        EffectApplier.Use use = new EffectApplier.Use(actor, actor.getDisplayName(), CombatActionType.ATTACK,
                "uses " + NAME + " (+" + amount + " " + type + ") after the hit", "on", NAME, null);
        EffectApplier.Applied first = hit.get();
        // A creature the hit killed takes nothing more, and the use isn't spent
        if (first.target().getLifeState() == LifeState.DEAD) {
            return;
        }
        effectApplier.addToHit(encounter, use, effect, first.target(),
                new EffectApplier.HitDamage(first.concentrationCheck(), first.leftoverAtZero()));
        actor.setTurnEconomy(economyOf(actor).withGeniesWrathUsed());
    }

    private static Optional<String> damageType(PlayerCharacter character) {
        return character == null ? Optional.empty() : WarlockPatrons.geniesWrathType(character);
    }

    private static int proficiencyBonus(PlayerCharacter character) {
        return character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2;
    }

    private static TurnEconomy economyOf(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }
}
