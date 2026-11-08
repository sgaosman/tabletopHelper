package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterLifecycle;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * How a combat command starts (D160): access by campaign first, then the encounter's row lock, then who the
 * user may act for (D028), then that the encounter is Active (D166), and last, for a command a player gives only on
 * their turn, the turn. Commands name participants in the body or the query string, never the path, so an unknown
 * one is a 400.
 */
@Component
@RequiredArgsConstructor
public class CombatAccess {

    /** Why a player's attack is refused off the participant's turn. */
    public static final String OFF_TURN_ATTACK = "You can only attack on your turn";

    /** Why a player's spell is refused off the participant's turn, unless it is cast as a reaction (D174). */
    public static final String OFF_TURN_SPELL =
            "You can only cast a spell on your turn; off your turn only a reaction can be taken";

    /** Why a player's use of a spell's effect again is refused off the participant's turn. */
    public static final String OFF_TURN_SPELL_EFFECT = "You can only use a spell's effect again on your turn";

    /** Why a player's command other than an attack or a spell is refused off the participant's turn. */
    public static final String OFF_TURN_ACTION = "You can only take that action on your turn";

    private final EncounterCommands encounterCommands;
    private final AccessPolicy accessPolicy;

    /** A command only the DM may give. */
    public Encounter beginAsDm(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.requireActive(encounter);
        return encounter;
    }

    /** A command the DM may give for anyone, and a player only for a participant they control (D028). */
    public Encounter beginFor(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsMember(encounterId, userId);
        accessPolicy.requireControls(campaignId(encounter), controllerOf(encounter, participantId), userId);
        EncounterLifecycle.requireActive(encounter);
        return encounter;
    }

    /**
     * As {@link #beginFor}, and a player only on that participant's turn: an attack (D028), refused off it with
     * {@link #OFF_TURN_ATTACK}. The turn is the creature the encounter's marker names (D163).
     */
    public Encounter beginOnTurnFor(UUID encounterId, UUID participantId, UUID userId) {
        return beginOnTurnFor(encounterId, participantId, userId, OFF_TURN_ATTACK);
    }

    /**
     * As {@link #beginFor}, and a player only on that participant's turn, refused off it with the command's own
     * message, such as {@link #OFF_TURN_SPELL_EFFECT}. An encounter that isn't Active is refused for that first, since
     * then there is no turn to wait for (D166).
     */
    public Encounter beginOnTurnFor(UUID encounterId, UUID participantId, UUID userId, String offTurnMessage) {
        Encounter encounter = beginFor(encounterId, participantId, userId);
        requireTurn(encounter, participantId, userId, offTurnMessage);
        return encounter;
    }

    /**
     * As {@link #beginOnTurnFor}, for a command that is neither an attack nor a spell, such as a standard action or
     * an object interaction: a player's off the participant's turn is refused with {@link #OFF_TURN_ACTION}.
     */
    public Encounter beginOnTurnForAction(UUID encounterId, UUID participantId, UUID userId) {
        return beginOnTurnFor(encounterId, participantId, userId, OFF_TURN_ACTION);
    }

    /**
     * As {@link #beginOnTurnFor}, except that a command costing a reaction or nothing from the turn may be given on
     * any turn, as a player's reaction may (D174).
     */
    public Encounter beginForCost(UUID encounterId, UUID participantId, UUID userId, ActionCost cost) {
        return offTurnAllowed(cost)
                ? beginFor(encounterId, participantId, userId)
                : beginOnTurnFor(encounterId, participantId, userId);
    }

    /**
     * For a spell, whose cost (its casting time) is known only once the encounter is read, begun with
     * {@link #beginFor}: a player casts it only on the participant's turn, refused with {@link #OFF_TURN_SPELL},
     * unless it costs a reaction or nothing from the turn (D174).
     */
    public void requireTurnForCost(Encounter encounter, UUID participantId, UUID userId, ActionCost cost) {
        if (!offTurnAllowed(cost)) {
            requireTurn(encounter, participantId, userId, OFF_TURN_SPELL);
        }
    }

    /**
     * As {@link #requireTurnForCost}, for a command that is neither an attack nor a spell, such as a pool's use: a
     * player's off the participant's turn is refused with {@link #OFF_TURN_ACTION}.
     */
    public void requireTurnForActionCost(Encounter encounter, UUID participantId, UUID userId, ActionCost cost) {
        if (!offTurnAllowed(cost)) {
            requireTurn(encounter, participantId, userId, OFF_TURN_ACTION);
        }
    }

    // A player's command only on the participant's turn; the DM's on any
    private void requireTurn(Encounter encounter, UUID participantId, UUID userId, String offTurnMessage) {
        accessPolicy.requireControls(campaignId(encounter), controllerOf(encounter, participantId),
                EncounterTurns.isCurrentTurn(encounter, participantId), offTurnMessage, userId);
    }

    private static boolean offTurnAllowed(ActionCost cost) {
        return cost == ActionCost.REACTION || cost == ActionCost.FREE;
    }

    public static boolean isDm(Encounter encounter, UUID userId) {
        return encounter.getCampaign().getDm().getId().equals(userId);
    }

    /** A participant the command names. */
    public static EncounterParticipant participant(Encounter encounter, UUID participantId) {
        return encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Participant not found"));
    }

    /**
     * A participant the command targets. A player who names a participant hidden from them gets the same 400
     * as for an unknown one, so a request can't find out that it exists (D157).
     */
    public static EncounterParticipant target(Encounter encounter, UUID participantId, UUID userId) {
        EncounterParticipant target = participant(encounter, participantId);
        if (Boolean.FALSE.equals(target.getIsVisibleToPlayers()) && !isDm(encounter, userId)) {
            throw new ValidationException("Participant not found");
        }
        return target;
    }

    /** Each participant the command targets, in the order named; see {@link #target}. */
    public static List<EncounterParticipant> targets(Encounter encounter, List<UUID> participantIds, UUID userId) {
        return participantIds.stream().map(id -> target(encounter, id, userId)).toList();
    }

    /**
     * Refuses an attack, spell or action that targets the dead with a 409: they can't be attacked, and nothing
     * else reaches them either, so a slot or use is never spent on them (D161). Set HP revives them.
     */
    public static void requireNotDead(List<EncounterParticipant> targets) {
        targets.stream()
                .filter(target -> target.getLifeState() == LifeState.DEAD)
                .findFirst()
                .ifPresent(target -> {
                    throw new ConflictException(target.getDisplayName() + " is dead");
                });
    }

    private static UUID campaignId(Encounter encounter) {
        return encounter.getCampaign().getId();
    }

    // Null for monsters and for an unknown or missing participant, so only the DM passes
    private static UUID controllerOf(Encounter encounter, UUID participantId) {
        return encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .map(EncounterParticipant::getControlledByUserId)
                .orElse(null);
    }
}
