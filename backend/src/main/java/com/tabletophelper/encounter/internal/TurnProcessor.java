package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.TurnOrder;
import com.tabletophelper.rules.combat.TurnOrder.Turn;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that happens as an encounter moves through its turn order (D162, D163), in one place; every
 * change it makes to a participant goes through {@link EffectApplier}. Advancing ends the current turn and
 * starts the next, which past the end of the order starts a new round. Going back only moves the marker,
 * redoing and undoing nothing. The Lair's turn has no processing at either end.
 *
 * <p>The turn that ends: conditions that end at the end of that creature's turn end, on whoever has them; its
 * rage ends if it neither attacked nor took damage since its last turn (PHB p.48, M17); and the creature repeats
 * the save of each of its conditions that has one. The server rolls it, unless the creature is a PC and the campaign prompts players, when the condition waits for the player's roll.
 *
 * <p>The turn that starts: the creature's Concentration Instance ends if its duration has run out, taking
 * its conditions and Spell Effects with it, and so does any other Spell Effect of its whose duration has run out,
 * such as Spiritual Weapon's (M15); conditions that end at the start of its turn end, as do conditions with a duration
 * whose rounds have passed when it is their caster (D162: a spell's duration ends at the start of its
 * caster's turn); it gets its action, bonus action and reaction back, its per-turn pools such as legendary
 * actions refill, and each spent Recharge ability rolls its d6; and a Dying PC is due a death save.
 *
 * <p>A turn counted on a creature that has left the encounter, or a dead monster whose turns are skipped,
 * counts on the turn of the creature with the condition instead, so the condition still ends.
 */
@Component
@RequiredArgsConstructor
public class TurnProcessor {

    private final EffectApplier effectApplier;
    private final CombatLogWriter combatLog;

    /** The first turn, as the encounter starts in round 1. */
    public void begin(Encounter encounter) {
        List<Turn> order = EncounterTurns.order(encounter);
        Optional<TurnOrder.NextTurn> first = TurnOrder.next(order, -1);
        EncounterTurns.moveTo(encounter, first.map(next -> order.get(next.index())).orElse(null));
        first.ifPresent(next -> startTurn(encounter, order.get(next.index())));
    }

    /** Ends the current turn and starts the next, past any dead monster's; a 409 when nobody can take one. */
    public void advance(Encounter encounter) {
        List<Turn> order = EncounterTurns.order(encounter);
        int current = EncounterTurns.currentIndex(encounter, order);
        TurnOrder.NextTurn next = TurnOrder.next(order, current)
                .orElseThrow(() -> new ConflictException("Nobody in the turn order can take a turn"));
        if (current >= 0) {
            endTurn(encounter, order.get(current));
        }
        passTo(encounter, order.get(next.index()), next.newRound());
    }

    /** Moves the marker back a turn, past any dead monster's, and nothing else (D163). */
    public void back(Encounter encounter) {
        List<Turn> order = EncounterTurns.order(encounter);
        TurnOrder.PreviousTurn previous = TurnOrder.previous(order, EncounterTurns.currentIndex(encounter, order))
                .orElseThrow(() -> new ConflictException("Nobody in the turn order can take a turn"));
        if (previous.previousRound() && encounter.getRoundNumber() > 1) {
            encounter.setRoundNumber(encounter.getRoundNumber() - 1);
        }
        Turn turn = order.get(previous.index());
        EncounterTurns.moveTo(encounter, turn);
        combatLog.write(encounter, CombatActionType.TURN_BACK, "Turn returns to " + EncounterTurns.name(encounter, turn)
                + " (Round " + encounter.getRoundNumber() + ")", null, turnTarget(encounter, turn));
    }

    /**
     * A participant leaving the fight, before it is removed: its concentration ends, it leaves its lair, and if
     * the turn is its own, the turn passes to the next creature in the order, which starts it. Its own turn
     * doesn't end, as it isn't there to finish it.
     */
    public void leave(Encounter encounter, EncounterParticipant leaving) {
        effectApplier.endConcentration(encounter, leaving, "leaves the encounter");
        if (encounter.getMonstersInLair() != null && encounter.getMonstersInLair().contains(leaving.getId())) {
            List<UUID> inLair = new ArrayList<>(encounter.getMonstersInLair());
            inLair.remove(leaving.getId());
            encounter.setMonstersInLair(inLair);
        }
        if (!EncounterTurns.isCurrentTurn(encounter, leaving.getId())) {
            return;
        }
        List<Turn> order = EncounterTurns.order(encounter);
        Optional<TurnOrder.NextTurn> next = TurnOrder.next(order, EncounterTurns.currentIndex(encounter, order))
                .filter(turn -> !isTurnOf(order.get(turn.index()), leaving));
        if (next.isEmpty()) {
            EncounterTurns.moveTo(encounter, null);
            return;
        }
        passTo(encounter, order.get(next.get().index()), next.get().newRound());
    }

    private void passTo(Encounter encounter, Turn turn, boolean newRound) {
        if (newRound) {
            encounter.setRoundNumber(encounter.getRoundNumber() + 1);
        }
        EncounterTurns.moveTo(encounter, turn);
        combatLog.write(encounter, CombatActionType.TURN_ADVANCE, "Turn passes to " + EncounterTurns.name(encounter, turn)
                + " (Round " + encounter.getRoundNumber() + ")", null, turnTarget(encounter, turn));
        startTurn(encounter, turn);
    }

    // ── The turn that ends ───────────────────────────────────────

    private void endTurn(Encounter encounter, Turn turn) {
        Optional<EncounterParticipant> ending = EncounterTurns.participantOf(encounter, turn);
        if (ending.isEmpty()) {
            return;
        }
        EncounterParticipant creature = ending.get();
        for (EncounterParticipant participant : List.copyOf(encounter.getParticipants())) {
            for (ConditionEntry condition : conditions(participant)) {
                if (condition.endRule().type() == EndRule.Type.END_OF_TURN
                        && creature.getId().equals(turnCounter(encounter, participant, condition))) {
                    effectApplier.endCondition(encounter, participant, condition.id(),
                            "end of " + creature.getDisplayName() + "'s turn");
                }
            }
        }
        // A rage ends with a turn in which the barbarian neither attacked nor took damage since its last (PHB p.48)
        effectApplier.endTurnForRage(encounter, creature);

        if (creature.getLifeState() == LifeState.DEAD) {
            return;
        }
        boolean playerRolls = creature.getParticipantType() == ParticipantType.PLAYER
                && Boolean.TRUE.equals(encounter.getCampaign().getPromptPlayerRepeatSaves());
        for (ConditionEntry condition : conditions(creature)) {
            if (condition.repeatSave() == null) {
                continue;
            }
            if (playerRolls) {
                effectApplier.awaitRepeatSave(creature, condition.id());
            } else {
                effectApplier.repeatSave(encounter, creature, condition.id());
            }
        }
    }

    // ── The turn that starts ─────────────────────────────────────

    private void startTurn(Encounter encounter, Turn turn) {
        Optional<EncounterParticipant> starting = EncounterTurns.participantOf(encounter, turn);
        if (starting.isEmpty()) {
            return;
        }
        EncounterParticipant creature = starting.get();
        int round = encounter.getRoundNumber();

        ConcentrationInstance instance = creature.getConcentration();
        if (instance != null && instance.durationRounds() != null
                && round - instance.startedRound() >= instance.durationRounds()) {
            effectApplier.endConcentration(encounter, creature, "its duration ends");
        }
        // Its Spell Effects whose duration has run out, those kept up without concentration included (M15, D162)
        for (SpellEffectEntry effect : EffectApplier.effects(creature)) {
            if (effect.expiredBy(round)) {
                effectApplier.endEffect(encounter, creature, effect.id(), "its duration ends");
            }
        }

        for (EncounterParticipant participant : List.copyOf(encounter.getParticipants())) {
            for (ConditionEntry condition : conditions(participant)) {
                if (!creature.getId().equals(turnCounter(encounter, participant, condition))) {
                    continue;
                }
                if (condition.endRule().type() == EndRule.Type.START_OF_TURN) {
                    effectApplier.endCondition(encounter, participant, condition.id(),
                            "start of " + creature.getDisplayName() + "'s turn");
                } else if (condition.duration() != null && round - condition.appliedRound() >= condition.duration()) {
                    effectApplier.endCondition(encounter, participant, condition.id(), "its duration ends");
                }
            }
        }

        effectApplier.refreshTurn(encounter, creature);
        effectApplier.deathSaveDue(creature);
    }

    // ── Whose turn counts ────────────────────────────────────────

    /**
     * The creature whose turn the condition's end rule counts: the one the rule names, or for a duration its
     * caster; the creature with the condition when the rule names none, or names one that has left the
     * encounter or is a dead monster whose turns are skipped.
     */
    private static UUID turnCounter(Encounter encounter, EncounterParticipant participant, ConditionEntry condition) {
        UUID named = switch (condition.endRule().type()) {
            case AFTER_ROUNDS, START_OF_TURN, END_OF_TURN -> condition.endRule().participantId();
            default -> condition.sourceParticipantId();
        };
        boolean takesTurns = named != null && encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(named))
                .anyMatch(p -> p.getLifeState() != LifeState.DEAD || p.getParticipantType() == ParticipantType.PLAYER);
        return takesTurns ? named : participant.getId();
    }

    private static boolean isTurnOf(Turn turn, EncounterParticipant participant) {
        return !turn.isLair() && turn.combatant().id().equals(participant.getId());
    }

    /**
     * What a turn's log entry is about: the creature whose turn it is, or for the Lair's a monster in its
     * lair, one players can see when there is one, so the players' log shows the Lair's turn only while they
     * can see a lair monster (D157).
     */
    private static EncounterParticipant turnTarget(Encounter encounter, Turn turn) {
        if (!turn.isLair()) {
            return EncounterTurns.participantOf(encounter, turn).orElse(null);
        }
        List<EncounterParticipant> lairMonsters = encounter.getParticipants().stream()
                .filter(p -> EncounterTurns.inLair(encounter, p.getId()))
                .toList();
        return lairMonsters.stream()
                .filter(p -> !Boolean.FALSE.equals(p.getIsVisibleToPlayers()))
                .findFirst()
                .orElse(lairMonsters.stream().findFirst().orElse(null));
    }

    private static List<ConditionEntry> conditions(EncounterParticipant participant) {
        return participant.getActiveConditions() == null ? List.of()
                : participant.getActiveConditions().stream().filter(Objects::nonNull).toList();
    }
}
