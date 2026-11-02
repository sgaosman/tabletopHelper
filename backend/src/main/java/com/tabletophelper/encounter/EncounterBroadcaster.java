package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Publishes the full encounter state after every change (D004), once per view (D157): the Player
 * View to every campaign member, the DM View to the DM. The full combat log follows on topics of its
 * own, the players' copy without the entries about participants the DM View marks hidden, so revealing
 * a monster sends its entries again (D171). {@link EncounterSubscriptionGuard} decides who may
 * subscribe to which.
 */
@Component
@RequiredArgsConstructor
public class EncounterBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final CombatLogReader combatLogReader;

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
