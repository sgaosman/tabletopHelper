package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.ParticipantResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A table of one player character against a visible goblin and a bugbear the DM may hide, with one
 * log entry about each monster.
 */
@ExtendWith(MockitoExtension.class)
class EncounterBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private CombatLogRepository combatLogRepository;
    @Mock private CombatCommandRepository combatCommandRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private EncounterService encounterService;

    private EncounterBroadcaster broadcaster;

    private final UUID encounterId = UUID.randomUUID();
    private final UUID ariaId = UUID.randomUUID();
    private final UUID goblinId = UUID.randomUUID();
    private final UUID bugbearId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        broadcaster = new EncounterBroadcaster(messagingTemplate, new CombatLogReader(combatLogRepository, combatCommandRepository),
                encounterRepository, encounterService);
        lenient().when(combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId)).thenReturn(List.of(
                logEntry("Aria hits Goblin", ariaId, goblinId),
                logEntry("Bugbear hits Aria", bugbearId, ariaId)));
    }

    private ParticipantResponse participant(UUID id, String name, ParticipantType type, boolean visible) {
        return ParticipantResponse.builder()
                .id(id)
                .participantType(type.name())
                .displayName(name)
                .hpMax(20)
                .hpCurrent(20)
                .armourClass(14)
                .isVisibleToPlayers(visible)
                .lifeState("ALIVE")
                .build();
    }

    private EncounterResponse dmView(boolean bugbearVisible) {
        return EncounterResponse.builder()
                .id(encounterId)
                .name("Goblin Ambush")
                .status("ACTIVE")
                .roundNumber(1)
                .participants(List.of(
                        participant(ariaId, "Aria", ParticipantType.PLAYER, true),
                        participant(goblinId, "Goblin", ParticipantType.MONSTER, true),
                        participant(bugbearId, "Bugbear", ParticipantType.MONSTER, bugbearVisible)))
                .build();
    }

    private CombatLog logEntry(String description, UUID actorId, UUID targetId) {
        return CombatLog.builder()
                .id(UUID.randomUUID())
                .roundNumber(1)
                .actorId(actorId)
                .targetId(targetId)
                .actionType(CombatActionType.ATTACK)
                .description(description)
                .createdAt(Instant.now())
                .build();
    }

    /** Publishes the DM View and returns what each destination received, in the order it was sent. */
    private Map<String, Object> publish(EncounterResponse dmView) {
        var destinations = ArgumentCaptor.forClass(String.class);
        var payloads = ArgumentCaptor.forClass(Object.class);

        broadcaster.publish(dmView);

        verify(messagingTemplate, times(4)).convertAndSend(destinations.capture(), payloads.capture());
        clearInvocations(messagingTemplate);
        Map<String, Object> sent = new LinkedHashMap<>();
        for (int i = 0; i < destinations.getAllValues().size(); i++) {
            sent.put(destinations.getAllValues().get(i), payloads.getAllValues().get(i));
        }
        return sent;
    }

    private static List<UUID> participantIds(Object state) {
        return ((EncounterResponse) state).getParticipants().stream().map(ParticipantResponse::getId).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> descriptions(Object log) {
        return ((List<CombatLogResponse>) log).stream().map(CombatLogResponse::getDescription).toList();
    }

    @Test
    @DisplayName("Publishes the DM View to the DM and the Player View, without the hidden monster, to players")
    void publishesBothViews() {
        var sent = publish(dmView(false));

        assertEquals(List.of(ariaId, goblinId, bugbearId),
                participantIds(sent.get(EncounterBroadcaster.dmStateDestination(encounterId))));
        assertEquals(List.of(ariaId, goblinId),
                participantIds(sent.get(EncounterBroadcaster.stateDestination(encounterId))));
    }

    @Test
    @DisplayName("Publishes the whole combat log to the DM, and to players the log without the hidden monster's entries (D171)")
    void publishesBothLogs() {
        var sent = publish(dmView(false));

        assertEquals(List.of("Aria hits Goblin", "Bugbear hits Aria"),
                descriptions(sent.get(EncounterBroadcaster.dmLogDestination(encounterId))));
        assertEquals(List.of("Aria hits Goblin"),
                descriptions(sent.get(EncounterBroadcaster.logDestination(encounterId))));
    }

    @Test
    @DisplayName("Once the DM reveals the monster, the next publish sends players its log entries again")
    void revealingTheMonsterRestoresItsEntries() {
        publish(dmView(false));

        var sent = publish(dmView(true));

        assertEquals(List.of("Aria hits Goblin", "Bugbear hits Aria"),
                descriptions(sent.get(EncounterBroadcaster.logDestination(encounterId))));
        assertEquals(List.of(ariaId, goblinId, bugbearId),
                participantIds(sent.get(EncounterBroadcaster.stateDestination(encounterId))));
    }

    @Test
    @DisplayName("Sends both states before both logs, reading the log once")
    void sendsStatesBeforeLogs() {
        var sent = publish(dmView(false));

        assertEquals(List.of(
                EncounterBroadcaster.dmStateDestination(encounterId),
                EncounterBroadcaster.stateDestination(encounterId),
                EncounterBroadcaster.dmLogDestination(encounterId),
                EncounterBroadcaster.logDestination(encounterId)), List.copyOf(sent.keySet()));
        verify(combatLogRepository).findByEncounterIdOrderByCreatedAtAsc(encounterId);
    }

    @Test
    @DisplayName("Maps every field of a log entry, as the log endpoint does")
    void mapsEveryLogField() {
        UUID entryId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-02T10:15:30Z");
        when(combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId)).thenReturn(List.of(
                CombatLog.builder()
                        .id(entryId).roundNumber(3)
                        .actorId(goblinId).actorName("Goblin").targetId(ariaId).targetName("Aria")
                        .actionType(CombatActionType.MONSTER_ACTION).description("Goblin slashes Aria")
                        .rollValue(15).rollTotal(19).damageDealt(5).healingDone(null)
                        .turnParticipantName("Goblin")
                        .legendaryResistanceEligible(true).lrTargetId(ariaId).lrDamageDealt(5)
                        .lrConditionsApplied("prone").lrResolved(false)
                        .createdAt(createdAt)
                        .build()));

        @SuppressWarnings("unchecked")
        var dmLog = (List<CombatLogResponse>) publish(dmView(false)).get(EncounterBroadcaster.dmLogDestination(encounterId));

        assertEquals(CombatLogResponse.builder()
                .id(entryId).roundNumber(3)
                .actorId(goblinId).actorName("Goblin").targetId(ariaId).targetName("Aria")
                .actionType("MONSTER_ACTION").description("Goblin slashes Aria")
                .rollValue(15).rollTotal(19).damageDealt(5)
                .turnParticipantName("Goblin")
                .legendaryResistanceEligible(true).lrTargetId(ariaId).lrDamageDealt(5)
                .lrConditionsApplied("prone").lrResolved(false)
                .createdAt(createdAt)
                .build(), dmLog.getFirst());
    }

    // ── Broadcasting after commit (D160) ────────────────────────

    /** The committed encounter the listener reloads, which toResponse turns into the DM View. */
    private void stubCommittedEncounter(boolean bugbearVisible) {
        Encounter committed = Encounter.builder().id(encounterId).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(committed));
        when(encounterService.toResponse(committed)).thenReturn(dmView(bugbearVisible));
    }

    private List<String> sentDestinations() {
        var destinations = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(destinations.capture(), any(Object.class));
        return destinations.getAllValues();
    }

    @Test
    @DisplayName("After a change, reloads the committed encounter and publishes both views and both logs")
    void encounterChangedPublishesCommittedState() {
        stubCommittedEncounter(false);

        broadcaster.encounterChanged(new EncounterChanged(encounterId));

        assertEquals(List.of(
                EncounterBroadcaster.dmStateDestination(encounterId),
                EncounterBroadcaster.stateDestination(encounterId),
                EncounterBroadcaster.dmLogDestination(encounterId),
                EncounterBroadcaster.logDestination(encounterId)), sentDestinations());
    }

    @Test
    @DisplayName("A join read before a change commits is published before that change's broadcast, never after it, "
            + "so clients don't end on the older state")
    void joinNeverPublishesAfterANewerChange() throws Exception {
        EncounterResponse before = dmView(false);
        before.setRoundNumber(1);
        Encounter committed = Encounter.builder().id(encounterId).build();
        EncounterResponse after = dmView(false);
        after.setRoundNumber(2);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(committed));
        when(encounterService.toResponse(committed)).thenReturn(after);
        CountDownLatch joinRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread join = new Thread(() -> broadcaster.publishOnJoin(encounterId, () -> {
            joinRead.countDown();
            await(release);
            return before;
        }));
        join.start();
        assertTrue(joinRead.await(5, TimeUnit.SECONDS));
        Thread change = new Thread(() -> broadcaster.encounterChanged(new EncounterChanged(encounterId)));
        change.start();
        // The change's broadcast either waits for the join or, without the wait, publishes first
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (change.getState() != Thread.State.BLOCKED && change.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        join.join(5000);
        change.join(5000);

        var destinations = ArgumentCaptor.forClass(String.class);
        var payloads = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(8)).convertAndSend(destinations.capture(), payloads.capture());
        Object lastDmState = null;
        for (int i = 0; i < destinations.getAllValues().size(); i++) {
            if (destinations.getAllValues().get(i).equals(EncounterBroadcaster.dmStateDestination(encounterId))) {
                lastDmState = payloads.getAllValues().get(i);
            }
        }
        assertSame(after, lastDmState);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("An encounter deleted by the change has nothing to publish")
    void deletedEncounterPublishesNothing() {
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        broadcaster.encounterChanged(new EncounterChanged(encounterId));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("A messaging failure after the commit is logged rather than thrown, so the request still succeeds")
    void messagingFailureIsNotThrown() {
        stubCommittedEncounter(false);
        doThrow(new MessageDeliveryException("Broker unavailable"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertDoesNotThrow(() -> broadcaster.encounterChanged(new EncounterChanged(encounterId)));
    }

    /**
     * The broadcaster in a bare Spring context (no Spring Boot) with a transaction manager that changes
     * nothing but runs Spring's transaction synchronization as a real one does, so the after-commit listener
     * and its own transaction behave as they do in the application.
     */
    @Nested
    class InTransactions {

        private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        private AnnotationConfigApplicationContext context;
        private TransactionTemplate transaction;

        @BeforeEach
        void startContext() {
            context = new AnnotationConfigApplicationContext();
            context.register(TransactionManagement.class);
            context.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean("encounterBroadcaster", EncounterBroadcaster.class, () -> broadcaster);
            context.refresh();
            transaction = new TransactionTemplate(transactionManager);
        }

        @AfterEach
        void closeContext() {
            context.close();
        }

        @Test
        @DisplayName("Nothing is published when the transaction rolls back, whether marked for rollback or failing")
        void rollbackPublishesNothing() {
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new EncounterChanged(encounterId));
                status.setRollbackOnly();
            });
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                context.publishEvent(new EncounterChanged(encounterId));
                throw new IllegalStateException("Command failed after announcing its change");
            }));

            verifyNoInteractions(messagingTemplate);
            verify(encounterRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Publishes only after the commit, on the committing thread, before the committing call returns")
        void publishesAfterCommitBeforeReturning() {
            Thread committingThread = Thread.currentThread();
            List<Thread> reloadThreads = new ArrayList<>();
            when(encounterRepository.findById(encounterId)).thenAnswer(invocation -> {
                reloadThreads.add(Thread.currentThread());
                return Optional.of(Encounter.builder().id(encounterId).build());
            });
            when(encounterService.toResponse(any(Encounter.class))).thenReturn(dmView(false));

            transaction.executeWithoutResult(status -> {
                context.publishEvent(new EncounterChanged(encounterId));
                verifyNoInteractions(messagingTemplate);
            });

            // The service call that committed has returned to its controller, which has not answered yet
            assertEquals(4, sentDestinations().size());
            assertEquals(List.of(committingThread), reloadThreads);
        }

        @Test
        @DisplayName("Reloads the committed state in a read-only transaction of its own")
        void reloadsInItsOwnReadOnlyTransaction() {
            List<Boolean> readOnlyDuringReload = new ArrayList<>();
            when(encounterRepository.findById(encounterId)).thenAnswer(invocation -> {
                readOnlyDuringReload.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return Optional.empty();
            });

            transaction.executeWithoutResult(status -> context.publishEvent(new EncounterChanged(encounterId)));

            assertEquals(List.of(true), readOnlyDuringReload);
            assertEquals(2, transactionManager.begun.size());
            TransactionDefinition reload = transactionManager.begun.get(1);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, reload.getPropagationBehavior());
            assertTrue(reload.isReadOnly());
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionManagement {
    }

    /** Begins, commits and rolls back nothing, and records each transaction it is asked to begin. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<TransactionDefinition> begun = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.add(definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
