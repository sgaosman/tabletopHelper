package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes the full encounter state after every change (D004), once per view (D157): the Player
 * View to every campaign member, the DM View to the DM. {@link EncounterSubscriptionGuard} decides
 * who may subscribe to which.
 */
@Component
@RequiredArgsConstructor
public class EncounterBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public static String stateDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/state";
    }

    public static String dmStateDestination(UUID encounterId) {
        return "/topic/encounter/" + encounterId + "/dm-state";
    }

    public void publish(EncounterResponse dmView) {
        messagingTemplate.convertAndSend(dmStateDestination(dmView.getId()), dmView);
        messagingTemplate.convertAndSend(stateDestination(dmView.getId()), EncounterViews.playerView(dmView));
    }
}
