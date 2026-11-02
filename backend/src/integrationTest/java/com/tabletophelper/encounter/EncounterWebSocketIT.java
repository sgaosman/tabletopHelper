package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP over SockJS, connecting as the browser does. These assert today's behaviour; R3 adds the
 * subscription checks (D157).
 */
class EncounterWebSocketIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private MonsterRepository monsterRepository;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void createClient() {
        stompClient = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void disconnect() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }

    @Test
    @DisplayName("CONNECT without a token gets an ERROR frame, then the server closes the connection")
    void connectWithoutTokenIsRefused() throws Exception {
        var errorFrame = new CompletableFuture<StompHeaders>();
        var connected = stompClient.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        errorFrame.complete(headers);
                    }
                });

        // The interceptor's own reason never reaches the client, only the channel's wrapper message
        assertThat(errorFrame.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getFirst("message"))
                .isEqualTo("Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]");
        assertThat(connected).failsWithin(5, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(ConnectionLostException.class);
    }

    @Test
    @DisplayName("Subscribing, then sending /app/encounter/{id}/join, delivers the encounter state")
    void joinDeliversState() throws Exception {
        var dm = register("dungeonmaster");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        StompSession session = connect(dm.accessToken());
        BlockingQueue<JsonNode> states = subscribe(session, encounterId);

        JsonNode state = joinUntilStateArrives(session, encounterId, states);

        assertThat(state.get("id").asText()).isEqualTo(encounterId.toString());
        assertThat(state.get("status").asText()).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("A REST damage call broadcasts the new state to subscribers")
    void damageBroadcastsNewState() throws Exception {
        var dm = register("dungeonmaster");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        String goblinId = addGoblin(dm.accessToken(), encounterId);
        startEncounter(dm.accessToken(), encounterId);
        StompSession session = connect(dm.accessToken());
        BlockingQueue<JsonNode> states = subscribe(session, encounterId);
        joinUntilStateArrives(session, encounterId, states);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", goblinId, "amount", 3), dm.accessToken()));

        JsonNode state = awaitState(states, s -> hpOf(s, goblinId) != 7);
        assertThat(hpOf(state, goblinId)).isEqualTo(4);
    }

    @Test
    @DisplayName("A user outside the campaign who subscribes still receives the encounter's broadcasts")
    void nonMemberReceivesBroadcasts() throws Exception {
        var dm = register("dungeonmaster");
        var outsider = register("eavesdropper");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        String goblinId = addGoblin(dm.accessToken(), encounterId);
        StompSession dmSession = connect(dm.accessToken());
        BlockingQueue<JsonNode> leaked = subscribe(connect(outsider.accessToken()), encounterId);

        // Each join by the DM broadcasts to every subscriber of the topic
        JsonNode state = joinUntilStateArrives(dmSession, encounterId, leaked);

        // R3 (D157): expect the outsider's SUBSCRIBE to be refused. Today it even sees the monster's exact HP.
        assertThat(state.get("id").asText()).isEqualTo(encounterId.toString());
        assertThat(hpOf(state, goblinId)).isEqualTo(7);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String url() {
        return "http://localhost:" + port + "/ws";
    }

    private UUID campaignId(RegisteredUser dm) {
        return UUID.fromString(createCampaign(dm.accessToken()).get("id").asText());
    }

    /** Adds a 7 HP goblin and returns its participant id. */
    private String addGoblin(String dmToken, UUID encounterId) {
        Monster goblin = monsterRepository.save(Monster.builder()
                .name("Goblin").type("Humanoid").challengeRating("1/4").source("MM")
                .hitPoints(7).armourClass(15).dexterity(14)
                .build());
        JsonNode state = addParticipant(dmToken, encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin"));
        return state.get("participants").get(0).get("id").asText();
    }

    private StompSession connect(String accessToken) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        StompSession session = stompClient.connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, UUID encounterId) {
        BlockingQueue<JsonNode> states = new LinkedBlockingQueue<>();
        session.subscribe("/topic/encounter/" + encounterId + "/state", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                states.add((JsonNode) payload);
            }
        });
        return states;
    }

    // The server handles inbound frames on a thread pool, so a SUBSCRIBE can still be in flight when
    // the next frame is handled. Repeating the join until a state arrives proves the subscription is live.
    private JsonNode joinUntilStateArrives(StompSession sender, UUID encounterId, BlockingQueue<JsonNode> states)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            sender.send("/app/encounter/" + encounterId + "/join", new byte[0]);
            JsonNode state = states.poll(250, TimeUnit.MILLISECONDS);
            if (state != null) {
                return state;
            }
        }
        throw new AssertionError("No encounter state arrived within " + TIMEOUT_SECONDS + "s");
    }

    // Skips states queued by earlier joins until one matches
    private JsonNode awaitState(BlockingQueue<JsonNode> states, Predicate<JsonNode> matches) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
            JsonNode state = states.poll(left, TimeUnit.NANOSECONDS);
            if (state != null && matches.test(state)) {
                return state;
            }
        }
        throw new AssertionError("No matching encounter state arrived within " + TIMEOUT_SECONDS + "s");
    }

    private static int hpOf(JsonNode state, String participantId) {
        for (JsonNode participant : state.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant.get("hpCurrent").asInt();
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the state");
    }
}
