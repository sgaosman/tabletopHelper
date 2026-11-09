package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustion end to end (PHB p.291, D183): the level on a player's sheet joins the encounter, the DM sets any
 * creature's level while preparing or under way, its effects reach speed, the hit point maximum, attack rolls and death
 * saves, undo restores it, Write-back takes the encounter's change to the sheet, and a long rest lowers it. Aria, a
 * player's level 1 fighter with 11 HP and speed 30, against a 59 HP ogre and a skeleton immune to exhaustion.
 */
class ExhaustionIT extends IntegrationTest {

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private UUID characterId;
    private String ariaId;
    private String ogreId;
    private String skeletonId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        characterId = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + characterId + "/campaign",
                Map.of("campaignId", campaignId), player.accessToken()));
        encounterId = createEncounter(dm.accessToken(), campaignId);
    }

    private void addEveryone() {
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("Giant").challengeRating("2")
                .source("MM").hitPoints(59).armourClass(11).dexterity(8).speed("{\"walk\": 40}").build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        Monster skeleton = monsterRepository.save(Monster.builder().name("Skeleton").type("Undead")
                .challengeRating("1/4").source("MM").hitPoints(13).armourClass(13).dexterity(14)
                .conditionImmunities("[\"exhaustion\", \"poisoned\"]").build());
        skeletonId = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", skeleton.getId(), "displayName", "Skeleton")), "Skeleton");
    }

    private void start() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 10),
                        Map.of("participantId", skeletonId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The sheet's level joins, the DM's change while preparing survives the start, Write-back takes it to "
            + "the sheet, and a long rest lowers it by one")
    void sheetToEncounterAndBack() {
        JsonNode sheet = expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + characterId
                + "/exhaustion", Map.of("level", 1), player.accessToken()));
        assertThat(sheet.get("exhaustionLevel").asInt()).isEqualTo(1);
        addEveryone();
        assertThat(participant(dmState(), ariaId).get("exhaustionLevel").asInt()).isEqualTo(1);

        // Only the DM sets a participant's level, and may while the encounter is prepared
        expect(HttpStatus.FORBIDDEN, exhaustion(ariaId, 3, player.accessToken()));
        expect(HttpStatus.OK, exhaustion(ariaId, 2, dm.accessToken()));
        start();

        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("exhaustionLevel").asInt()).isEqualTo(2);
        assertThat(aria.get("speed").get("walk").asInt()).isEqualTo(15);
        assertThat(descriptions(dmLog())).contains("Aria gains exhaustion: level 2 (was 1): disadvantage on ability "
                + "checks, speed halved");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(character().get("exhaustionLevel").asInt()).isEqualTo(2);

        JsonNode rested = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId
                + "/long-rest", null, player.accessToken()));
        assertThat(rested.get("exhaustionLevel").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Under way: level 3 gives the ogre's attacks disadvantage, level 4 halves its maximum and its hit "
            + "points, undo puts both back, and the players see its level")
    void underWay() {
        addEveryone();
        start();

        expect(HttpStatus.OK, exhaustion(ogreId, 3, dm.accessToken()));
        JsonNode preview = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-roll-preview?attackerId=" + ogreId + "&targetId=" + ariaId + "&withinFiveFeet=true",
                null, dm.accessToken()));
        assertThat(preview.get("rollMode").asString()).isEqualTo("DISADVANTAGE");
        assertThat(preview.get("sources").get(0).get("reason").asString()).isEqualTo("attacker exhausted (3)");
        assertThat(participant(dmState(), ogreId).get("speed").get("walk").asInt()).isEqualTo(20);

        expect(HttpStatus.OK, exhaustion(ogreId, 4, dm.accessToken()));
        JsonNode ogre = participant(dmState(), ogreId);
        assertThat(ogre.get("hpMax").asInt()).isEqualTo(29);
        assertThat(ogre.get("hpMaxBase").asInt()).isEqualTo(59);
        assertThat(ogre.get("hpCurrent").asInt()).isEqualTo(29);
        JsonNode playerView = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken()));
        assertThat(participant(playerView, ogreId).get("exhaustionLevel").asInt()).isEqualTo(4);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", dmState().get("undo").get("commandId").asString()), dm.accessToken()));
        ogre = participant(dmState(), ogreId);
        assertThat(ogre.get("exhaustionLevel").asInt()).isEqualTo(3);
        assertThat(ogre.get("hpMax").asInt()).isEqualTo(59);
        assertThat(ogre.get("hpCurrent").asInt()).isEqualTo(59);
    }

    @Test
    @DisplayName("A creature immune to exhaustion gains none, the plain condition is refused, and level 6 kills a "
            + "player character outright, who can't be revived until the level comes down")
    void immunityAndDeath() {
        addEveryone();
        start();

        assertThat(json(expectStatus(HttpStatus.CONFLICT, exhaustion(skeletonId, 1, dm.accessToken())))
                .get("error").asString()).isEqualTo("Skeleton is immune to exhaustion");
        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/condition/add", Map.of("targetId", ogreId, "condition", "exhaustion"), dm.accessToken()));

        expect(HttpStatus.OK, exhaustion(ariaId, 6, dm.accessToken()));
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(aria.get("hpCurrent").asInt()).isZero();
        assertThat(descriptions(dmLog())).contains("Aria dies of exhaustion");
        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/hp",
                Map.of("targetId", ariaId, "hpCurrent", 5), dm.accessToken()));

        expect(HttpStatus.OK, exhaustion(ariaId, 5, dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/hp",
                Map.of("targetId", ariaId, "hpCurrent", 5), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("lifeState").asString()).isEqualTo("ALIVE");
    }

    @Test
    @DisplayName("At level 3 a death saving throw has disadvantage: two d20s, the lower counting (PHB p.197, p.291)")
    void deathSaveDisadvantage() {
        addEveryone();
        start();
        expect(HttpStatus.OK, exhaustion(ariaId, 3, dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ariaId, "amount", 11), dm.accessToken()));

        dice.script(15, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/death-save",
                Map.of("participantId", ariaId), dm.accessToken()));

        assertThat(descriptions(dmLog())).contains("Aria fails a death save (roll: 4): 1/3 failures (2d20(15, 4) "
                + "with disadvantage, exhausted (3))");
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> exhaustion(String participantId, int level, String token) {
        return exchange(HttpMethod.PUT, "/api/encounters/" + encounterId + "/combat/exhaustion",
                Map.of("participantId", participantId, "level", level), token);
    }

    private static ResponseEntity<String> expectStatus(HttpStatus status, ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        return response;
    }

    private JsonNode character() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asString().equals(displayName)) {
                return participant.get("id").asString();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asString().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
