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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monsters whose actions the definitions lacked until D194, acting from the definitions as the data now gives them:
 * the pit fiend (MM) makes its four-attack multiattack, and the storm giant (MM) uses its Lightning Strike, a
 * Recharge 5-6 Dexterity save. Each monster's action templates are the bundled data's own.
 */
class StatBlockActionsIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String pitFiendId;
    private String giantId;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);

        JsonNode definitions = read();
        pitFiendId = addMonster(definitions, Monster.builder().name("Pit Fiend").source("MM").type("Fiend")
                .challengeRating("20").hitPoints(300).armourClass(19).dexterity(10).constitution(10));
        giantId = addMonster(definitions, Monster.builder().name("Storm Giant").source("MM").type("Giant")
                .challengeRating("13").hitPoints(230).armourClass(16).dexterity(10).constitution(10));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", pitFiendId, "initiative", 20),
                        Map.of("participantId", giantId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The pit fiend's multiattack makes its bite, claw, mace and tail; the bite poisons on a failed DC 21 "
            + "Constitution save and the mace adds fire")
    void pitFiendMultiattack() {
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/multiattack-options?participantId=" + pitFiendId, null, dm.accessToken()));
        assertThat(options.get(0).get("manualReason").isNull()).isTrue();
        List<String> components = new ArrayList<>();
        options.get(0).get("components").forEach(c -> {
            components.add(c.get("name").asString());
            assertThat(c.get("automated").asBoolean()).isTrue();
        });
        assertThat(components).containsExactly("Bite", "Claw", "Mace", "Tail");

        // Bite: 10 + 14 hits AC 16 for 3 + 3 + 3 + 3 + 8, and the giant's CON save of 1 + 0 fails DC 21;
        // claw: a 1 misses; mace: 10 + 14 hits for 3 + 3 + 8 bludgeoning and 2 x 6 fire; tail: a 1 misses
        dice.script(10, 3, 3, 3, 3, 1, 1, 10, 3, 3, 2, 2, 2, 2, 2, 2, 1);
        expect(HttpStatus.OK, monsterAction(pitFiendId, "Multiattack", giantId));

        JsonNode giant = participant(dmView(), giantId);
        assertThat(giant.get("hpCurrent").asInt()).isEqualTo(230 - 20 - 26);
        List<String> conditions = new ArrayList<>();
        giant.path("activeConditions").forEach(c -> conditions.add(c.get("name").asString()));
        assertThat(conditions).contains("poisoned");
        assertThat(dice.remaining()).isZero();
        List<String> log = descriptions(dmLog());
        assertThat(log).anyMatch(line -> line.startsWith("Pit Fiend uses Multiattack (Bite) on Storm Giant: "
                + "d20(10) + 14 = 24 vs AC 16") && line.endsWith("hit, 20 piercing damage, CON save d20(1) + 0 = 1 "
                + "vs DC 21 fails, gains poisoned."));
        assertThat(log).anyMatch(line -> line.startsWith("Pit Fiend uses Multiattack (Mace) on Storm Giant: ")
                && line.endsWith("hit, 26 damage (14 bludgeoning, 12 fire)."));
        // The bite's poison damage each turn is the DM's, noted from the stat block
        assertThat(log).anyMatch(line -> line.startsWith("Pit Fiend uses Multiattack.")
                && line.contains("takes 21 (6d6) poison damage at the start of each of its turns"));
    }

    @Test
    @DisplayName("The storm giant's Lightning Strike is a DC 17 Dexterity save for 12d8 lightning, and it can't be "
            + "used again until it recharges")
    void stormGiantLightningStrike() {
        // Twelve 4s for 48 lightning, and the pit fiend's DEX save of 4 + 0 fails DC 17, in either order
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4);
        expect(HttpStatus.OK, monsterAction(giantId, "Lightning Strike", pitFiendId));

        JsonNode state = dmView();
        assertThat(participant(state, pitFiendId).get("hpCurrent").asInt()).isEqualTo(300 - 48);
        assertThat(pool(participant(state, giantId), "monster:recharge:lightning-strike").get("currentUses").asInt())
                .isZero();
        assertThat(dice.remaining()).isZero();

        ResponseEntity<String> again = monsterAction(giantId, "Lightning Strike", pitFiendId);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).get("error").asString())
                .isEqualTo("Recharge action 'Lightning Strike' is not yet recharged");
    }

    // ── Helpers ──────────────────────────────────────────────────

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

    private ResponseEntity<String> monsterAction(String monsterId, String actionName, String targetId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", monsterId, "actionName", actionName, "actionSource", "ACTION",
                        "targetParticipantIds", List.of(targetId)), dm.accessToken());
    }

    private JsonNode dmView() {
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

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError(participant.get("displayName").asString() + " has no " + poolId + " pool");
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
