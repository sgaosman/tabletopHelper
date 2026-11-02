package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class EncounterWebSocketController {

    private final EncounterService encounterService;
    private final EncounterBroadcaster broadcaster;

    // Publishes both views, so the client that just subscribed receives the state it may see (D157)
    @MessageMapping("/encounter/{encounterId}/join")
    public void joinEncounter(
            @DestinationVariable UUID encounterId,
            SimpMessageHeaderAccessor headerAccessor) {
        UUID userId = extractUserId(headerAccessor);
        EncounterResponse state = encounterService.getEncounter(encounterId, userId);
        broadcaster.publish(state);
    }

    private UUID extractUserId(SimpMessageHeaderAccessor accessor) {
        UsernamePasswordAuthenticationToken auth =
                (UsernamePasswordAuthenticationToken) accessor.getUser();
        return (UUID) auth.getPrincipal();
    }
}
