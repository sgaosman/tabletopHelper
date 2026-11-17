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
 * D241's leftovers (D243), through the API with the bundled definitions: the ferrumach's Skewering Charge and the
 * giff's Headfirst Charge used from their traits, at no cost, on a target of AC 12, abilities 10 and 100 hit points or
 * on a Huge one; the minotaur infiltrator's enlarged charge; and the yochlol's bite in spider form.
 */
class TraitChargeIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String ferrumach;
    private String giff;
    private String minotaur;
    private String yochlol;
    private String target;
    private String giant;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        JsonNode campaign = createCampaign(dm.accessToken());
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        ferrumach = addFromDefinitions("Ferrumach Rilmani", "MPP", "Medium", "Celestial");
        giff = addFromDefinitions("Giff", "MPMM", "Medium", "Humanoid");
        minotaur = addFromDefinitions("Minotaur Infiltrator", "BMT", "Medium", "Monstrosity");
        yochlol = addFromDefinitions("Yochlol", "MM", "Medium", "Fiend");
        target = add(monster("Target", "IT", "Medium", "Humanoid").build());
        giant = add(monster("Giant", "IT", "Huge", "Giant").build());
        List<Map<String, Object>> initiatives = new ArrayList<>();
        int initiative = 20;
        for (String id : List.of(ferrumach, giff, minotaur, yochlol, target, giant)) {
            initiatives.add(Map.of("participantId", id, "initiative", initiative--));
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", initiatives), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Skewering Charge: on a failed DC 16 Strength save the target takes 3d6 piercing and is grappled "
            + "(escape DC 18) by the ferrumach, and the ferrumach keeps its action")
    void skeweringCharge() {
        // 3 + 3 + 3 = 9 piercing; the Strength save 2 + 0 fails DC 16
        dice.script(3, 3, 3, 2);
        expect(HttpStatus.OK, trait(ferrumach, "Skewering Charge", target));
        JsonNode dummy = participant(dmView(), target);
        assertThat(dummy.get("hpCurrent").asInt()).isEqualTo(91);
        assertThat(dummy.toString()).contains("grappled");
        assertThat(dice.remaining()).isZero();
        assertThat(descriptions()).anyMatch(line -> line.startsWith("Ferrumach Rilmani uses Skewering Charge."));
        assertThat(descriptions()).noneMatch(line -> line.contains("Skewering Charge (bonus action)")
                || line.contains("Skewering Charge (reaction)"));
        // It costs nothing from the ferrumach's turn
        JsonNode economy = participant(dmView(), ferrumach).path("turnEconomy");
        assertThat(economy.path("actionsUsed").asInt(0)).isZero();
        assertThat(economy.path("bonusActionUsed").asBoolean(false)).isFalse();
        assertThat(economy.path("reactionUsed").asBoolean(false)).isFalse();
    }

    @Test
    @DisplayName("Headfirst Charge: a successful save leaves the target unharmed and standing; a Huge creature is "
            + "refused, a Large or smaller creature only")
    void headfirstCharge() {
        dice.script(4, 4, 19);
        expect(HttpStatus.OK, trait(giff, "Headfirst Charge", target));
        JsonNode dummy = participant(dmView(), target);
        assertThat(dummy.get("hpCurrent").asInt()).isEqualTo(100);
        assertThat(dummy.toString()).doesNotContain("prone");
        ResponseEntity<String> huge = trait(giff, "Headfirst Charge", giant);
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(huge.getBody()).contains("Headfirst Charge reaches only a Large or smaller creature, and Giant is "
                + "Huge");
    }

    @Test
    @DisplayName("A trait the DM can't use as an action, the giff's Firearms Knowledge, isn't found among its traits")
    void passiveTrait() {
        ResponseEntity<String> passive = trait(giff, "Firearms Knowledge", target);
        assertThat(passive.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(passive.getBody()).contains("source: TRAIT");
    }

    @Test
    @DisplayName("The minotaur infiltrator charging while enlarged deals 2d8 + 4 and 2d8 piercing, the enlarged dice "
            + "in place of 1d8 + 4 and 1d8")
    void enlargedCharge() {
        // 10 + 6 hits AC 12; 2d8 + 4 = 3 + 3 + 4 = 10, then the charge's 1d8 and the enlarged 1d8: 2 + 2; the save 19
        // succeeds, so no prone
        dice.script(10, 3, 3, 2, 2, 19);
        expect(HttpStatus.OK, act(minotaur, "Gore", target, Map.of("charged", true, "attackerForm", "enlarged")));
        assertThat(participant(dmView(), target).get("hpCurrent").asInt()).isEqualTo(86);
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The yochlol's bite in spider form deals 1d6 + 2 piercing in place of bludgeoning, plus 6d6 poison")
    void yochlolSpiderForm() {
        dice.script(10, 3, 1, 1, 1, 1, 1, 1);
        JsonNode result = expect(HttpStatus.OK, act(yochlol, "Slam (Bite in Spider Form)", target,
                Map.of("attackerForm", "spider form")));
        assertThat(participant(dmView(), target).get("hpCurrent").asInt()).isEqualTo(89);
        assertThat(result.toString()).contains("piercing").doesNotContain("bludgeoning");
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> trait(String monster, String trait, String at) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", monster, "actionName", trait, "actionSource", "TRAIT",
                        "targetParticipantIds", List.of(at)), dm.accessToken());
    }

    private ResponseEntity<String> act(String monster, String action, String at) {
        return act(monster, action, at, Map.of());
    }

    private ResponseEntity<String> act(String monster, String action, String at, Map<String, Object> choices) {
        Map<String, Object> request = new HashMap<>(Map.of("monsterParticipantId", monster, "actionName", action,
                "actionSource", "ACTION", "targetParticipantIds", List.of(at)));
        request.putAll(choices);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action", request,
                dm.accessToken());
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(100).armourClass(12).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    private String add(Monster monster) {
        Monster saved = monsterRepository.save(monster);
        return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", saved.getId(), "displayName", saved.getName())), saved.getName());
    }

    // A monster with the action templates of the bundled definitions
    private String addFromDefinitions(String name, String source, String size, String type) throws IOException {
        JsonNode definitions;
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            definitions = JacksonDefaults.mapper().readTree(in);
        }
        for (JsonNode definition : definitions) {
            if (name.equals(definition.path("monsterName").asString(""))
                    && source.equals(definition.path("source").asString(""))) {
                Monster monster = monster(name, source, size, type).build();
                monster.setActionTemplates(MonsterTemplates.of(definition));
                return add(monster);
            }
        }
        throw new AssertionError(name + " has no definition");
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private List<String> descriptions() {
        JsonNode log = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
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
