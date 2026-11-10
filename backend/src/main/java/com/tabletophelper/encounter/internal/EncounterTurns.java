package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.TurnOrder;
import com.tabletophelper.rules.combat.TurnOrder.Turn;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an encounter stands in its turn order (D163). {@link TurnOrder} computes the order whenever it is
 * needed, from each participant's initiative, DEX score and the DM's order among ties, with the Lair's turn
 * while a monster fighting in its lair is alive. The encounter remembers whose turn it is by participant, or
 * that it is the Lair's, never by place in the order: the place shifts as ties are broken and participants
 * and the Lair come and go, and the creature doesn't.
 */
public final class EncounterTurns {

    private EncounterTurns() {}

    /**
     * The encounter's turns in order. The Lair has a turn while a monster in its lair is alive, and keeps the
     * one it is taking when the last of them dies during it.
     */
    public static List<Turn> order(Encounter encounter) {
        return TurnOrder.order(encounter.getParticipants().stream()
                        .filter(p -> !dismissed(p) || isCurrentTurn(encounter, p.getId()))
                        // A banished creature keeps its place, skipped, so it returns to it (PHB p.217, D185)
                        .map(EncounterTurns::combatant).toList(),
                lairPresent(encounter) || isLairTurn(encounter));
    }

    /**
     * The participants in the fight: everyone but a summoned creature that has disappeared, whose row stays for undo
     * (D182).
     */
    public static List<EncounterParticipant> present(Encounter encounter) {
        return encounter.getParticipants().stream().filter(p -> !dismissed(p)).toList();
    }

    /** Whether the participant is a summoned creature that has disappeared (D182). */
    public static boolean dismissed(EncounterParticipant participant) {
        return Boolean.TRUE.equals(participant.getDismissed());
    }

    /** Whether a monster fighting in its lair is alive, which gives the Lair a turn (D163). */
    public static boolean lairPresent(Encounter encounter) {
        List<UUID> inLair = encounter.getMonstersInLair();
        return inLair != null && encounter.getParticipants().stream()
                .anyMatch(p -> inLair.contains(p.getId()) && p.getLifeState() != LifeState.DEAD);
    }

    /** Whether the monster is fighting in its lair. */
    public static boolean inLair(Encounter encounter, UUID participantId) {
        return encounter.getMonstersInLair() != null && encounter.getMonstersInLair().contains(participantId);
    }

    public static boolean isLairTurn(Encounter encounter) {
        return Boolean.TRUE.equals(encounter.getLairTurn());
    }

    /** Whether it is this participant's turn. */
    public static boolean isCurrentTurn(Encounter encounter, UUID participantId) {
        return participantId != null && participantId.equals(encounter.getCurrentTurnParticipantId());
    }

    /** Whether the encounter is on this turn. */
    public static boolean isCurrent(Encounter encounter, Turn turn) {
        return turn.isLair() ? isLairTurn(encounter) : isCurrentTurn(encounter, turn.combatant().id());
    }

    /** The place in the order of the turn the encounter is on, or -1 when it is on none. */
    public static int currentIndex(Encounter encounter, List<Turn> order) {
        for (int i = 0; i < order.size(); i++) {
            if (isCurrent(encounter, order.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The participant whose turn it is; empty on the Lair's turn and before the encounter starts. */
    public static Optional<EncounterParticipant> currentParticipant(Encounter encounter) {
        return participant(encounter, encounter.getCurrentTurnParticipantId());
    }

    /** The participant whose turn this is; empty for the Lair's. */
    public static Optional<EncounterParticipant> participantOf(Encounter encounter, Turn turn) {
        return turn.isLair() ? Optional.empty() : participant(encounter, turn.combatant().id());
    }

    /** What the order calls the turn: the participant's name, or the Lair's. */
    public static String name(Encounter encounter, Turn turn) {
        return participantOf(encounter, turn).map(EncounterParticipant::getDisplayName)
                .orElse(TurnOrder.LAIR_ACTIONS);
    }

    /**
     * A name for the turn the encounter is on, the same for every command during it and different on any other turn:
     * the round and whose turn it is, as "3:<participant id>" or "3:lair". What a feature allows once per turn, any
     * creature's turn, is recorded against it, such as Sneak Attack (PHB p.96, M17).
     */
    public static String stamp(Encounter encounter) {
        String whose = isLairTurn(encounter) ? "lair" : String.valueOf(encounter.getCurrentTurnParticipantId());
        return encounter.getRoundNumber() + ":" + whose;
    }

    /** Puts the encounter on the turn, or on none for null. */
    public static void moveTo(Encounter encounter, Turn turn) {
        encounter.setCurrentTurnParticipantId(turn != null && !turn.isLair() ? turn.combatant().id() : null);
        encounter.setLairTurn(turn != null && turn.isLair());
    }

    /**
     * The participant as the order sees it. A dead monster's turn is skipped, while a PC keeps its turn,
     * dying or dead; a participant without a DEX score counts as 10, and one the DM hasn't ordered comes after
     * those it ties with. A summoned creature that follows its caster, as a Tasha's spirit does (TCE p.109), names its
     * caster to follow, and one that disappeared during its own turn keeps that turn, skipped, until the turn passes
     * (D182).
     */
    public static TurnOrder.Combatant combatant(EncounterParticipant participant) {
        // A banished creature, PC or monster, has no turns until it returns (PHB p.217, D185)
        boolean dismissed = dismissed(participant) || CombatAccess.banished(participant);
        UUID follows = participant.getSummon() != null && participant.getSummon().followsCaster()
                ? participant.getSummonedByParticipantId() : null;
        return new TurnOrder.Combatant(participant.getId(), participant.getInitiative(),
                CreatureStats.score(participant, Ability.DEX),
                participant.getSortOrder() != null ? participant.getSortOrder() : Integer.MAX_VALUE,
                participant.getParticipantType() != ParticipantType.PLAYER || dismissed,
                participant.getLifeState() == LifeState.DEAD || dismissed, follows);
    }

    private static Optional<EncounterParticipant> participant(Encounter encounter, UUID participantId) {
        if (participantId == null) {
            return Optional.empty();
        }
        return encounter.getParticipants().stream().filter(p -> p.getId().equals(participantId)).findFirst();
    }
}
