package com.questkeeper.encounter;

import com.questkeeper.encounter.dto.EncounterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class EncounterWebSocketController {

    private final EncounterService encounterService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/encounter/{encounterId}/join")
    public void joinEncounter(
            @DestinationVariable UUID encounterId,
            SimpMessageHeaderAccessor headerAccessor) {
        UUID userId = extractUserId(headerAccessor);
        EncounterResponse state = encounterService.getEncounter(encounterId, userId);
        messagingTemplate.convertAndSend(
                "/topic/encounter/" + encounterId + "/state", state);
    }

    private UUID extractUserId(SimpMessageHeaderAccessor accessor) {
        UsernamePasswordAuthenticationToken auth =
                (UsernamePasswordAuthenticationToken) accessor.getUser();
        return (UUID) auth.getPrincipal();
    }
}
