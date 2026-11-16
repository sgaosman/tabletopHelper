package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.encounter.state.ReactionOffer;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What answering a reaction offer changes about the event it answers (D216). The app resolves an attack at once, so a
 * reaction that would change it comes after its result: Shield cast in answer to a hit takes the hit back when its
 * +5 AC makes the attack roll miss (PHB p.275), the way Counterspell takes back a spell (D196). The hit's command is
 * reverted through undo's before-state, and the attacker keeps what the attack cost and spent ({@link KeptCosts}).
 * Undoing the Shield brings the hit back.
 * <p>
 * The hit is taken back only when that is exact: the hit was all its command did to the wizard (the offer says so,
 * {@link ReactionOffers}), the command changed no other creature, nothing since has changed the wizard or what of the
 * attacker's would be put back, and the attack left nothing of the attacker's on the wizard. Otherwise the log says
 * so, the +5 AC stands, and the DM adjusts by hand. One attack of a Multiattack is never taken back alone, since the
 * wizard's state between its attacks isn't recorded.
 */
@Component
@RequiredArgsConstructor
public class ReactionAnswers {

    private final CommandRecorder commandRecorder;
    private final UndoService undoService;
    private final CombatLogWriter combatLog;

    /**
     * The offer a cast answers, found before anything is spent (D216): one standing as the command started, for the
     * caster, that can still be taken.
     *
     * @throws ConflictException   when no such offer stands ({@link ReactionOfferService#GONE})
     * @throws ValidationException when the offer doesn't offer the spell cast
     */
    public ReactionOffer answered(Encounter encounter, EncounterParticipant caster, UUID offerId, String spellName) {
        List<ReactionOffer> standing = CommandRecorder.current()
                .map(CommandRecorder.Recording::offersAtStart)
                .orElseGet(() -> encounter.getReactionOffers() != null ? encounter.getReactionOffers() : List.of());
        ReactionOffer offer = standing.stream()
                .filter(candidate -> candidate.id().equals(offerId))
                .filter(candidate -> caster.getId().equals(candidate.reactorId()))
                .filter(candidate -> ReactionOffers.open(encounter, candidate))
                .findFirst()
                .orElseThrow(() -> new ConflictException(ReactionOfferService.GONE));
        String spell = spellName != null ? spellName.trim() : "";
        if (offer.options().stream().noneMatch(option -> option.kind() == ReactionOffer.Kind.SPELL
                && spell.equalsIgnoreCase(option.spellName()))) {
            throw new ValidationException("That reaction offer is for "
                    + offer.options().stream().map(ReactionOffer.ReactionOption::name).collect(Collectors.joining(", "))
                    + ", not " + spell);
        }
        return offer;
    }

