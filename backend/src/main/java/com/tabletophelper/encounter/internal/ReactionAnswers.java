package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.encounter.state.ReactionOffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What answering a reaction offer changes about the event it answers (D216). The app resolves an attack at once, so a
 * reaction that would change it comes after its result: Shield cast in answer to a hit takes the hit back when its
 * +5 AC makes the attack roll miss (PHB p.275), the way Counterspell takes back a spell (D196). The hit's command is
 * reverted through undo's before-state, and the attacker keeps what the attack cost it, its turn, pools, slots and
 * conditions, as it did attack. Undoing the Shield brings the hit back. Where the hit can't be taken back safely, the
 * log says so and the DM adjusts by hand.
 */
@Component
@RequiredArgsConstructor
public class ReactionAnswers {

    private final CommandRecorder commandRecorder;
    private final UndoService undoService;
    private final CombatLogWriter combatLog;

    /**
     * Shield cast in answer to the offer: takes back the hit it turns into a miss. Called before the Shield's own
     * cost is spent, so taking back the hit can't give that cost back. An offer that has gone, or isn't the caster's
     * hit, changes nothing: the Shield is cast as any other.
     */
    public void shield(Encounter encounter, EncounterParticipant caster, UUID offerId, UUID userId) {
        Optional<CommandRecorder.Recording> recording = CommandRecorder.current();
        if (recording.isEmpty()) {
            return;
        }
        ReactionOffer offer = recording.map(CommandRecorder.Recording::priorOffers).orElse(List.of()).stream()
                .filter(candidate -> candidate.id().equals(offerId))
                .filter(candidate -> candidate.trigger() == ReactionOffer.Trigger.ATTACK_HIT)
                .filter(candidate -> caster.getId().equals(candidate.reactorId()))
                .findFirst()
                .orElse(null);
        if (offer == null || offer.attackTotal() == null || offer.armourClass() == null) {
            return;
        }
        int shielded = offer.armourClass() + 5;
        String lead = "Shield raises " + caster.getDisplayName() + "'s AC to " + shielded + " against "
                + offer.triggerActorName() + "'s attack roll of " + offer.attackTotal();
        if (offer.critical()) {
            combatLog.write(encounter, CombatActionType.SPELL_CAST, lead + ", but a critical hit still hits.",
                    caster, null);
            return;
        }
        if (offer.attackTotal() >= shielded) {
            combatLog.write(encounter, CombatActionType.SPELL_CAST, lead + ", which still hits.", caster, null);
            return;
        }
        Optional<CombatCommand> hit = commandRecorder.undoable(encounter.getId(), 2).stream()
                .filter(command -> recording.map(r -> !command.getId().equals(r.id())).orElse(true))
                .findFirst()
                .filter(command -> command.getId().equals(offer.commandId()));
        EncounterParticipant attacker = offer.triggerActorId() == null ? null : encounter.getParticipants().stream()
                .filter(p -> offer.triggerActorId().equals(p.getId()))
                .findFirst()
                .orElse(null);
        String refusal = hit.isEmpty() ? "other actions have followed it"
                : attacker == null ? "its attacker has left"
                : changedOthers(hit.get(), Set.of(attacker.getId(), caster.getId()))
                ? "the same action changed other creatures too"
                : hitsOn(recording.get().priorOffers(), offer) > 1 ? "the same action hit " + caster.getDisplayName()
                        + " more than once"
                : null;
        if (refusal != null) {
            combatLog.write(encounter, CombatActionType.SPELL_CAST, lead + ", which misses, but the hit can't be "
                    + "taken back here (" + refusal + "): the DM restores its damage and anything else it did by "
                    + "hand.", caster, null);
            return;
        }
        // The attack was made, so what it cost stays spent, as a countered spell's cost does (D196)
        var slots = attacker.getSpellSlotsCurrent() != null ? new LinkedHashMap<>(attacker.getSpellSlotsCurrent()) : null;
        var pools = attacker.getResourcePoolsCurrent() != null ? new ArrayList<>(attacker.getResourcePoolsCurrent())
                : null;
        var conditions = attacker.getActiveConditions() != null ? new ArrayList<>(attacker.getActiveConditions())
                : null;
        var economy = attacker.getTurnEconomy();
        undoService.revert(encounter, hit.get(), userId);
        attacker.setSpellSlotsCurrent(slots);
        attacker.setResourcePoolsCurrent(pools);
        attacker.setActiveConditions(conditions);
        attacker.setTurnEconomy(economy);
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.SPELL_CAST,
                        lead + ", which misses: the hit is taken back."), caster, attacker)
                .undoesCommandId(hit.get().getId()));
    }

    // Whether the command changed, or brought in, a creature other than these
    private static boolean changedOthers(CombatCommand command, Set<UUID> allowed) {
        CommandSnapshot before = command.getBeforeState();
        if (before == null) {
            return true;
        }
        if (before.createdParticipantIds() != null && !before.createdParticipantIds().isEmpty()) {
            return true;
        }
        List<ParticipantCombatState> changed = before.participants() != null ? before.participants() : List.of();
        return changed.stream().anyMatch(state -> !allowed.contains(state.id()));
    }

    // How many hits on the reactor the offer's command made, one offer each
    private static long hitsOn(List<ReactionOffer> offers, ReactionOffer offer) {
        return offers.stream()
                .filter(other -> other.trigger() == ReactionOffer.Trigger.ATTACK_HIT)
                .filter(other -> offer.reactorId().equals(other.reactorId()))
                .filter(other -> offer.commandId() != null && offer.commandId().equals(other.commandId()))
                .count();
    }
}
