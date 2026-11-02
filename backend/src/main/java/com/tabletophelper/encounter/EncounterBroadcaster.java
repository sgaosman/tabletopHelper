package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Publishes the full encounter state after every change (D004), once per view (D157): the Player
 * View to every campaign member, the DM View to the DM. The full combat log follows on topics of its
 * own, the players' copy without the entries about participants the DM View marks hidden, so revealing
 * a monster sends its entries again (D171). {@link EncounterSubscriptionGuard} decides who may
 * subscribe to which.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncounterBroadcaster {

    private static final int PUBLISH_STRIPES = 64;

    private final SimpMessagingTemplate messagingTemplate;
    private final CombatLogReader combatLogReader;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    // An encounter's listeners hold its stripe from the reload to the last message, so two commits close
    // together publish in the order they reloaded and an older state never follows a newer one
    private final Object[] publishStripes = Stream.generate(Object::new).limit(PUBLISH_STRIPES).toArray();

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
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void encounterChanged(EncounterChanged event) {
        UUID encounterId = event.encounterId();
        synchronized (publishStripes[Math.floorMod(encounterId.hashCode(), PUBLISH_STRIPES)]) {
            // Empty once the encounter is deleted, which leaves nothing to show
            Optional<EncounterResponse> dmView = encounterRepository.findById(encounterId)
                    .map(encounterService::toResponse);
            if (dmView.isEmpty()) {
                return;
            }
            try {
                publish(dmView.get());
            } catch (MessagingException e) {
                log.error("Could not broadcast encounter {} after its change committed", encounterId, e);
            }
        }
    }

    public void publish(EncounterResponse dmView) {
        UUID encounterId = dmView.getId();
        messagingTemplate.convertAndSend(dmStateDestination(encounterId), dmView);
        messagingTemplate.convertAndSend(stateDestination(encounterId), EncounterViews.playerView(dmView));

        List<CombatLogResponse> dmLog = combatLogReader.dmLog(encounterId);
        messagingTemplate.convertAndSend(dmLogDestination(encounterId), dmLog);
        messagingTemplate.convertAndSend(logDestination(encounterId),
                EncounterViews.playerLog(dmLog, EncounterViews.hiddenParticipants(dmView)));
    }
}
