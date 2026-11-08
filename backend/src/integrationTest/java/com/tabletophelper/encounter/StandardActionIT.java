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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standard actions, object interactions and the DM's economy edit, end to end (D174, PHB p.190–193). A player
 * controls Vex, a fighter, and Wren, a second fighter; they go first and second, then a goblin with Nimble Escape and
 * plenty of hit points.
 */
class StandardActionIT extends IntegrationTest {

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private String inviteCode;
    private UUID encounterId;
    private String vexId;
    private String wrenId;
    private String goblinId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("vex");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        inviteCode = campaign.get("inviteCode").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join", Map.of("inviteCode", inviteCode),
                player.accessToken()));
        UUID race = insertRace("Human");
        UUID fighter = insertClass("Fighter", 10);
        UUID vex = character("Vex", race, fighter);
        UUID wren = character("Wren", race, fighter);
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("Humanoid")
                .challengeRating("1/4").source("MM").hitPoints(200).armourClass(15).dexterity(14)
                .skills("{\"stealth\": \"+6\"}")
                .traits("[{\"name\": \"Nimble Escape\", \"description\": \"The goblin can take the Disengage or Hide "
                        + "action as a bonus action on each of its turns.\"}]")
                .build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        vexId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", vex, "displayName", "Vex")), "Vex");
        wrenId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", wren, "displayName", "Wren")), "Wren");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", vexId, "initiative", 20),
                        Map.of("participantId", wrenId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Vex's Dodge gives the goblin's attack disadvantage on its turn and ends at the start of Vex's next "
            + "turn")
    void dodgeAcrossTurns() {
        JsonNode dodged = expect(HttpStatus.OK, standardAction(vexId, "DODGE", null, player.accessToken()));

        JsonNode dodger = participant(dodged, vexId);
        assertThat(dodger.get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        JsonNode entry = dodger.get("activeConditions").get(0);
        assertThat(entry.get("name").asString()).isEqualTo("dodging");
        assertThat(entry.get("endRule").get("type").asString()).isEqualTo("START_OF_TURN");
        assertThat(entry.get("endRule").get("participantId").asString()).isEqualTo(vexId);

        nextTurn();
        assertThat(conditionNames(participant(dmView(), vexId))).containsExactly("dodging");
        nextTurn();

        // 18 and 1 with disadvantage: the 1 counts, a miss
        dice.script(18, 1);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                        + "/combat/attack?actorId=" + goblinId,
                new HashMap<>(Map.of("targetId", vexId, "attackBonus", 4, "damageDice", "1d6")), dm.accessToken()));
        assertThat(descriptions(logEntries("ATTACK")).getLast())
                .startsWith("Goblin attacks Vex: 2d20(18, 1) with disadvantage → 1 + 4 = 5");

        nextTurn();
        JsonNode round2 = dmView();
        assertThat(round2.get("roundNumber").asInt()).isEqualTo(2);
        assertThat(conditionNames(participant(round2, vexId))).isEmpty();
        assertThat(descriptions(logEntries("CONDITION_REMOVE")))
                .containsExactly("dodging ends on Vex (start of Vex's turn)");
        assertThat(descriptions(logEntries("STANDARD_ACTION"))).containsExactly("Vex takes the Dodge action (action) until "
                + "the start of its next turn: attacks against it have disadvantage and it has advantage on Dexterity "
                + "saves.");
    }

    @Test
    @DisplayName("A player acts for their own participant on its turn; the DM for anyone, with Nimble Escape's bonus "
            + "action")
    void access() {
        RegisteredUser other = register("other");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join", Map.of("inviteCode", inviteCode),
                other.accessToken()));

        assertThat(standardAction(vexId, "DASH", null, other.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> offTurn = standardAction(wrenId, "DASH", null, player.accessToken());
        assertThat(offTurn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(offTurn).get("error").asString()).isEqualTo("You can only take that action on your turn");
        ResponseEntity<String> offTurnInteraction = interact(wrenId, "draws a dagger", player.accessToken());
        assertThat(offTurnInteraction.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(offTurnInteraction).get("error").asString())
                .isEqualTo("You can only take that action on your turn");
        assertThat(standardAction(goblinId, "DASH", null, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> bonusDash = standardAction(vexId, "DASH", "BONUS_ACTION", player.accessToken());
        assertThat(bonusDash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(bonusDash).get("error").asString()).isEqualTo("Dash as a bonus action needs Cunning Action");

        Map<String, Object> help = new LinkedHashMap<>(Map.of("participantId", vexId, "action", "HELP"));
        assertThat(exchange(HttpMethod.POST, path("standard-action"), help, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        help.put("targetId", wrenId);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, path("standard-action"), help, player.accessToken()));

        nextTurn();
        nextTurn();
        dice.script(9);
        JsonNode hidden = expect(HttpStatus.OK, standardAction(goblinId, "HIDE", "BONUS_ACTION", dm.accessToken()));
        assertThat(participant(hidden, goblinId).get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();
        JsonNode hide = logEntries("STANDARD_ACTION").getLast();
        assertThat(hide.get("description").asString()).isEqualTo("Goblin takes the Hide action (bonus action, "
                + "Nimble Escape): Dexterity (Stealth) d20(9) + 6 = 15; the DM decides whether it is hidden.");
        assertThat(hide.get("rollTotal").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("The first object interaction is free, a player's second is a 409, and the DM's is logged as over")
    void objectInteraction() {
        expect(HttpStatus.OK, interact(vexId, "draws a longsword", player.accessToken()));
        ResponseEntity<String> second = interact(vexId, "opens the door", player.accessToken());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(second).get("error").asString()).isEqualTo("It has already had its free object interaction "
                + "this turn; another needs the Use an Object action");

        JsonNode after = expect(HttpStatus.OK, interact(vexId, "opens the door", dm.accessToken()));
        assertThat(participant(after, vexId).get("turnEconomy").get("objectInteractionUsed").asBoolean()).isTrue();
        assertThat(descriptions(logEntries("OBJECT_INTERACTION"))).containsExactly(
                "Vex interacts with an object: draws a longsword",
                "Vex's object interaction is over the action economy: it has already had its free object interaction "
                        + "this turn; another needs the Use an Object action",
                "Vex interacts with an object: opens the door");
    }

    @Test
    @DisplayName("Only the DM sets a turn economy, such as Haste's second action, and it is logged")
    void turnEconomyEdit() {
        Map<String, Object> economy = new LinkedHashMap<>();
        economy.put("actions", 2);
        economy.put("actionsUsed", 0);
        economy.put("attackActionTaken", false);
        economy.put("attacksLeft", 0);
        economy.put("bonusActionUsed", false);
        economy.put("reactionUsed", false);
        economy.put("objectInteractionUsed", false);
        economy.put("bonusActionSpellCast", false);
        economy.put("otherSpellCast", false);
        economy.put("dashed", false);
        economy.put("disengaged", false);
        Map<String, Object> body = Map.of("participantId", vexId, "turnEconomy", economy);

        assertThat(exchange(HttpMethod.PUT, path("turn-economy"), body, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode set = expect(HttpStatus.OK, exchange(HttpMethod.PUT, path("turn-economy"), body, dm.accessToken()));
        assertThat(participant(set, vexId).get("turnEconomy").get("actions").asInt()).isEqualTo(2);

        expect(HttpStatus.OK, standardAction(vexId, "DASH", null, player.accessToken()));
        expect(HttpStatus.OK, standardAction(vexId, "DISENGAGE", null, player.accessToken()));
        assertThat(standardAction(vexId, "SEARCH", null, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(descriptions(logEntries("TURN_ECONOMY_SET")))
                .containsExactly("The DM sets Vex's turn economy: actions 1 → 2");

        // Players see a monster's budget edit happen, but not what changed, as they don't see its budget (D157)
        economy.put("actions", 1);
        economy.put("reactionUsed", true);
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, path("turn-economy"),
                Map.of("participantId", goblinId, "turnEconomy", economy), dm.accessToken()));
        assertThat(descriptions(logEntries("TURN_ECONOMY_SET"))).containsExactly(
                "The DM sets Vex's turn economy: actions 1 → 2",
                "The DM sets Goblin's turn economy: reaction used no → yes");
        assertThat(descriptions(logEntries("TURN_ECONOMY_SET", player.accessToken()))).containsExactly(
                "The DM sets Vex's turn economy: actions 1 → 2",
                "The DM adjusts Goblin's turn.");

        economy.put("actions", -1);
        assertThat(exchange(HttpMethod.PUT, path("turn-economy"), body, dm.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private UUID character(String name, UUID race, UUID classId) {
        return UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", name, "raceId", race, "classId", classId, "strength", 16, "dexterity", 12,
                "constitution", 14, "intelligence", 8, "wisdom", 10, "charisma", 10), player.accessToken()))
                .get("id").asString());
    }

    private String path(String command) {
        return "/api/encounters/" + encounterId + "/combat/" + command;
    }

    private ResponseEntity<String> standardAction(String participantId, String action, String cost, String token) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("participantId", participantId, "action", action));
        if (cost != null) {
            body.put("cost", cost);
        }
        return exchange(HttpMethod.POST, path("standard-action"), body, token);
    }

    private ResponseEntity<String> interact(String participantId, String description, String token) {
        return exchange(HttpMethod.POST, path("object-interaction"),
                Map.of("participantId", participantId, "description", description), token);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, path("turn/next"), null, dm.accessToken()));
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private List<JsonNode> logEntries(String actionType) {
        return logEntries(actionType, dm.accessToken());
    }

    // The log as the token's user reads it: whole for the DM, the Player View's for a player (D157)
    private List<JsonNode> logEntries(String actionType, String token) {
        JsonNode log = expect(HttpStatus.OK, exchange(HttpMethod.GET, path("log"), null, token));
        List<JsonNode> entries = new ArrayList<>();
        log.forEach(entry -> {
            if (entry.get("actionType").asString().equals(actionType)) {
                entries.add(entry);
            }
        });
        return entries;
    }

    private static List<String> descriptions(List<JsonNode> entries) {
        return entries.stream().map(entry -> entry.get("description").asString()).toList();
    }

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant.get("activeConditions");
        if (conditions != null) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
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
}
