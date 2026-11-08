package com.tabletophelper.encounter;

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

    // Publishes both views and both combat logs, so the client that just subscribed receives what it may
    // see (D157, D171)
    @MessageMapping("/encounter/{encounterId}/join")
    public void joinEncounter(
            @DestinationVariable UUID encounterId,
            SimpMessageHeaderAccessor headerAccessor) {
        UUID userId = extractUserId(headerAccessor);
        // Read and published in order with the changes' broadcasts, so a join never sends an older state last
        broadcaster.publishOnJoin(encounterId, () -> encounterService.getEncounter(encounterId, userId));
    }

    private UUID extractUserId(SimpMessageHeaderAccessor accessor) {
        UsernamePasswordAuthenticationToken auth =
                (UsernamePasswordAuthenticationToken) accessor.getUser();
        return (UUID) auth.getPrincipal();
    }
}
