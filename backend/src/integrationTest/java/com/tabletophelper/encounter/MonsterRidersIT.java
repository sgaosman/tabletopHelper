package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monster hit riders through the API with the bundled definitions (D202): the phase spider's poison is a CON save
 * for its 4d8 rather than part of every bite, the ankheg's Large-or-smaller grapple doesn't hold a Huge hill giant,
 * and the grippli warrior's shortbow adds its 1d4 only when it shoots with advantage. Every creature's abilities are
 * 10, so its saves are +0.
 */
class MonsterRidersIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String spiderId;
    private String ankhegId;
    private String grippliId;
    private String giantId;
    private String goblinId;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);

        JsonNode definitions = read();
        spiderId = addMonster(definitions, monster("Phase Spider", "MM", "Large", "Monstrosity", 32, 13));
        ankhegId = addMonster(definitions, monster("Ankheg", "MM", "Large", "Monstrosity", 39, 14));
        grippliId = addMonster(definitions, monster("Grippli Warrior", "CM", "Small", "Humanoid", 22, 12));
        giantId = addMonster(definitions, monster("Hill Giant", "MM", "Huge", "Giant", 105, 13));
        goblinId = addMonster(definitions, monster("Goblin", "MM", "Small", "Humanoid", 7, 15));

        List<Map<String, Object>> initiatives = new ArrayList<>();
        int initiative = 20;
        for (String id : List.of(spiderId, ankhegId, grippliId, giantId, goblinId)) {
            initiatives.add(Map.of("participantId", id, "initiative", initiative--));
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", initiatives), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The phase spider's bite: 7 piercing on the hit, then a failed DC 11 Constitution save for the full "
            + "4d8 poison, and no poisoning or paralysis, which only come at 0 hit points")
    void phaseSpiderPoisonSave() {
        // 15 + 4 hits AC 13; 1d10 5 + 2 = 7 piercing; CON save 3 + 0 fails DC 11; 4d8 4 + 4 + 4 + 4 = 16 poison
        dice.script(15, 5, 3, 4, 4, 4, 4);
        expect(HttpStatus.OK, monsterAction(spiderId, "Bite", giantId, false));

        JsonNode giant = participant(dmView(), giantId);
        assertThat(giant.get("hpCurrent").asInt()).isEqualTo(105 - 23);
        assertThat(conditions(giant)).isEmpty();
        assertThat(dice.remaining()).isZero();
        assertThat(descriptions(dmLog())).anyMatch(line -> line.startsWith("Phase Spider uses Bite on Hill Giant")
                && line.endsWith("hit, 23 damage (7 piercing, 16 poison), CON save d20(3) + 0 = 3 vs DC 11 fails."));
    }

    @Test
    @DisplayName("A successful save halves the phase spider's poison")
    void phaseSpiderPoisonHalved() {
        // 7 piercing; CON save 15 succeeds; 4d8 = 16, halved to 8
        dice.script(15, 5, 15, 4, 4, 4, 4);
        expect(HttpStatus.OK, monsterAction(spiderId, "Bite", giantId, false));

        assertThat(participant(dmView(), giantId).get("hpCurrent").asInt()).isEqualTo(105 - 15);
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The ankheg's bite grapples a Large or smaller creature: not the Huge hill giant, which the log "
            + "explains, but the Small goblin")
    void ankhegGrappleSizeLimit() {
        // 15 + 5 hits; 2d6 3 + 3 + 3 = 9 slashing and 1d6 3 acid
        dice.script(15, 3, 3, 3);
        expect(HttpStatus.OK, monsterAction(ankhegId, "Bite", giantId, false));

        JsonNode state = dmView();
        assertThat(participant(state, giantId).get("hpCurrent").asInt()).isEqualTo(105 - 12);
        assertThat(conditions(participant(state, giantId))).isEmpty();
        assertThat(descriptions(dmLog())).contains("Hill Giant is Huge, too big to be grappled by Bite (Large or "
                + "smaller).");

        dice.script(15, 1, 1, 1);
        expect(HttpStatus.OK, monsterAction(ankhegId, "Bite", goblinId, false));
        assertThat(conditions(participant(dmView(), goblinId))).containsExactly("grappled");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The grippli warrior's shortbow adds 1d4 piercing only when it had advantage on the attack roll")
    void grippliAdvantageDamage() {
        // With advantage: d20s 15 and 12 hit AC 15 with 15 + 4; 1d6 3 + 2 = 5 and 1d4 2 piercing
        dice.script(15, 12, 3, 2);
        expect(HttpStatus.OK, monsterAction(grippliId, "Shortbow", giantId, true));
        assertThat(participant(dmView(), giantId).get("hpCurrent").asInt()).isEqualTo(105 - 7);

        // Without: only the 5; the log says the extra damage didn't apply
        dice.script(15, 3);
        expect(HttpStatus.OK, monsterAction(grippliId, "Shortbow", giantId, false));
        assertThat(participant(dmView(), giantId).get("hpCurrent").asInt()).isEqualTo(105 - 12);
        assertThat(descriptions(dmLog())).contains("Shortbow's 1d4 piercing (if the grippli had advantage on the "
                + "attack roll) doesn't apply to Hill Giant.");
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type, int hp,
                                                  int ac) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(hp).armourClass(ac).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    // The monster with the action templates the bundled definitions give it
    private String addMonster(JsonNode definitions, Monster.MonsterBuilder builder) {
        Monster monster = builder.build();
        for (JsonNode definition : definitions) {
            if (monster.getName().equals(definition.path("monsterName").asString(""))
                    && monster.getSource().equals(definition.path("source").asString(""))) {
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", saved.getName())), saved.getName());
            }
        }
        throw new AssertionError(monster.getName() + " has no definition");
    }

    private static JsonNode read() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            return JacksonDefaults.mapper().readTree(in);
        }
    }

    private ResponseEntity<String> monsterAction(String monsterId, String actionName, String targetId,
                                                 boolean advantage) {
        Map<String, Object> body = new HashMap<>(Map.of("monsterParticipantId", monsterId, "actionName", actionName,
                "actionSource", "ACTION", "targetParticipantIds", List.of(targetId)));
        if (advantage) {
            body.put("advantage", true);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action", body,
                dm.accessToken());
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static List<String> conditions(JsonNode participant) {
        List<String> names = new ArrayList<>();
        participant.path("activeConditions").forEach(c -> names.add(c.get("name").asString()));
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

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
