package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatCommandRepository;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.UndoInfo;
import com.tabletophelper.encounter.dto.UndoableCommand;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.EncounterTurnState;
import com.tabletophelper.encounter.state.LogPatch;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.encounter.state.ReactionOffer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records each combat command for undo (M14). {@link CombatAccess} starts the recording once a command has passed
 * its checks, so every combat service is covered without code of its own: the participants' combat state and the
 * encounter's turn are copied as they are, and a {@link CombatCommand} row is written with the next sequence, which
 * the encounter's row lock keeps in order. While the transaction runs, {@link CombatLogWriter} stamps each entry with
 * the command, and a command that changes an older entry, as Legendary Resistance does, notes it first
 * ({@link Recording#patched}). Before the transaction commits, the copy is compared with the state then, and only
 * what changed is kept: the participants the command changed, cascades included, and the encounter's turn if it
 * moved. A command that changed nothing and wrote nothing is dropped, and one that rolls back leaves nothing behind.
 */
@Component
@RequiredArgsConstructor
public class CommandRecorder {

    /** The most commands one undo may take, and how many the DM View lists. */
    public static final int MAX_STEPS = 20;

    /** Why a command can't be undone: it isn't the newest any more. */
    public static final String STALE = "The last action has changed since you reviewed it; review it again";

    // The entry types that sum a command up, the likeliest first: a spell's summary over its slot's entry, and Next
    // Turn's turn entry over the saves and conditions that ended with the turn
    private static final List<CombatActionType> HEADLINES = List.of(
            CombatActionType.SPELL_CAST, CombatActionType.SPELL_EFFECT_REPEAT, CombatActionType.ATTACK,
            CombatActionType.MONSTER_ACTION, CombatActionType.LEGENDARY_ACTION, CombatActionType.LAIR_ACTION,
            CombatActionType.REACTION_TAKEN, CombatActionType.LEGENDARY_RESISTANCE_USED,
            CombatActionType.TURN_ADVANCE, CombatActionType.TURN_BACK, CombatActionType.STANDARD_ACTION,
            CombatActionType.OBJECT_INTERACTION, CombatActionType.POOL_USE, CombatActionType.HP_SET,
            CombatActionType.DAMAGE, CombatActionType.HEAL, CombatActionType.DEATH_SAVE, CombatActionType.REPEAT_SAVE,
            CombatActionType.CONDITION_ADD, CombatActionType.CONDITION_REMOVE, CombatActionType.SPELL_SLOT_USE,
            CombatActionType.SPELL_SLOT_RESTORE, CombatActionType.TURN_ECONOMY_SET);

    private static final Object RESOURCE_KEY = CommandRecorder.class;

    private final CombatCommandRepository commands;
    private final CombatLogRepository combatLogs;
    private final ObjectMapper objectMapper;

    /** The command the current transaction is recording, if any. */
    public static Optional<Recording> current() {
        return Optional.ofNullable((Recording) TransactionSynchronizationManager.getResource(RESOURCE_KEY));
    }

    /**
     * The encounter's turn has changed (D216): the reaction offers of earlier commands go. Outside a command, every
     * offer goes.
     */
    public static void turnChanged(Encounter encounter) {
        Optional<Recording> recording = current()
                .filter(r -> r.encounter == encounter || Objects.equals(r.encounter.getId(), encounter.getId()));
        if (recording.isPresent()) {
            recording.get().turnChanged();
        } else {
            encounter.setReactionOffers(new ArrayList<>());
        }
    }

    /**
     * Starts recording the command the user is giving on the locked, Active encounter. Outside a transaction, or
     * when the transaction is recording one already, it does nothing.
     */
    public void record(Encounter encounter, UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() || current().isPresent()) {
            return;
        }
        CombatCommand command = commands.save(CombatCommand.builder()
                .encounterId(encounter.getId())
                .sequence(commands.lastSequence(encounter.getId()) + 1)
                .issuedBy(userId)
                .beforeState(CommandSnapshot.empty())
                .build());
        Recording recording = new Recording(command, encounter);
        // The reaction offers stand until answered, dismissed, their reactor's reaction is spent or the turn changes
        // (D216). Those that can no longer be taken go now, and so does a Counterspell offer, which only answers the
        // newest command (D196): this one comes after its spell, unless it turns out to change nothing
        recording.offersAtStart = ReactionOffer.without(encounter.getReactionOffers(), offer -> false);
        encounter.setReactionOffers(ReactionOffer.without(recording.offersAtStart,
                offer -> offer.trigger() == ReactionOffer.Trigger.SPELL_CAST
                        || !ReactionOffers.open(encounter, offer)));
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, recording);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                // The command's response has usually settled it already (D228); settling again changes nothing, and
                // covers a command that answers without the encounter's state
                recording.finish();
                if (recording.isEmpty()) {
                    commands.delete(recording.command);
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
            }
        });
    }

    /**
     * Settles the command the transaction is recording on the encounter, as its commit would (D228): its Shield offers
     * kept only while the hit is all it did to the wizard, what it changed and what sums it up, and, for a command that
     * changed nothing, the Counterspell offers it set aside. A response built after this shows the offers and undo as
     * they are stored. Outside a recording of the encounter it does nothing, and settling twice changes nothing more.
     */
    public static void settle(Encounter encounter) {
        current().filter(r -> r.encounter == encounter || Objects.equals(r.encounter.getId(), encounter.getId()))
                .ifPresent(Recording::finish);
    }

    /**
     * Records a barrier, which undo can't go past: the encounter's start, or a participant's removal mid-fight,
     * whose Write-back has already changed their sheet (D166).
     */
    public void barrier(Encounter encounter, UUID userId, String kind, String summary) {
        commands.save(CombatCommand.builder()
                .encounterId(encounter.getId())
                .sequence(commands.lastSequence(encounter.getId()) + 1)
                .kind(kind)
                .summary(summary)
                .issuedBy(userId)
                .beforeState(CommandSnapshot.empty())
                .barrier(true)
                .build());
    }

    /** Clears what the encounter's commands changed once it has completed: nothing can be undone after. */
    public void completed(Encounter encounter) {
        commands.clearBeforeStates(encounter.getId());
    }

    /** The commands the DM may undo, newest first and back to the last barrier, at most {@code limit}. */
    public List<CombatCommand> undoable(UUID encounterId, int limit) {
        return commands.undoable(encounterId, commands.lastBarrier(encounterId), PageRequest.of(0, limit));
    }

    /**
     * What the DM's Undo would take back on the encounter, for the DM View; empty unless it is Active and has a
     * command to undo. Inside a command, the one being recorded counts once it has changed something.
     */
    public Optional<UndoInfo> undoInfo(Encounter encounter) {
        if (encounter.getId() == null || encounter.getStatus() != EncounterStatus.ACTIVE) {
            return Optional.empty();
        }
        UUID unchanged = current()
                .filter(recording -> recording.encounter.getId().equals(encounter.getId()))
                .filter(recording -> {
                    recording.settle();
                    return recording.isEmpty();
                })
                .map(Recording::id)
                .orElse(null);

        long barrier = commands.lastBarrier(encounter.getId());
        List<CombatCommand> recent = commands.undoable(encounter.getId(), barrier, PageRequest.of(0, MAX_STEPS + 1))
                .stream()
                .filter(command -> !command.getId().equals(unchanged))
                .limit(MAX_STEPS)
                .toList();
        if (recent.isEmpty()) {
            return Optional.empty();
        }
        long available = commands.countUndoable(encounter.getId(), barrier) - (unchanged != null ? 1 : 0);

        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : combatLogs.countByCommand(recent.stream().map(CombatCommand::getId).toList())) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        List<UndoableCommand> listed = recent.stream()
                .map(command -> new UndoableCommand(command.getId(), kindOf(command), summaryOf(command),
                        counts.getOrDefault(command.getId(), 0)))
                .toList();
        UndoableCommand head = listed.getFirst();
        return Optional.of(new UndoInfo(head.commandId(), head.kind(), head.summary(), head.entryCount(), available,
                listed));
    }

    static String kindOf(CombatCommand command) {
        return command.getKind() != null ? command.getKind() : "COMMAND";
    }

    static String summaryOf(CombatCommand command) {
        return command.getSummary() != null ? command.getSummary() : "An action";
    }

    /**
     * The command a transaction is recording: the state it found, the entries it wrote and the older entries it
     * changed.
     */
    public final class Recording {

        private final CombatCommand command;
        private final Encounter encounter;
        private final Map<UUID, JsonNode> participantsBefore = new LinkedHashMap<>();
        private final JsonNode encounterBefore;
        private final List<CombatLog> entries = new ArrayList<>();
        private final Map<UUID, LogPatch> patches = new LinkedHashMap<>();
        private String kind;
        private String summary;
        // The reaction offers there were as the command started (D216)
        private List<ReactionOffer> offersAtStart;
        // What reaction offers work out once per command, such as each reactor's known spells (D216)
        private final Map<Object, Object> memo = new HashMap<>();
        // The reactor as each hit it may Shield against left it, by offer (D216)
        private final Map<UUID, ParticipantCombatState> hitEnds = new HashMap<>();

        private Recording(CombatCommand command, Encounter encounter) {
            this.command = command;
            this.encounter = encounter;
            // Copied as JSON at once: commands change lists in place, such as a participant's conditions
            for (EncounterParticipant participant : encounter.getParticipants()) {
                participantsBefore.put(participant.getId(), objectMapper.valueToTree(ParticipantCombatState.of(participant)));
            }
            this.encounterBefore = objectMapper.valueToTree(EncounterTurnState.of(encounter));
        }

        public UUID id() {
            return command.getId();
        }

        /** Notes an entry the command wrote, which {@link CombatLogWriter} has stamped with it. */
        public void written(CombatLog entry) {
            entries.add(entry);
        }

        /** Notes an older entry the command is about to change, as it is before the change. */
        public void patched(CombatLog entry) {
            patches.putIfAbsent(entry.getId(), new LogPatch(entry.getId(), entry.getLrResolved()));
        }

        /** The reaction offers there were as this command started, which an answer to one names (D216). */
        public List<ReactionOffer> offersAtStart() {
            return offersAtStart != null ? List.copyOf(offersAtStart) : List.of();
        }

        /** The command is the participant's reaction (D216): its reaction is spent, so its offers go. */
        public void reactionTaken(UUID reactorId) {
            encounter.setReactionOffers(ReactionOffer.withoutReactor(encounter.getReactionOffers(), reactorId));
        }

        /** Drops the offers the command triggered, which another command has taken back (D216). */
        public void commandTakenBack(UUID commandId) {
            encounter.setReactionOffers(ReactionOffer.withoutCommand(encounter.getReactionOffers(), commandId));
        }

        /**
         * The turn has changed (D216): the offers of the commands before this one go, as their moment has passed; those
         * this command has made since, such as for damage at the start of the new turn, stand.
         */
        public void turnChanged() {
            encounter.setReactionOffers(ReactionOffer.without(encounter.getReactionOffers(),
                    offer -> !command.getId().equals(offer.commandId())));
        }

        /**
         * The value worked out once for this command under the key, such as a reactor's known spells, which don't
         * change while one command runs (D216).
         */
        @SuppressWarnings("unchecked")
        public <T> T memo(Object key, java.util.function.Supplier<T> compute) {
            return (T) memo.computeIfAbsent(key, ignored -> compute.get());
        }

        /** Whether the participant was as it is in the state when the command started; true for one it brought in. */
        public boolean unchangedSinceStart(UUID participantId, ParticipantCombatState state) {
            JsonNode before = participantsBefore.get(participantId);
            return before == null || before.equals(objectMapper.valueToTree(state));
        }

        /** Notes the reactor as the hit an offer answers left it (D216). */
        public void hitEnded(UUID offerId, ParticipantCombatState state) {
            hitEnds.put(offerId, state);
        }

        /** The reactor as the hit the offer answers left it, or null for an offer this command didn't note. */
        public ParticipantCombatState hitEnd(UUID offerId) {
            return hitEnds.get(offerId);
        }

        /** Whether the two states of a participant are the same. */
        public boolean same(ParticipantCombatState one, ParticipantCombatState other) {
            return objectMapper.valueToTree(one).equals(objectMapper.valueToTree(other));
        }

        // Everything the command's end settles (D228), which runs twice: as its response is built and before it commits
        private void finish() {
            ReactionOffers.settle(encounter, this);
            settle();
            if (isEmpty()) {
                counterspellOffersBack();
            }
        }

        // A command that changed nothing doesn't follow the spell a Counterspell offer answers, so the offer stands;
        // one already back isn't added twice
        private void counterspellOffersBack() {
            List<ReactionOffer> offers = new ArrayList<>(encounter.getReactionOffers() != null
                    ? encounter.getReactionOffers() : List.of());
            for (ReactionOffer offer : offersAtStart != null ? offersAtStart : List.<ReactionOffer>of()) {
                if (offer.trigger() == ReactionOffer.Trigger.SPELL_CAST && !offers.contains(offer)
                        && ReactionOffers.open(encounter, offer)) {
                    offers.add(offer);
                }
            }
            encounter.setReactionOffers(offers);
        }

        /** Names the command for one that writes no entry, such as a change of which monsters fight in their lair. */
        public void describe(String kind, String summary) {
            this.kind = kind;
            this.summary = summary;
        }

        /** What the command changed, as it was before. */
        public CommandSnapshot snapshot() {
            List<ParticipantCombatState> changed = new ArrayList<>();
            List<UUID> created = new ArrayList<>();
            for (EncounterParticipant participant : encounter.getParticipants()) {
                JsonNode before = participantsBefore.get(participant.getId());
                if (before == null) {
                    // Brought into the fight by the command, such as a spell's summoned creatures (D182); a row not
                    // yet saved has no ID, and the command saves each it creates before it ends
                    if (participant.getId() != null) {
                        created.add(participant.getId());
                    }
                } else if (!before.equals(objectMapper.valueToTree(ParticipantCombatState.of(participant)))) {
                    changed.add(objectMapper.treeToValue(before, ParticipantCombatState.class));
                }
            }
            JsonNode turnNow = objectMapper.valueToTree(EncounterTurnState.of(encounter));
            EncounterTurnState turn = encounterBefore.equals(turnNow)
                    ? null : objectMapper.treeToValue(encounterBefore, EncounterTurnState.class);
            return new CommandSnapshot(changed, turn, List.copyOf(patches.values()),
                    created.isEmpty() ? null : List.copyOf(created));
        }

        /** Whether the command has changed nothing and written nothing, so there is nothing to undo. */
        public boolean isEmpty() {
            return entries.isEmpty() && Objects.requireNonNullElse(command.getBeforeState(), CommandSnapshot.empty())
                    .isEmpty();
        }

        // Writes what the command changed, and what sums it up, onto its row
        private void settle() {
            command.setBeforeState(snapshot());
            CombatLog headline = headline();
            command.setKind(kind != null ? kind : headline != null ? headline.getActionType().name() : null);
            command.setSummary(summary != null ? summary : headline != null ? headline.getDescription() : null);
        }

        // The entry that sums the command up: of the likeliest type it wrote, the one naming no target, such as a
        // spell's summary, or else the first
        private CombatLog headline() {
            for (CombatActionType type : HEADLINES) {
                List<CombatLog> ofType = entries.stream().filter(entry -> entry.getActionType() == type).toList();
                if (!ofType.isEmpty()) {
                    return ofType.stream().filter(entry -> entry.getTargetId() == null).findFirst()
                            .orElse(ofType.getFirst());
                }
            }
            return entries.isEmpty() ? null : entries.getFirst();
        }
    }
}
