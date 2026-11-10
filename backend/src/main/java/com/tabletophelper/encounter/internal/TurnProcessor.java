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
 * rage ends if it neither attacked nor took damage since its last turn (PHB p.48, M17); Summon Greater Demon's demon
 * makes its Charisma save to break free (XGE p.166, D182); and the creature repeats
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
 * counts on the turn of the creature with the condition instead, so the condition still ends. What has no turn left to
 * count on runs out at the start of each round instead, before the first turn: a dead monster's Spell Effects, such as
 * the Spiritual Weapon it cast before it died, and a duration that would count on a dead monster's turn when the
 * creature with the condition is a dead monster too.
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
        // Its other Spell Effects go with it, so their Ongoing Modifiers end on whoever had them (D184)
        for (SpellEffectEntry effect : EffectApplier.effects(leaving)) {
            if (effect.summon() == null) {
                effectApplier.endEffect(encounter, leaving, effect.id(), "its caster leaves the encounter");
            }
        }
        // Its summoned creatures go with it (D182)
        effectApplier.dismissSummonsOf(encounter, leaving, leaving.getDisplayName() + " leaves the encounter");
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
            startRound(encounter);
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
        int round = encounter.getRoundNumber();
        for (EncounterParticipant participant : List.copyOf(encounter.getParticipants())) {
            for (ConditionEntry condition : conditions(participant)) {
                // One with a duration ends at the end of the named creature's turn once that many rounds have
                // passed, as a stun "until the end of your next turn" given on the monk's own turn does (PHB p.79)
                if (condition.endRule().type() == EndRule.Type.END_OF_TURN
                        && creature.getId().equals(turnCounter(encounter, participant, condition))
                        && (condition.duration() == null || round - condition.appliedRound() >= condition.duration())) {
                    effectApplier.endCondition(encounter, participant, condition.id(),
                            "end of " + creature.getDisplayName() + "'s turn");
                }
            }
        }
        // Ongoing Modifiers that last until the end of this turn end, and Slow's creature repeats its save (D184)
        effectApplier.modifiersAtTurnEnd(encounter, creature);
        // Damage due at the end of this turn, as Vitriolic Sphere's (XGE p.170, D185)
        effectApplier.effectsAtTurnEnd(encounter, creature);
        // A rage ends with a turn in which the barbarian neither attacked nor took damage since its last (PHB p.48)
        effectApplier.endTurnForRage(encounter, creature);
        // A command its caster gave lasts for the creature's next turn (PHB p.212, XGE p.168, D182)
        if (creature.getSummon() != null && creature.getSummon().commandedRound() != null) {
            effectApplier.command(creature, null);
        }

        if (creature.getLifeState() == LifeState.DEAD) {
            return;
        }
        // Summon Greater Demon's demon tries to break free at the end of each of its turns (XGE p.166, D182)
        if (!EncounterTurns.dismissed(creature)) {
            effectApplier.summonControlSave(encounter, creature);
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
                } else if (condition.endRule().type() != EndRule.Type.END_OF_TURN && condition.duration() != null
                        && round - condition.appliedRound() >= condition.duration()) {
                    effectApplier.endCondition(encounter, participant, condition.id(), "its duration ends");
                }
            }
        }

        // Ongoing Modifiers that last until the start of this turn end, and Heroism's temporary hit points (D184)
        effectApplier.modifiersAtTurnStart(encounter, creature);
        // Delayed damage armed, Aura of Life's hit point, and the creature's Spell Effects' reminders (D185)
        effectApplier.effectsAtTurnStart(encounter, creature);
        effectApplier.refreshTurn(encounter, creature);
        effectApplier.deathSaveDue(creature);
    }

    // ── The round that starts ────────────────────────────────────

    /*
     * The top of a new round, before its first turn (D177 follow-up): durations whose clock has no turn left to run on
     * end here once they have passed, as they would have at the start of their creature's turn. A dead monster's Spell
     * Effects, and its concentration should it still hold one, count here, since its turns are skipped; so does a
     * condition with a duration whose counting creature takes no turns, which happens only when the creature with it is
     * a dead monster too. A creature that has left is no longer in the encounter, and nothing of its is left to count.
     */
    private void startRound(Encounter encounter) {
        int round = encounter.getRoundNumber();
        for (EncounterParticipant participant : List.copyOf(encounter.getParticipants())) {
            if (takesTurns(participant)) {
                continue;
            }
            ConcentrationInstance instance = participant.getConcentration();
            if (instance != null && instance.durationRounds() != null
                    && round - instance.startedRound() >= instance.durationRounds()) {
                effectApplier.endConcentration(encounter, participant, "its duration ends");
            }
            for (SpellEffectEntry effect : EffectApplier.effects(participant)) {
                if (effect.expiredBy(round)) {
                    effectApplier.endEffect(encounter, participant, effect.id(), "its duration ends");
                }
            }
            for (ConditionEntry condition : conditions(participant)) {
                if (condition.duration() != null && round - condition.appliedRound() >= condition.duration()
                        && participant.getId().equals(turnCounter(encounter, participant, condition))) {
                    effectApplier.endCondition(encounter, participant, condition.id(), "its duration ends");
                }
            }
        }
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
                .anyMatch(TurnProcessor::takesTurns);
        return takesTurns ? named : participant.getId();
    }

    /** Whether the creature still takes turns: anyone but a dead monster, whose turns are skipped (D163). */
    private static boolean takesTurns(EncounterParticipant participant) {
        if (EncounterTurns.dismissed(participant) || CombatAccess.banished(participant)) {
            // A summoned creature that has disappeared takes no more turns (D182), nor does a banished one until it
            // returns (PHB p.217, D185)
            return false;
        }
        return participant.getLifeState() != LifeState.DEAD
                || participant.getParticipantType() == ParticipantType.PLAYER;
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
