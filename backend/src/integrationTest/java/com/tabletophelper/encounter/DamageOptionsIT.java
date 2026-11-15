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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Damage of a type the monster chooses (D207), through the API with the bundled definitions: a dracohydra with three
 * heads bites a fire-resistant guard with fire, acid and cold, one type per Bite, and a Bite without a type is refused
 * with a 400 that changes nothing. The guard's abilities are 10, its AC 12 and its hit points 120.
 */
class DamageOptionsIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String dracohydra;
    private String guard;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        dracohydra = addDracohydra(3);
        Monster target = monster("Guard Target", "IT", "Medium", "Humanoid", 120, 12)
                .damageResistances("[\"fire\"]").build();
        guard = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", monsterRepository.save(target).getId(), "displayName", "Guard")), "Guard");
        start(dracohydra, guard);
    }

    @Test
    @DisplayName("Three heads bite with fire, acid and cold: each 10, the fire halved by the guard's resistance, and "
            + "the log names each type (FTD)")
    void threeHeadsThreeTypes() {
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/multiattack-options?participantId=" + dracohydra, null, dm.accessToken()));
        assertThat(options.get(0).get("components")).hasSize(3);
        options.get(0).get("components").forEach(component -> assertThat(texts(component.get("damageOptions")))
                .containsExactly("acid", "cold", "fire", "lightning", "poison"));

        // Each Bite: 10 + 9 hits AC 12 for 1d10 5 + 5 = 10
        dice.script(10, 5, 10, 5, 10, 5);
        expect(HttpStatus.OK, multiattack("fire", "acid", "cold"));
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(95);
        List<String> log = descriptions(dmLog());
        assertThat(log).anyMatch(line -> line.startsWith("Dracohydra uses Multiattack (Bite: fire; Bite: acid; "
                + "Bite: cold)."));
        assertThat(log).anyMatch(line -> line.contains("5 fire damage"));
        assertThat(log).anyMatch(line -> line.contains("10 acid damage"));
        assertThat(log).anyMatch(line -> line.contains("10 cold damage"));
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A Bite without a type, or with one not among its options, is a 400 that changes nothing")
    void missingType() {
        ResponseEntity<String> refused = multiattack("fire", null, "cold");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Choose a damage type for Bite (attack 2): acid, cold, fire, "
                + "lightning, poison");
        ResponseEntity<String> wrong = multiattack("fire", "radiant", "cold");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrong.getBody()).contains("radiant isn't a damage type Bite (attack 2) can deal");
        ResponseEntity<String> single = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/monster-action", Map.of("monsterParticipantId", dracohydra, "actionName", "Bite",
                "actionSource", "ACTION", "targetParticipantIds", List.of(guard)), dm.accessToken());
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(single.getBody()).contains("Choose a damage type for Bite: acid, cold, fire, lightning, poison");
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(120);
        assertThat(descriptions(dmLog())).noneMatch(line -> line.startsWith("Dracohydra uses"));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> multiattack(String... types) {
        Map<String, Object> request = new HashMap<>(Map.of("monsterParticipantId", dracohydra,
                "actionName", "Multiattack", "actionSource", "ACTION",
                "componentTargetIds", List.of(List.of(guard), List.of(guard), List.of(guard))));
        request.put("componentDamageTypes", Arrays.asList(types));
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action", request,
                dm.accessToken());
    }

    private void start(String... order) {
        List<Map<String, Object>> initiatives = new ArrayList<>();
        int initiative = 20;
        for (String id : order) {
            initiatives.add(Map.of("participantId", id, "initiative", initiative--));
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", initiatives), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type, int hp,
                                                  int ac) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(hp).armourClass(ac).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    // The dracohydra with the action templates of the bundled definitions and the heads the DM chose
    private String addDracohydra(int heads) throws IOException {
        JsonNode definitions;
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            definitions = JacksonDefaults.mapper().readTree(in);
        }
        for (JsonNode definition : definitions) {
            if ("Dracohydra".equals(definition.path("monsterName").asString(""))
                    && "FTD".equals(definition.path("source").asString(""))) {
                Monster monster = monster("Dracohydra", "FTD", "Huge", "Dragon", 157, 16).build();
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", "Dracohydra", "heads", heads)), "Dracohydra");
            }
        }
        throw new AssertionError("The dracohydra has no definition");
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asString()));
        return texts;
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
