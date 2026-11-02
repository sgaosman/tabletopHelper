package com.tabletophelper.config;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.auth.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status codes through the full stack, as the code returns them today. R3 changes the cases marked
 * {@code R3 (D15x)}, and R3 isn't done while any marker remains.
 */
class HttpSecurityIT extends IntegrationTest {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private String error(ResponseEntity<String> response) {
        return json(response).get("error").asText();
    }

    @Test
    @DisplayName("A request without a token gets 403")
    void noTokenIsRefused() {
        var response = exchange(HttpMethod.GET, "/api/characters", null, null);

        // R3 (D155): expect 401
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("An expired token gets 403")
    void expiredTokenIsRefused() {
        var user = register("aria");
        String expired = new JwtTokenProvider(jwtSecret, -60_000, -60_000).generateAccessToken(user.id(), "aria");

        var response = exchange(HttpMethod.GET, "/api/characters", null, expired);

        // R3 (D155): expect 401
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A refresh token is accepted as a bearer token")
    void refreshTokenWorksAsBearer() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters", null, user.refreshToken());

        // R3 (D156): expect 401
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Reading another user's character gets 400")
    void otherUsersCharacterIsRefused() {
        var owner = register("aria");
        var stranger = register("bram");
        UUID characterId = createCharacter(owner.accessToken(), insertRace("Human"), insertClass("Fighter", 10));

        var response = exchange(HttpMethod.GET, "/api/characters/" + characterId, null, stranger.accessToken());

        // R3 (D155): expect 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("You do not have access to this character");
    }

    @Test
    @DisplayName("A non-member reading a campaign gets 400")
    void nonMemberCampaignIsRefused() {
        var dm = register("dungeonmaster");
        var stranger = register("bram");
        String campaignId = createCampaign(dm.accessToken()).get("id").asText();

        var response = exchange(HttpMethod.GET, "/api/campaigns/" + campaignId, null, stranger.accessToken());

        // R3 (D155): expect 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("You are not a member of this campaign");
    }

    @Test
    @DisplayName("A player starting an encounter gets 400")
    void playerStartingEncounterIsRefused() {
        var dm = register("dungeonmaster");
        var player = register("aria");
        var campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));

        var response = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null, player.accessToken());

        // R3 (D155): expect 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("Only the DM can perform this action");
    }

    @Test
    @DisplayName("An unknown character gets 400")
    void unknownCharacterIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters/" + UUID.randomUUID(), null, user.accessToken());

        // R3 (D155): expect 404
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
    @DisplayName("A non-numeric ?level filter gets 400, because NumberFormatException is an IllegalArgumentException")
    void nonNumericLevelIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/reference/spells?level=abc", null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("For input string: \"abc\"");
    }

    @Test
    @DisplayName("A path variable that is not a UUID gets 400 from the IllegalArgumentException handler, not /error")
    void malformedUuidIsBadRequest() {
        var user = register("aria");

        var response = exchange(HttpMethod.GET, "/api/characters/not-a-uuid", null, user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(response)).isEqualTo("Invalid UUID string: not-a-uuid");
    }

    @Test
    @DisplayName("An unhandled exception is forwarded to /error, which security answers with 403")
    void unhandledExceptionIsForbiddenAtErrorPage() {
        var user = register("aria");

        // No ability scores: the NOT NULL columns reject the insert and nothing handles the exception
        var response = exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Aria", "raceId", insertRace("Human"), "classId", insertClass("Fighter", 10)),
                user.accessToken());

        // R3 (D155): expect 500
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
