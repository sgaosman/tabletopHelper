package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn order end to end (D163): the computed order with the Lair's turn on initiative 20, dead monsters
 * skipped, the Lair leaving with its monster, when legendary and lair actions may be used, the DM's order for
 * a tie, and a participant leaving on its own turn. A table of a DM and one player, whose character Aria fights
 * an ogre, a goblin and a dragon with lair actions, on initiatives 22, 15, 12 and 10.
 */
class TurnOrderIT extends IntegrationTest {

    // The dragon's actions deal flat damage, so they roll no dice
    private static final String DRAGON_ACTIONS = """
            {"actions": [{"name": "Claw", "deliveryMethod": "AUTO_HIT",
                          "effects": [{"effectType": "DAMAGE", "flatDamage": 1, "damageType": "slashing"}]}],
             "legendaryActions": [{"name": "Tail Attack", "cost": 1, "deliveryMethod": "AUTO_HIT",
                          "effects": [{"effectType": "DAMAGE", "flatDamage": 1, "damageType": "bludgeoning"}]}],
             "lairActions": [{"name": "Magma Eruption", "deliveryMethod": "AUTO_HIT",
                          "effects": [{"effectType": "DAMAGE", "flatDamage": 1, "damageType": "fire"}]}],
             "hasLairActions": true}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;
    private String goblinId;
    private String dragonId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        encounterId = createEncounter(dm.accessToken(), campaignId);

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        ogreId = addMonster(encounterId, "Ogre", 59, 8, null);
        goblinId = addMonster(encounterId, "Goblin", 7, 14, null);
        dragonId = addMonster(encounterId, "Dragon", 200, 10, DRAGON_ACTIONS);
        EncounterParticipant dragon = participantRepository.findById(UUID.fromString(dragonId)).orElseThrow();
        dragon.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry("monster:legendary-actions",
                "Legendary Actions", "MONSTER", "Dragon", 3, "3", 3, "turn", null, null, "FREE", "zap", null))));
        participantRepository.save(dragon);

        setInitiatives(Map.of(ogreId, 22, ariaId, 15, goblinId, 12, dragonId, 10));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The Lair acts on 20 while its dragon lives, dead monsters are skipped, and the Lair leaves with the dragon")
    void lairTurn() {
        JsonNode state = expect(HttpStatus.OK, lairStatus(List.of(dragonId)));
        assertThat(turnNames(state)).containsExactly("Ogre", "Lair Actions", "Aria", "Goblin", "Dragon");
        assertThat(state.get("monstersInLair")).hasSize(1);
        assertThat(participant(state, dragonId).get("hasLairActions").asBoolean()).isTrue();
        assertThat(participant(state, ogreId).get("hasLairActions").asBoolean()).isFalse();
        damage(goblinId, 7);

        state = nextTurn();
        assertThat(state.get("currentTurnIndex").asInt()).isEqualTo(1);
        assertThat(state.get("turnOrder").get(1).get("lair").asBoolean()).isTrue();
        assertThat(state.get("turnOrder").get(1).get("current").asBoolean()).isTrue();
        // On the Lair's turn the dragon takes its lair action, and may use a legendary action
        expect(HttpStatus.OK, dragonAction("Magma Eruption", "LAIR"));
        expect(HttpStatus.OK, dragonAction("Tail Attack", "LEGENDARY"));

        state = nextTurn();
        assertThat(currentTurnName(state)).isEqualTo("Aria");
        ResponseEntity<String> lairOutOfTurn = dragonAction("Magma Eruption", "LAIR");
        assertThat(lairOutOfTurn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(lairOutOfTurn).get("error").asText())
                .isEqualTo("Lair actions happen only on the Lair's turn, at initiative 20");

        // The dead goblin's turn is passed over
        state = nextTurn();
        assertThat(currentTurnName(state)).isEqualTo("Dragon");
        assertThat(state.get("turnOrder").get(3).get("skipped").asBoolean()).isTrue();
        ResponseEntity<String> ownTurn = dragonAction("Tail Attack", "LEGENDARY");
        assertThat(ownTurn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(ownTurn).get("error").asText())
                .isEqualTo("Dragon can't use legendary actions on its own turn, only on other creatures' turns");

        // Once the dragon dies, the Lair takes no more turns
        damage(dragonId, 200);
        state = nextTurn();
        assertThat(currentTurnName(state)).isEqualTo("Ogre");
        assertThat(state.get("roundNumber").asInt()).isEqualTo(2);
        assertThat(turnNames(state)).containsExactly("Ogre", "Aria", "Goblin", "Dragon");
        assertThat(currentTurnName(nextTurn())).isEqualTo("Aria");

        assertThat(dmLogDescriptions()).contains("Turn passes to Lair Actions (Round 1)", "Turn passes to Aria (Round 1)",
                "Turn passes to Dragon (Round 1)", "Turn passes to Ogre (Round 2)");
    }

    @Test
    @DisplayName("Only a monster fighting in its lair takes lair actions, and only the DM's monsters fight in one")
    void lairStatusRules() {
        // The ogre has the same lair actions, but isn't fighting in its lair
        jdbcTemplate.update("UPDATE monsters SET action_templates = ?::jsonb WHERE name = 'Ogre'", DRAGON_ACTIONS);
        expect(HttpStatus.OK, lairStatus(List.of(dragonId)));
        nextTurn();

        ResponseEntity<String> notInLair = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                        + "/combat/monster-action", Map.of("monsterParticipantId", ogreId, "actionName", "Magma Eruption",
                        "actionSource", "LAIR", "targetParticipantIds", List.of(ariaId)), dm.accessToken());
        ResponseEntity<String> playerInLair = lairStatus(List.of(ariaId));
        ResponseEntity<String> byPlayer = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/lair-status",
                Map.of("monsterIds", List.of(dragonId)), player.accessToken());

        assertThat(notInLair.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(notInLair).get("error").asText()).isEqualTo("Ogre isn't fighting in its lair");
        assertThat(playerInLair.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(playerInLair).get("error").asText()).isEqualTo("Only monsters fight in their lair");
        assertThat(byPlayer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A player acts only on their character's turn, as the current turn names it")
    void playerActsOnItsTurn() {
        ResponseEntity<String> early = attack(goblinId);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(early).get("error").asText()).isEqualTo("You can only attack on your turn");

        nextTurn();
        dice.script(15);
        expect(HttpStatus.OK, attack(goblinId));
    }

    @Test
    @DisplayName("The DM reorders a tie on initiative and DEX; any other reorder is a 400")
    void tieOrder() {
        setInitiatives(Map.of(goblinId, 15));
        JsonNode state = getEncounter(dm.accessToken());
        assertThat(turnNames(state)).containsExactly("Ogre", "Aria", "Goblin", "Dragon");
        assertThat(state.get("turnOrder").get(1).get("tieGroup").asInt())
                .isEqualTo(state.get("turnOrder").get(2).get("tieGroup").asInt());
        assertThat(state.get("turnOrder").get(0).get("tieGroup").isNull()).isTrue();

        state = expect(HttpStatus.OK, tieOrder(List.of(goblinId, ariaId)));
        assertThat(turnNames(state)).containsExactly("Ogre", "Goblin", "Aria", "Dragon");
        assertThat(participant(state, goblinId).get("sortOrder").asInt()).isEqualTo(1);

        ResponseEntity<String> untied = tieOrder(List.of(dragonId, ogreId));
        assertThat(untied.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(untied).get("error").asText())
                .isEqualTo("Only participants tied on both initiative and DEX can be reordered");
        assertThat(tieOrder(List.of(goblinId, ariaId), player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A player's view has no tie groups
        assertThat(getEncounter(player.accessToken()).get("turnOrder").get(1).get("tieGroup").isNull()).isTrue();
    }

    @Test
    @DisplayName("Removing the participant whose turn it is passes the turn on, and deleting an encounter still works")
    void removingAndDeleting() {
        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.DELETE,
                "/api/encounters/" + encounterId + "/participants/" + ogreId, null, dm.accessToken()));

        assertThat(turnNames(state)).containsExactly("Aria", "Goblin", "Dragon");
        assertThat(currentTurnName(state)).isEqualTo("Aria");
        assertThat(jdbcTemplate.queryForObject("SELECT current_turn_participant_id FROM encounters WHERE id = ?",
                UUID.class, encounterId)).isEqualTo(UUID.fromString(ariaId));
        assertThat(dmLogDescriptions()).contains("Turn passes to Aria (Round 1)");

        // An encounter whose marker names one of its participants deletes with them
        UUID preparing = createEncounter(dm.accessToken(), campaignId);
        String kobold = addMonster(preparing, "Kobold", 5, 15, null);
        jdbcTemplate.update("UPDATE encounters SET current_turn_participant_id = ? WHERE id = ?",
                UUID.fromString(kobold), preparing);
        ResponseEntity<String> deleted = exchange(HttpMethod.DELETE, "/api/encounters/" + preparing, null,
                dm.accessToken());
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM encounters WHERE id = ?", Integer.class, preparing))
                .isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String addMonster(UUID encounter, String name, int hitPoints, int dexterity, String actionTemplates) {
        Monster monster = monsterRepository.save(Monster.builder()
                .name(name).type("Monstrosity").challengeRating("1").source("MM")
                .hitPoints(hitPoints).armourClass(12).dexterity(dexterity).actionTemplates(MonsterTemplates.parse(actionTemplates))
                .build());
        return idOf(addParticipant(dm.accessToken(), encounter,
                Map.of("participantType", "MONSTER", "monsterId", monster.getId(), "displayName", name)), name);
    }

    private void setInitiatives(Map<String, Integer> initiatives) {
        List<Map<String, Object>> entries = new ArrayList<>();
        initiatives.forEach((id, initiative) -> entries.add(Map.of("participantId", id, "initiative", initiative)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", entries), dm.accessToken()));
    }

    private ResponseEntity<String> lairStatus(List<String> monsterIds) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/lair-status",
                Map.of("monsterIds", monsterIds), dm.accessToken());
    }

    private ResponseEntity<String> tieOrder(List<String> participantIds) {
        return tieOrder(participantIds, dm.accessToken());
    }

    private ResponseEntity<String> tieOrder(List<String> participantIds, String token) {
        return exchange(HttpMethod.PUT, "/api/encounters/" + encounterId + "/initiatives/tie-order",
                Map.of("participantIds", participantIds), token);
    }

    private ResponseEntity<String> dragonAction(String action, String source) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", dragonId, "actionName", action, "actionSource", source,
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken());
    }

    private ResponseEntity<String> attack(String targetId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + ariaId,
                Map.of("targetId", targetId, "attackBonus", 0, "damageDice", "1"), player.accessToken());
    }

    private void damage(String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    private JsonNode nextTurn() {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                null, dm.accessToken()));
    }

    private JsonNode getEncounter(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null, token));
    }

    private List<String> dmLogDescriptions() {
        List<String> descriptions = new ArrayList<>();
        expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken())).forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }

    private static List<String> turnNames(JsonNode state) {
        List<String> names = new ArrayList<>();
        state.get("turnOrder").forEach(turn -> names.add(turn.get("name").asText()));
        return names;
    }

    private static String currentTurnName(JsonNode state) {
        return state.get("turnOrder").get(state.get("currentTurnIndex").asInt()).get("name").asText();
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asText().equals(displayName)) {
                return participant.get("id").asText();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }
}
