package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * STOMP over SockJS, connecting as the browser does. CONNECT needs an access token (D015, D156);
 * SUBSCRIBE and SEND go through EncounterSubscriptionGuard, and each view has its own topic (D157).
 */
class EncounterWebSocketIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    // The interceptor's own reason never reaches the client, only the channel's wrapper message
    private static final String REFUSED = "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]";

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

        assertThat(errorFrame.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getFirst("message")).isEqualTo(REFUSED);
        assertThat(connected).failsWithin(5, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(ConnectionLostException.class);
    }

    @Test
    @DisplayName("Subscribing to the DM View, then sending /app/encounter/{id}/join, delivers the encounter state")
    void joinDeliversState() throws Exception {
        var dm = register("dungeonmaster");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        StompSession session = connect(dm.accessToken());
        BlockingQueue<JsonNode> states = subscribe(session, EncounterBroadcaster.dmStateDestination(encounterId));

        JsonNode state = joinUntilStateArrives(session, encounterId, states);

        assertThat(state.get("id").asText()).isEqualTo(encounterId.toString());
        assertThat(state.get("status").asText()).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("A REST damage call broadcasts the new state to subscribers")
    void damageBroadcastsNewState() throws Exception {
        var dm = register("dungeonmaster");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        String goblinId = addMonster(dm.accessToken(), encounterId, "Goblin", 7);
        startEncounter(dm.accessToken(), encounterId);
        StompSession session = connect(dm.accessToken());
        BlockingQueue<JsonNode> states = subscribe(session, EncounterBroadcaster.dmStateDestination(encounterId));
        joinUntilStateArrives(session, encounterId, states);

        damage(dm.accessToken(), encounterId, goblinId, 3);

        JsonNode state = awaitState(states, s -> hpOf(s, goblinId) != 7);
        assertThat(hpOf(state, goblinId)).isEqualTo(4);
    }

    @Test
    @DisplayName("Each side receives its own view: the DM sees hidden monsters and exact HP, players a Health Status")
    void eachSideReceivesItsOwnView() throws Exception {
        var dm = register("dungeonmaster");
        var player = register("aria");
        UUID encounterId = createEncounter(dm.accessToken(), joinedCampaign(dm, player));
        String goblinId = addMonster(dm.accessToken(), encounterId, "Goblin", 7);
        String bugbearId = addMonster(dm.accessToken(), encounterId, "Bugbear", 27);
        hide(dm.accessToken(), encounterId, bugbearId);
        startEncounter(dm.accessToken(), encounterId);

        StompSession dmSession = connect(dm.accessToken());
        BlockingQueue<JsonNode> dmStates = subscribe(dmSession, EncounterBroadcaster.dmStateDestination(encounterId));
        joinUntilStateArrives(dmSession, encounterId, dmStates);
        StompSession playerSession = connect(player.accessToken());
        BlockingQueue<JsonNode> playerStates = subscribe(playerSession, EncounterBroadcaster.stateDestination(encounterId));
        joinUntilStateArrives(playerSession, encounterId, playerStates);

        damage(dm.accessToken(), encounterId, goblinId, 3);

        JsonNode dmState = awaitState(dmStates, s -> hpOf(s, goblinId) == 4);
        assertThat(hpOf(dmState, bugbearId)).isEqualTo(27);
        JsonNode playerState = awaitState(playerStates,
                s -> "INJURED".equals(participant(s, goblinId).path("healthStatus").asText()));
        assertThat(participantIds(playerState)).containsExactly(goblinId);
        assertThat(participant(playerState, goblinId).get("hpCurrent").isNull()).isTrue();
        assertThat(participant(playerState, goblinId).get("hpMax").isNull()).isTrue();
    }

    @Test
    @DisplayName("A user outside the campaign is refused when subscribing to the encounter")
    void nonMemberIsRefused() throws Exception {
        var dm = register("dungeonmaster");
        var outsider = register("eavesdropper");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        WatchedSession eavesdropper = connectWatchingErrors(outsider.accessToken());

        eavesdropper.session().subscribe(EncounterBroadcaster.stateDestination(encounterId), ignoreFrames());

        assertRefused(eavesdropper);
    }

    @Test
    @DisplayName("A player is refused when subscribing to the DM View")
    void playerIsRefusedOnDmState() throws Exception {
        var dm = register("dungeonmaster");
        var player = register("aria");
        UUID encounterId = createEncounter(dm.accessToken(), joinedCampaign(dm, player));
        WatchedSession watched = connectWatchingErrors(player.accessToken());

        watched.session().subscribe(EncounterBroadcaster.dmStateDestination(encounterId), ignoreFrames());

        assertRefused(watched);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/topic/**", "/topic/encounter/*/state", "/topic/encounter/%s/*"})
    @DisplayName("A wildcard subscription is refused, even for the campaign's DM")
    void wildcardSubscriptionIsRefused(String pattern) throws Exception {
        var dm = register("dungeonmaster");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));
        WatchedSession watched = connectWatchingErrors(dm.accessToken());

        watched.session().subscribe(pattern.formatted(encounterId), ignoreFrames());

        assertRefused(watched);
    }

    @Test
    @DisplayName("A SEND straight to an encounter topic is refused and reaches no subscriber")
    void sendToTopicIsRefused() throws Exception {
        var dm = register("dungeonmaster");
        var player = register("aria");
        var otherPlayer = register("bram");
        var campaign = createCampaign(dm.accessToken());
        join(player.accessToken(), campaign);
        join(otherPlayer.accessToken(), campaign);
        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));
        StompSession listener = connect(otherPlayer.accessToken());
        BlockingQueue<JsonNode> states = subscribe(listener, EncounterBroadcaster.stateDestination(encounterId));
        joinUntilStateArrives(listener, encounterId, states);
        WatchedSession forger = connectWatchingErrors(player.accessToken());

        forger.session().send(EncounterBroadcaster.stateDestination(encounterId),
                Map.of("id", encounterId.toString(), "name", "Forged state"));

        assertRefused(forger);
        // States from the listener's own joins may still arrive; the forged one never does
        for (JsonNode state = states.poll(500, TimeUnit.MILLISECONDS); state != null;
             state = states.poll(500, TimeUnit.MILLISECONDS)) {
            assertThat(state.get("name").asText()).isEqualTo("Goblin Ambush");
        }
    }

    @Test
    @DisplayName("A handshake from an origin outside CORS_ALLOWED_ORIGINS is refused; the configured origin connects")
    void foreignOriginIsRefused() throws Exception {
        var dm = register("dungeonmaster");
        WebSocketHttpHeaders foreign = new WebSocketHttpHeaders();
        foreign.setOrigin("http://evil.example");
        WebSocketHttpHeaders devServer = new WebSocketHttpHeaders();
        devServer.setOrigin("http://localhost:5173");

        var refused = stompClient.connectAsync(url(), foreign, authHeaders(dm.accessToken()),
                new StompSessionHandlerAdapter() {});
        StompSession allowed = stompClient.connectAsync(url(), devServer, authHeaders(dm.accessToken()),
                new StompSessionHandlerAdapter() {}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(allowed);

        assertThat(refused).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(allowed.isConnected()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private record WatchedSession(StompSession session, CompletableFuture<StompHeaders> errorFrame,
                                  CompletableFuture<Throwable> connectionLost) {}

    private String url() {
        return "http://localhost:" + port + "/ws";
    }

    private UUID campaignId(RegisteredUser dm) {
        return UUID.fromString(createCampaign(dm.accessToken()).get("id").asText());
    }

    /** Creates the DM's campaign with the player in it, and returns its id. */
    private UUID joinedCampaign(RegisteredUser dm, RegisteredUser player) {
        JsonNode campaign = createCampaign(dm.accessToken());
        join(player.accessToken(), campaign);
        return UUID.fromString(campaign.get("id").asText());
    }

    private void join(String playerToken, JsonNode campaign) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), playerToken));
    }

    /** Adds a monster with the given HP and returns its participant id. */
    private String addMonster(String dmToken, UUID encounterId, String name, int hitPoints) {
        Monster monster = monsterRepository.save(Monster.builder()
                .name(name).type("Humanoid").challengeRating("1").source("MM")
                .hitPoints(hitPoints).armourClass(15).dexterity(14)
                .build());
        JsonNode state = addParticipant(dmToken, encounterId,
                Map.of("participantType", "MONSTER", "monsterId", monster.getId(), "displayName", name));
        for (JsonNode participant : state.get("participants")) {
            if (participant.get("displayName").asText().equals(name)) {
                return participant.get("id").asText();
            }
        }
        throw new AssertionError(name + " is not in the encounter");
    }

    private void hide(String dmToken, UUID encounterId, String participantId) {
        expect(HttpStatus.OK, exchange(HttpMethod.PATCH,
                "/api/encounters/" + encounterId + "/participants/" + participantId + "/visibility",
                Map.of("visible", false), dmToken));
    }

    private void damage(String dmToken, UUID encounterId, String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dmToken));
    }

    private static StompHeaders authHeaders(String accessToken) {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        return connectHeaders;
    }

    private StompSession connect(String accessToken) throws Exception {
        StompSession session = stompClient.connectAsync(url(), new WebSocketHttpHeaders(), authHeaders(accessToken),
                new StompSessionHandlerAdapter() {}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    /** Connects and records the ERROR frame and the connection loss that follow a refused frame. */
    private WatchedSession connectWatchingErrors(String accessToken) throws Exception {
        var errorFrame = new CompletableFuture<StompHeaders>();
        var connectionLost = new CompletableFuture<Throwable>();
        StompSession session = stompClient.connectAsync(url(), new WebSocketHttpHeaders(), authHeaders(accessToken),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        errorFrame.complete(headers);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        connectionLost.complete(exception);
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return new WatchedSession(session, errorFrame, connectionLost);
    }

    // A refused frame gets an ERROR frame, and the server then closes the whole connection
    private static void assertRefused(WatchedSession watched) throws Exception {
        assertThat(watched.errorFrame().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getFirst("message")).isEqualTo(REFUSED);
        assertThat(watched.connectionLost().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ConnectionLostException.class);
    }

    private static StompFrameHandler ignoreFrames() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        };
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> states = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
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

    private static JsonNode participant(JsonNode state, String participantId) {
        for (JsonNode participant : state.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the state");
    }

    private static List<String> participantIds(JsonNode state) {
        List<String> ids = new ArrayList<>();
        state.get("participants").forEach(participant -> ids.add(participant.get("id").asText()));
        return ids;
    }

    private static int hpOf(JsonNode state, String participantId) {
        return participant(state, participantId).get("hpCurrent").asInt();
    }
}
