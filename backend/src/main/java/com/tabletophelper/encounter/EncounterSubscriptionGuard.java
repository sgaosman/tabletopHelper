package com.tabletophelper.encounter;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks every SUBSCRIBE and SEND frame from a STOMP client (D157); CONNECT is checked by
 * {@code WebSocketAuthInterceptor} (D015). A subscription must name one of five destinations
 * exactly, so the simple broker's wildcard patterns never reach it:
 * <ul>
 *   <li>{@code /topic/encounter/{id}/state}, the Player View, for members of the encounter's campaign</li>
 *   <li>{@code /topic/encounter/{id}/log}, the players' combat log, for members too (D171)</li>
 *   <li>{@code /topic/encounter/{id}/dm-state}, the DM View, for the campaign's DM</li>
 *   <li>{@code /topic/encounter/{id}/dm-log}, the whole combat log, for the DM too (D171)</li>
 *   <li>{@code /user/queue/encounter/errors}, the user's own error queue</li>
 * </ul>
 * A client may send only to the application's handlers under {@code /app/}, never straight to a
 * broker or user destination. A refused frame gets an ERROR frame, and Spring then closes the
 * connection.
 */
@Component
@RequiredArgsConstructor
public class EncounterSubscriptionGuard implements ChannelInterceptor {

    private static final String ERROR_QUEUE = "/user/queue/encounter/errors";
    private static final String APP_PREFIX = "/app/";
    private static final String DM_TOPIC_PREFIX = "dm-";

    // Lower case, as UUID.toString() writes it and as the broadcaster publishes
    private static final Pattern ENCOUNTER_TOPIC = Pattern.compile(
            "/topic/encounter/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(state|log|dm-state|dm-log)");

    private final EncounterRepository encounterRepository;
    private final AccessPolicy accessPolicy;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(headers);
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        if (type == SimpMessageType.SUBSCRIBE) {
            checkSubscribe(destination, userId(SimpMessageHeaderAccessor.getUser(headers)));
        }
        else if (type == SimpMessageType.MESSAGE) {
            checkSend(destination);
        }
        return message;
    }

    private void checkSubscribe(String destination, UUID userId) {
        if (ERROR_QUEUE.equals(destination)) {
            return;
        }
        Matcher topic = ENCOUNTER_TOPIC.matcher(destination != null ? destination : "");
        if (!topic.matches()) {
            throw new ForbiddenException("You cannot subscribe to " + destination);
        }
        UUID campaignId = encounterRepository.findCampaignIdById(UUID.fromString(topic.group(1)))
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        if (topic.group(2).startsWith(DM_TOPIC_PREFIX)) {
            accessPolicy.requireDm(campaignId, userId);
        }
        else {
            accessPolicy.requireMember(campaignId, userId);
        }
    }

    private static void checkSend(String destination) {
        if (destination == null || !destination.startsWith(APP_PREFIX) || isPattern(destination)) {
            throw new ForbiddenException("You cannot send to " + destination);
        }
    }

    // The characters AntPathMatcher treats as a pattern
    private static boolean isPattern(String destination) {
        return destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0 || destination.indexOf('{') >= 0;
    }

    // WebSocketAuthInterceptor stores the user's ID as the principal at CONNECT
    private static UUID userId(Principal user) {
        if (user instanceof Authentication authentication && authentication.getPrincipal() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("You must be signed in to subscribe");
    }
}
