package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.ParticipantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A table of one player character against a visible goblin and a bugbear the DM may hide, with one
 * log entry about each monster.
 */
@ExtendWith(MockitoExtension.class)
class EncounterBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private CombatLogRepository combatLogRepository;

    private EncounterBroadcaster broadcaster;

    private final UUID encounterId = UUID.randomUUID();
    private final UUID ariaId = UUID.randomUUID();
    private final UUID goblinId = UUID.randomUUID();
    private final UUID bugbearId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        broadcaster = new EncounterBroadcaster(messagingTemplate, new CombatLogReader(combatLogRepository));
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
                .isAlive(true)
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
}
