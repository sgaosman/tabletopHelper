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
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commands on one encounter queue behind its row lock instead of failing (D160). A table of a DM and a
 * 59 HP ogre in an Active encounter. The test holds the encounter's row lock itself, as a slow command
 * would, so requests are sure to meet at the lock.
 */
class ConcurrentCombatIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private DataSource dataSource;

    private final ExecutorService requests = Executors.newFixedThreadPool(2);

    private RegisteredUser dm;
    private UUID encounterId;
    private String ogreId;

    @BeforeEach
    void setUpEncounter() {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asText());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        Monster ogre = monsterRepository.save(Monster.builder()
                .name("Ogre").type("Giant").challengeRating("2").source("MM")
                .hitPoints(59).armourClass(11).dexterity(8)
                .build());
        JsonNode state = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre"));
        ogreId = state.get("participants").get(0).get("id").asText();
        startEncounter(dm.accessToken(), encounterId);
    }

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    @Test
    @DisplayName("Two damage calls sent at once to the same encounter both apply, and the final HP reflects both")
    void simultaneousDamageBothApply() throws Exception {
        List<CompletableFuture<ResponseEntity<String>>> calls;
        Connection lock = lockEncounterRow();
        try {
            calls = List.of(damageAsync(5), damageAsync(7));
            awaitRequestsWaitingForLock(2);
        } finally {
            release(lock);
        }

        List<Integer> answeredHp = new ArrayList<>();
        for (CompletableFuture<ResponseEntity<String>> call : calls) {
            answeredHp.add(ogre(expect(HttpStatus.OK, call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).get("hpCurrent").asInt());
        }

        assertThat(ogreHp()).isEqualTo(59 - 5 - 7);
        // The second command started from the first one's committed state, so no damage was lost
        assertThat(answeredHp).contains(47).containsAnyOf(54, 52);
        assertThat(log()).contains("DM deals 5 damage to Ogre", "DM deals 7 damage to Ogre");
    }

    @Test
    @DisplayName("A command that waits more than five seconds for the encounter gets 409 and changes nothing")
    void commandWaitingTooLongIsRefused() throws Exception {
        ResponseEntity<String> response;
        long waitedMillis;
        Connection lock = lockEncounterRow();
        try {
            long start = System.nanoTime();
            response = damageAsync(5).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            release(lock);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("error").asText())
                .isEqualTo("Another change is still in progress. Please try again in a moment.");
        assertThat(waitedMillis).isBetween(4_500L, 15_000L);
        assertThat(ogreHp()).isEqualTo(59);
        assertThat(log()).doesNotContain("DM deals 5 damage to Ogre");
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Opens a transaction holding the encounter's row lock until {@link #release}. */
    private Connection lockEncounterRow() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM encounters WHERE id = ? FOR UPDATE")) {
            select.setObject(1, encounterId);
            select.executeQuery().close();
        } catch (SQLException e) {
            release(connection);
            throw e;
        }
        return connection;
    }

    private static void release(Connection lock) throws SQLException {
        try (lock) {
            lock.rollback();
        }
    }

    // Requests must queue well inside the five-second lock timeout, so this gives up after four
    private void awaitRequestsWaitingForLock(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(count + " requests did not reach the encounter's lock within 4s");
    }

    private CompletableFuture<ResponseEntity<String>> damageAsync(int amount) {
        return CompletableFuture.supplyAsync(() -> exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ogreId, "amount", amount), dm.accessToken()), requests);
    }

    private JsonNode ogre(JsonNode encounter) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asText().equals(ogreId)) {
                return participant;
            }
        }
        throw new AssertionError("The ogre is not in the encounter");
    }

    private int ogreHp() {
        return ogre(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()))).get("hpCurrent").asInt();
    }

    private List<String> log() {
        List<String> descriptions = new ArrayList<>();
        expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken())).forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }
}
