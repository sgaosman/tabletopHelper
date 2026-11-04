package com.tabletophelper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.content.ContentStartup;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for the integration tier (D172). Every subclass shares one Spring context, one server
 * on a random port and one PostgreSQL container, and the tables are emptied after each test.
 * Requests go through the real port rather than MockMvc, because MockMvc never forwards errors to
 * /error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ScriptedDiceConfiguration.class})
@ActiveProfiles("it")
public abstract class IntegrationTest {

    // Tests insert the reference rows they need; ContentLoadIT runs the full load itself
    @MockitoBean
    protected ContentStartup contentStartup;

    @LocalServerPort
    protected int port;

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected RaceRepository raceRepository;
    @Autowired protected CharacterClassRepository characterClassRepository;
    // Combat's dice: random unless a test scripts them
    @Autowired protected ScriptedDiceConfiguration.ScriptedDice dice;
    @Autowired private CacheManager cacheManager;

    @AfterEach
    void emptyTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        jdbcTemplate.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        // Reference endpoints are @Cacheable, so drop entries that point at deleted rows
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        dice.clear();
    }

    // ── Users and reference rows ─────────────────────────────────

    /**
     * Registers through the real endpoint, so tests hold a genuine access token and the refresh token
     * from the cookie (D156).
     */
    protected RegisteredUser register(String username) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/register", Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "password123"), null);
        JsonNode body = expect(HttpStatus.CREATED, response);
        return new RegisteredUser(UUID.fromString(body.get("userId").asText()),
                body.get("accessToken").asText(), refreshCookieValue(response));
    }

    /** The value of the refresh_token cookie a response sets, or null when it sets none. */
    protected static String refreshCookieValue(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .map(header -> header.split(";", 2)[0])
                .filter(pair -> pair.startsWith("refresh_token="))
                .map(pair -> pair.substring("refresh_token=".length()))
                .findFirst()
                .orElse(null);
    }

    protected UUID insertRace(String name) {
        return raceRepository.save(Race.builder()
                .name(name).source("PHB").speed("{\"walk\":30}")
                .build()).getId();
    }

    protected UUID insertClass(String name, int hitDice) {
        return characterClassRepository.save(CharacterClass.builder()
                .name(name).source("PHB").hitDice(hitDice)
                .build()).getId();
    }

    /**
     * A class with Spellcasting, or a warlock's Pact Magic, casting with the ability given, so the server works out
     * its slots and spell numbers (D167).
     */
    protected UUID insertCaster(String name, int hitDice, String ability, boolean pactMagic) {
        return characterClassRepository.save(CharacterClass.builder()
                .name(name).source("PHB").hitDice(hitDice).subclassLevel(pactMagic ? 1 : 2)
                .isSpellcaster(true).isPactMagic(pactMagic).spellcastingAbility(ability)
                .build()).getId();
    }

    // ── API calls ────────────────────────────────────────────────

    /** Sends a JSON request, with a bearer token when one is given. Never throws on error statuses. */
    protected ResponseEntity<String> exchange(HttpMethod method, String path, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response body is not JSON: " + response.getBody(), e);
        }
    }

    protected JsonNode expect(HttpStatus status, ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("response body: %s", response.getBody()).isEqualTo(status);
        return json(response);
    }

    /** Creates a campaign owned by the caller; the response carries its id and invite code. */
    protected JsonNode createCampaign(String dmToken) {
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/campaigns",
                Map.of("name", "Lost Mine"), dmToken));
    }

    protected UUID createEncounter(String dmToken, UUID campaignId) {
        JsonNode encounter = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/encounters",
                Map.of("campaignId", campaignId, "name", "Goblin Ambush"), dmToken));
        return UUID.fromString(encounter.get("id").asText());
    }

    protected UUID createCharacter(String token, UUID raceId, UUID classId) {
        JsonNode character = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Aria", "raceId", raceId, "classId", classId,
                "strength", 10, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10), token));
        return UUID.fromString(character.get("id").asText());
    }

    protected JsonNode addParticipant(String dmToken, UUID encounterId, Map<String, Object> request) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/participants", request, dmToken));
    }

    /** Rolls initiative for every participant, then starts the encounter. */
    protected JsonNode startEncounter(String dmToken, UUID encounterId) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/initiatives/roll", null, dmToken));
        return expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/start", null, dmToken));
    }

    protected record RegisteredUser(UUID id, String accessToken, String refreshToken) {}
}