    /**
     * Shield cast in answer to the offer: takes back the hit it turns into a miss, when that can be done exactly.
     * Called before the Shield's own cost is spent, so taking back the hit can't give that cost back. An offer that
     * isn't for a hit changes nothing: the Shield is cast as any other.
     */
    public void shield(Encounter encounter, EncounterParticipant caster, ReactionOffer offer, UUID userId) {
        Optional<CommandRecorder.Recording> recording = CommandRecorder.current();
        if (recording.isEmpty() || offer.trigger() != ReactionOffer.Trigger.ATTACK_HIT || offer.attackTotal() == null
                || offer.armourClass() == null) {
            return;
        }
        int shielded = offer.armourClass() + 5;
        // EncounterViews takes this AC out of the players' log for a monster (D235): keep its phrasing in step
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
        EncounterParticipant attacker = offer.triggerActorId() == null ? null : encounter.getParticipants().stream()
                .filter(p -> offer.triggerActorId().equals(p.getId()))
                .findFirst()
                .orElse(null);
        List<CombatCommand> recent = commandRecorder.undoable(encounter.getId(), CommandRecorder.MAX_STEPS + 1).stream()
                .filter(command -> !command.getId().equals(recording.get().id()))
                .toList();
        int at = -1;
        for (int i = 0; i < recent.size() && at < 0; i++) {
            if (recent.get(i).getId().equals(offer.commandId())) {
                at = i;
            }
        }
        String refusal = refusal(recording.get(), offer, caster, attacker, recent, at);
        if (refusal != null) {
            combatLog.write(encounter, CombatActionType.SPELL_CAST, lead + ", which misses, but the hit can't be "
                    + "taken back here (" + refusal + "): the DM restores its damage and anything else it did by "
                    + "hand.", caster, null);
            return;
        }
        CombatCommand hit = recent.get(at);
        // The attack was made, so what it cost and spent stays so, as a countered spell's cost does (D196)
        KeptCosts kept = KeptCosts.of(KeptCosts.Kind.ATTACK, attacker);
        undoService.revert(encounter, hit, userId);
        kept.putBack(attacker);
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.SPELL_CAST,
                        lead + ", which misses: the hit is taken back."), caster, attacker)
                .undoesCommandId(hit.getId()));
    }

    /*
     * Why the hit can't be taken back exactly, or null when it can. recent is the commands that can still be undone,
     * newest first and without the Shield's own, and at is the hit's place among them.
     */
    private static String refusal(CommandRecorder.Recording recording, ReactionOffer offer,
                                  EncounterParticipant caster, EncounterParticipant attacker,
                                  List<CombatCommand> recent, int at) {
        boolean resolves = offer.options().stream()
                .anyMatch(option -> ReactionOffers.isShield(option) && option.resolves());
        if (!resolves) {
            return "the same action did more to " + caster.getDisplayName() + " than this hit";
        }
        if (at < 0) {
            return "it can no longer be undone";
        }
        if (attacker == null) {
            return "its attacker has left";
        }
        CombatCommand hit = recent.get(at);
        if (changedOthers(hit, Set.of(attacker.getId(), caster.getId()))) {
            return "the same action changed other creatures too";
        }
        List<CombatCommand> since = recent.subList(0, at);
        if (since.stream().anyMatch(command -> stateIn(command, caster.getId()).isPresent())) {
            return "later actions have changed " + caster.getDisplayName();
        }
        // The attacker as the hit left it: as the oldest later command to change it found it, or as it is now
        Optional<ParticipantCombatState> afterHit = Optional.empty();
        for (int i = since.size() - 1; i >= 0 && afterHit.isEmpty(); i--) {
            afterHit = stateIn(since.get(i), attacker.getId());
        }
        ParticipantCombatState now = ParticipantCombatState.of(attacker);
        if (afterHit.isPresent() && !recording.same(notKept(afterHit.get()), notKept(now))) {
            return "later actions have changed " + attacker.getDisplayName();
        }
        Optional<ParticipantCombatState> beforeHit = stateIn(hit, attacker.getId());
        if (beforeHit.isPresent() && leftOn(beforeHit.get(), afterHit.orElse(now), caster.getId())) {
            return "the attack left something of " + attacker.getDisplayName() + "'s on " + caster.getDisplayName();
        }
        return null;
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

    // The participant as the command found it, when the command changed it
    private static Optional<ParticipantCombatState> stateIn(CombatCommand command, UUID participantId) {
        CommandSnapshot before = command.getBeforeState();
        if (before == null || before.participants() == null) {
            return Optional.empty();
        }
        return before.participants().stream().filter(state -> participantId.equals(state.id())).findFirst();
    }

    // The state without what KeptCosts keeps, which taking the hit back leaves as it is now
    private static ParticipantCombatState notKept(ParticipantCombatState state) {
        return new ParticipantCombatState(state.id(), state.hpCurrent(), state.hpTemp(), state.lifeState(),
                state.deathSaveSuccesses(), state.deathSaveFailures(), state.deathSaveDue(), null, null, null, null,
                null, null, state.lastLairActionUsed(), Boolean.TRUE.equals(state.dismissed()), state.summon(),
                Objects.requireNonNullElse(state.exhaustionLevel(), 0), state.controlledByUserId(), null, null);
    }

    /*
     * Whether the attack changed what the attacker keeps in a way tied to the wizard: a Spell Effect of the attacker's
     * on the wizard started or ended, as Ray of Enfeeblement's on a hit, or a condition the wizard is the source of
     * gained or lost. Keeping those as the attack left them would keep part of the hit.
     */
    private static boolean leftOn(ParticipantCombatState before, ParticipantCombatState after, UUID wizardId) {
        if (!effectsOn(before, wizardId).equals(effectsOn(after, wizardId))) {
            return true;
        }
        return !conditionsFrom(before, wizardId).equals(conditionsFrom(after, wizardId));
    }

    private static Set<UUID> effectsOn(ParticipantCombatState state, UUID wizardId) {
        Set<UUID> ids = new HashSet<>();
        for (SpellEffectEntry effect : state.activeEffects() != null ? state.activeEffects()
                : List.<SpellEffectEntry>of()) {
            if (effect != null && effect.affected() != null && effect.affected().contains(wizardId)) {
                ids.add(effect.id());
            }
        }
        return ids;
    }

    private static Set<UUID> conditionsFrom(ParticipantCombatState state, UUID wizardId) {
        Set<UUID> ids = new HashSet<>();
        for (ConditionEntry condition : state.activeConditions() != null ? state.activeConditions()
                : List.<ConditionEntry>of()) {
            if (condition != null && wizardId.equals(condition.sourceParticipantId())) {
                ids.add(condition.id());
            }
        }
        return ids;
    }
}
