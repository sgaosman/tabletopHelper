package com.tabletophelper.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.identity.internal.JwtTokenProvider;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status codes through the full stack: the access policy and error mapping (D155) and token types
 * (D156). The encounter views and their DM-only controls are in {@code PlayerViewIT} (D157).
 */
class HttpSecurityIT extends IntegrationTest {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private MonsterRepository monsterRepository;

    private String error(ResponseEntity<String> response) {
        return json(response).get("error").asText();
    }

    @Test
    @DisplayName("A request without a token gets 401")
    void noTokenIsRefused() {
        var response = exchange(HttpMethod.GET, "/api/characters", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("An expired token gets 401")
    void expiredTokenIsRefused() {
        var user = register("aria");
        String expired = new JwtTokenProvider(jwtSecret, -60_000).generateAccessToken(user.id(), "aria");

        var response = exchange(HttpMethod.GET, "/api/characters", null, expired);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("A refresh token is refused as a bearer token")
    void refreshTokenIsRefusedAsBearer() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters", null, user.refreshToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("A correctly signed JWT without type: access gets 401, as tokens issued before D156 do")
    void untypedTokenIsRefused() {
        var user = register("aria");
        Date now = new Date();
        String untyped = Jwts.builder()
                .subject(user.id().toString())
                .claim("username", "aria")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        var response = exchange(HttpMethod.GET, "/api/characters", null, untyped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Reading another user's character gets 403")
    void otherUsersCharacterIsRefused() {
        var owner = register("aria");
        var stranger = register("bram");
        UUID characterId = createCharacter(owner.accessToken(), insertRace("Human"), insertClass("Fighter", 10));

        var response = exchange(HttpMethod.GET, "/api/characters/" + characterId, null, stranger.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response)).isEqualTo("You do not have access to this character");
    }

    @Test
    @DisplayName("A non-member reading a campaign gets 403")
    void nonMemberCampaignIsRefused() {
        var dm = register("dungeonmaster");
        var stranger = register("bram");
        String campaignId = createCampaign(dm.accessToken()).get("id").asText();

        var response = exchange(HttpMethod.GET, "/api/campaigns/" + campaignId, null, stranger.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response)).isEqualTo("You are not a member of this campaign");
    }

    @Test
    @DisplayName("A player starting an encounter gets 403")
    void playerStartingEncounterIsRefused() {
        var dm = register("dungeonmaster");
        var player = register("aria");
        var campaign = createCampaign(dm.accessToken());
        join(player.accessToken(), campaign);
        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));

        var response = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null, player.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response)).isEqualTo("Only the DM can perform this action");
    }

    @Test
    @DisplayName("An unknown character gets 404")
    void unknownCharacterIsNotFound() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters/" + UUID.randomUUID(), null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(response)).isEqualTo("Character not found");
    }

    @Test
    @DisplayName("An unknown spell gets 404")
    void unknownSpellIsNotFound() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/reference/spells/" + UUID.randomUUID(), null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Deleting a character in an active encounter gets 409")
    void deletingCharacterInActiveEncounterConflicts() {
        var dm = register("dungeonmaster");
        UUID characterId = createCharacter(dm.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asText());
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Aria"));
        startEncounter(dm.accessToken(), encounterId);

        var response = exchange(HttpMethod.DELETE, "/api/characters/" + characterId, null, dm.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(response)).isEqualTo("Cannot delete a character that is in an active encounter");
    }

    @Test
    @DisplayName("Creating a character without raceId gets 400")
    void missingRaceIdIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Aria", "classId", insertClass("Fighter", 10),
                "strength", 10, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10), user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("raceId: must not be null");
    }

    @Test
    @DisplayName("A non-numeric ?level filter gets 400 from a ValidationException")
    void nonNumericLevelIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/reference/spells?level=abc", null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("Invalid value for level: abc");
    }

    @Test
    @DisplayName("A path variable that is not a UUID gets 400 from the type mismatch handler, not /error")
    void malformedUuidIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters/not-a-uuid", null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("Invalid value for characterId: not-a-uuid");
    }

    @Test
    @DisplayName("An unhandled exception gets 500 with a generic message outside the dev profile")
    void unhandledExceptionIsInternalServerError() {
        var user = register("aria");

        // No ability scores: the NOT NULL columns reject the insert and nothing handles the exception
        var response = exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Aria", "raceId", insertRace("Human"), "classId", insertClass("Fighter", 10)),
                user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(error(response)).isEqualTo(GlobalExceptionHandler.UNEXPECTED_ERROR);
    }

    @Test
    @DisplayName("Spring's own 404 for an unknown path and 405 for a wrong method survive the catch-all")
    void springErrorsKeepTheirStatus() {
        var user = register("aria");

        var unknownPath = exchange(HttpMethod.GET, "/api/no-such-endpoint", null, user.accessToken());
        var wrongMethod = exchange(HttpMethod.DELETE, "/api/campaigns", null, user.accessToken());

        assertThat(unknownPath.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ── Checks added by the access policy (D155) ─────────────────

    @Test
    @DisplayName("Looking up an encounter by session code gets 403 for a user outside the campaign")
    void sessionCodeLookupNeedsMembership() {
        var dm = register("dungeonmaster");
        var player = register("aria");
        var stranger = register("bram");
        var campaign = createCampaign(dm.accessToken());
        join(player.accessToken(), campaign);
        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));
        String sessionCode = startEncounter(dm.accessToken(), encounterId).get("sessionCode").asText();

        var asStranger = exchange(HttpMethod.GET, "/api/encounters/join/" + sessionCode, null, stranger.accessToken());
        var asPlayer = exchange(HttpMethod.GET, "/api/encounters/join/" + sessionCode, null, player.accessToken());

        assertThat(asStranger.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(asStranger)).isEqualTo("You are not a member of this campaign");
        assertThat(json(asPlayer).get("id").asText()).isEqualTo(encounterId.toString());
    }

    @Test
    @DisplayName("Reading an encounter's combat log gets 403 for a user outside the campaign")
    void combatLogNeedsMembership() {
        var dm = register("dungeonmaster");
        var stranger = register("bram");
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));

        var response = exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                stranger.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response)).isEqualTo("You are not a member of this campaign");
    }

    @Test
    @DisplayName("A character cannot be attached to a campaign its owner has not joined, on create or by the campaign command")
    void attachingCharacterNeedsMembership() {
        var dm = register("dungeonmaster");
        var player = register("aria");
        UUID campaignId = campaignId(dm);
        UUID raceId = insertRace("Human");
        UUID classId = insertClass("Fighter", 10);
        UUID characterId = createCharacter(player.accessToken(), raceId, classId);

        Map<String, Object> createBody = new HashMap<>(Map.of(
                "name", "Bram", "raceId", raceId, "classId", classId,
                "strength", 10, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10));
        createBody.put("campaignId", campaignId);
        var onCreate = exchange(HttpMethod.POST, "/api/characters", createBody, player.accessToken());
        var onUpdate = exchange(HttpMethod.PUT, "/api/characters/" + characterId + "/campaign",
                Map.of("campaignId", campaignId), player.accessToken());

        assertThat(onCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(onCreate)).isEqualTo("You are not a member of this campaign");
        assertThat(onUpdate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(onUpdate)).isEqualTo("You are not a member of this campaign");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM player_characters WHERE campaign_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("The DM cannot add a character to an encounter while its owner is outside the campaign")
    void addingCharacterToEncounterNeedsOwnerMembership() {
        var dm = register("dungeonmaster");
        var player = register("aria");
        UUID characterId = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        UUID encounterId = createEncounter(dm.accessToken(), campaignId(dm));

        var response = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/participants",
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Aria"),
                dm.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(response)).isEqualTo("This character's owner is not a member of this campaign");
    }

    @Test
    @DisplayName("Legendary Resistance with a combat log entry from another encounter gets 404")
    void legendaryResistanceNeedsEntryFromSameEncounter() {
        var dm = register("dungeonmaster");
        UUID campaignId = campaignId(dm);
        UUID firstEncounter = createEncounter(dm.accessToken(), campaignId);
        String goblinId = addGoblin(dm.accessToken(), firstEncounter);
        startEncounter(dm.accessToken(), firstEncounter);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + firstEncounter + "/combat/damage",
                Map.of("targetId", goblinId, "amount", 3), dm.accessToken()));
        JsonNode log = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + firstEncounter + "/combat/log", null, dm.accessToken()));
        String otherEncountersEntry = log.get(0).get("id").asText();
        UUID secondEncounter = createEncounter(dm.accessToken(), campaignId);
        startEncounter(dm.accessToken(), secondEncounter);

        var response = exchange(HttpMethod.POST, "/api/encounters/" + secondEncounter + "/combat/legendary-resistance",
                Map.of("combatLogId", otherEncountersEntry), dm.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(response)).isEqualTo("Combat log entry not found");
    }

    @Test
    @DisplayName("A player cannot read another player's character in the same campaign, but the DM can")
    void playerCannotReadAnotherPlayersCharacter() {
        var dm = register("dungeonmaster");
        var owner = register("aria");
        var otherPlayer = register("bram");
        var campaign = createCampaign(dm.accessToken());
        join(owner.accessToken(), campaign);
        join(otherPlayer.accessToken(), campaign);
        UUID characterId = createCharacter(owner.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + characterId + "/campaign",
                Map.of("campaignId", campaign.get("id").asText()), owner.accessToken()));

        var asOtherPlayer = exchange(HttpMethod.GET, "/api/characters/" + characterId, null, otherPlayer.accessToken());
        var asDm = exchange(HttpMethod.GET, "/api/characters/" + characterId, null, dm.accessToken());

        assertThat(asOtherPlayer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error(asOtherPlayer)).isEqualTo("You do not have access to this character");
        assertThat(json(asDm).get("id").asText()).isEqualTo(characterId.toString());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void join(String playerToken, JsonNode campaign) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), playerToken));
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
}
