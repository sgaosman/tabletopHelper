package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Publishes the full encounter state after every change (D004), once per view (D157): the Player
 * View to every campaign member, the DM View to the DM. The full combat log follows on topics of its
 * own, the players' copy without the entries about participants the DM View marks hidden, so revealing
 * a monster sends its entries again (D171). {@link EncounterSubscriptionGuard} decides who may
 * subscribe to which.
 *
 * <p>Each publication reads the encounter, its participants and its combat log in one read-only REPEATABLE READ
 * transaction (D228), so every statement sees the same committed state and a broadcast never mixes two commands'
 * results. That transaction begins only once the encounter's stripe is held, and ends before the messages are
 * sent: a broadcast queued behind another for the same encounter waits holding only its request's connection, not a
 * second one, and holds that second connection only for the reads.
 */
@Slf4j
@Component
public class EncounterBroadcaster {

    private static final int PUBLISH_STRIPES = 64;

    private final SimpMessagingTemplate messagingTemplate;
    private final CombatLogReader combatLogReader;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;
    // A transaction of its own (the request's has committed by the time a change is published), read-only and
    // REPEATABLE READ, so the reads share one snapshot (D228)
    private final TransactionTemplate snapshotReads;

    // An encounter's listeners and joins hold its stripe from the reload to the last message, so two commits
    // close together, or a commit and a join, publish in the order they read and an older state never follows a
    // newer one
    private final Object[] publishStripes = Stream.generate(Object::new).limit(PUBLISH_STRIPES).toArray();

    public EncounterBroadcaster(SimpMessagingTemplate messagingTemplate, CombatLogReader combatLogReader,
                                EncounterRepository encounterRepository, EncounterService encounterService,
                                PlatformTransactionManager transactionManager) {
        this.messagingTemplate = messagingTemplate;
        this.combatLogReader = combatLogReader;
        this.encounterRepository = encounterRepository;
        this.encounterService = encounterService;
        this.snapshotReads = new TransactionTemplate(transactionManager);
        snapshotReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotReads.setReadOnly(true);
    }

    public static String stateDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/state";
    }

    public static String dmStateDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/dm-state";
    }

    public static String logDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/log";
    }

    public static String dmLogDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/dm-log";
    }

    /**
     * Publishes an encounter once a command that changed it commits (D160), and never for one that rolled
     * back. It reloads the committed state in a transaction of its own, so a late broadcast never sends an
     * older state than the one committed. It runs on the request's thread before the response is written,
     * so the WebSocket message for a change is published before the HTTP response that reports it, which
     * useEncounterAction relies on. The change is committed whatever happens here, so a messaging failure
     * is logged rather than failing the request; clients catch up with the next change or on reconnecting.
     *
     * <p>The request's connection is still checked out while this runs, as Spring releases it only after the
     * after-commit callbacks, so the reload's transaction is opened inside the stripe rather than around this method
     * (D228): threads queued for the same encounter don't each hold a second connection while they wait. Publishing
     * from another thread once the request's connection is released would free that one too, but would let the HTTP
     * response overtake the WebSocket message.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void encounterChanged(EncounterChanged event) {
        UUID encounterId = event.encounterId();
        synchronized (stripe(encounterId)) {
            // Empty once the encounter is deleted, which leaves nothing to show
            Optional<Snapshot> snapshot = snapshotReads.execute(status -> encounterRepository.findById(encounterId)
                    .map(encounter -> new Snapshot(encounterService.toResponse(encounter),
                            combatLogReader.dmLog(encounterId))));
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            try {
                publish(snapshot.get().dmView(), snapshot.get().dmLog());
            } catch (MessagingException e) {
                log.error("Could not broadcast encounter {} after its change committed", encounterId, e);
            }
        }
    }

    /**
     * Publishes the encounter for a client that has just joined (D157, D171), read by {@code read} while holding
     * the encounter's stripe, as a change's broadcast does: a join read before a change commits is published before
     * that change's broadcast, never after it, so no client is left showing the older state. The read and the
     * combat log share one snapshot, as a change's broadcast's do (D228).
     */
    public void publishOnJoin(UUID encounterId, Supplier<EncounterResponse> read) {
        synchronized (stripe(encounterId)) {
            Snapshot snapshot = snapshotReads.execute(status -> {
                EncounterResponse dmView = read.get();
                return new Snapshot(dmView, combatLogReader.dmLog(dmView.getId()));
            });
            publish(snapshot.dmView(), snapshot.dmLog());
        }
    }

    private Object stripe(UUID encounterId) {
        return publishStripes[Math.floorMod(encounterId.hashCode(), PUBLISH_STRIPES)];
    }

    /** Publishes both views and both logs, reading the log now. */
    public void publish(EncounterResponse dmView) {
        publish(dmView, combatLogReader.dmLog(dmView.getId()));
    }

    private void publish(EncounterResponse dmView, List<CombatLogResponse> dmLog) {
        UUID encounterId = dmView.getId();
        messagingTemplate.convertAndSend(dmStateDestination(encounterId), dmView);
        messagingTemplate.convertAndSend(stateDestination(encounterId), EncounterViews.playerView(dmView));
        messagingTemplate.convertAndSend(dmLogDestination(encounterId), dmLog);
        messagingTemplate.convertAndSend(logDestination(encounterId),
                EncounterViews.playerLog(dmLog, EncounterViews.hiddenParticipants(dmView),
                        EncounterViews.monsters(dmView)));
    }

    // The encounter's DM View and whole combat log, read in one transaction
    private record Snapshot(EncounterResponse dmView, List<CombatLogResponse> dmLog) {
    }
}
