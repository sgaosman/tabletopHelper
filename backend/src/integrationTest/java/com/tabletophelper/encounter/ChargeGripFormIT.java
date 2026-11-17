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
 * A charge, a versatile weapon's grip and a form (D241), through the API with the bundled definitions: an aurochs
 * charges (MPMM), a hobgoblin swings its longsword in two hands (MM p.186), and a wereraven pecks in raven form (VRGR),
 * each at a target of AC 12, abilities 10 and 100 hit points. A player of the campaign reads the log, which names the
 * choices but never the target monster's AC (D235).
 */
class ChargeGripFormIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String aurochs;
    private String hobgoblin;
    private String wereraven;
    private String target;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        encounterId = createEncounter(dm.accessToken(), campaignId);
        aurochs = addFromDefinitions("Aurochs", "MPMM", "Large", "Beast");
        hobgoblin = addFromDefinitions("Hobgoblin", "MM", "Medium", "Humanoid");
        wereraven = addFromDefinitions("Wereraven", "VRGR", "Medium", "Humanoid");
        Monster dummy = monster("Target", "IT", "Medium", "Humanoid").build();
        target = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", monsterRepository.save(dummy).getId(), "displayName", "Target")), "Target");
        List<Map<String, Object>> initiatives = new ArrayList<>();
        int initiative = 20;
        for (String id : List.of(aurochs, hobgoblin, wereraven, target)) {
            initiatives.add(Map.of("participantId", id, "initiative", initiative--));
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", initiatives), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Charging, the aurochs's Gore deals 2d8 + 5 and 2d8 and knocks the target prone on a failed save; "
            + "the player's log names the charge but not the target's AC")
    void aurochsCharges() {
        // 10 + 7 hits AC 12; 4 + 4 + 5 = 13, then 3 + 3 = 6; the Strength save 2 + 0 fails DC 15
        dice.script(10, 4, 4, 3, 3, 2);
        expect(HttpStatus.OK, act(aurochs, "Gore", Map.of("charged", true)));
        JsonNode dummy = participant(dmView(), target);
        assertThat(dummy.get("hpCurrent").asInt()).isEqualTo(81);
        assertThat(dummy.toString()).contains("prone");
        assertThat(dice.remaining()).isZero();
        assertThat(descriptions(log(dm))).anyMatch(line -> line.startsWith("Aurochs uses Gore (charging)."));
        List<String> playerLog = descriptions(log(player));
        assertThat(playerLog).anyMatch(line -> line.startsWith("Aurochs uses Gore (charging)."));
        assertThat(playerLog).noneMatch(line -> line.contains("AC 12"));
    }

    @Test
    @DisplayName("Without the charge the Gore deals 2d8 + 5 alone, with no save")
    void aurochsWalks() {
        dice.script(10, 4, 4);
        expect(HttpStatus.OK, act(aurochs, "Gore", Map.of()));
        JsonNode dummy = participant(dmView(), target);
        assertThat(dummy.get("hpCurrent").asInt()).isEqualTo(87);
        assertThat(dummy.toString()).doesNotContain("prone");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The hobgoblin's longsword in two hands rolls 1d10 + 1, and two hands on its longbow is a 400")
    void hobgoblinTwoHanded() {
        dice.script(10, 7);
        expect(HttpStatus.OK, act(hobgoblin, "Longsword", Map.of("twoHanded", true)));
        assertThat(participant(dmView(), target).get("hpCurrent").asInt()).isEqualTo(92);
        assertThat(descriptions(log(dm))).anyMatch(line -> line.startsWith("Hobgoblin uses Longsword (two-handed)."));
        ResponseEntity<String> bow = act(hobgoblin, "Longbow", Map.of("twoHanded", true));
        assertThat(bow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bow.getBody()).contains("Longbow isn't a versatile weapon attack");
    }

    @Test
    @DisplayName("The wereraven's beak in raven form deals 1 piercing in place of 1d4 + 2")
    void wereravenRavenForm() {
        dice.script(10);
        expect(HttpStatus.OK, act(wereraven, "Beak (Raven or Hybrid Form Only)", Map.of("attackerForm",
                "raven form")));
        assertThat(participant(dmView(), target).get("hpCurrent").asInt()).isEqualTo(99);
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> act(String monster, String action, Map<String, Object> choices) {
        Map<String, Object> request = new HashMap<>(Map.of("monsterParticipantId", monster, "actionName", action,
                "actionSource", "ACTION", "targetParticipantIds", List.of(target)));
        request.putAll(choices);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action", request,
                dm.accessToken());
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(100).armourClass(12).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
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
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", name)), name);
            }
        }
        throw new AssertionError(name + " has no definition");
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode log(RegisteredUser user) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                user.accessToken()));
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
