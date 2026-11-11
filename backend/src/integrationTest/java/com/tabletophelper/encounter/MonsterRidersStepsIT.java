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
 * Monster riders, steps 3 to 5 (D203), through the API with the bundled definitions: a wight's Life Drain lowers a hill
 * giant's hit point maximum on a failed save; a carrion crawler's paralysis lasts only while its poison does; a giant
 * constrictor snake's Constrict grapples and restrains one creature at a time, and its grapple is escaped with a rolled
 * check; a stirge attaches and drains blood at the start of its own turns until it has drained 10; a ghoul's claws pass
 * over undead; and a dragon's Frightful Presence leaves a creature that saved immune. Every creature's abilities are 10,
 * so its saves and checks are +0.
 */
class MonsterRidersStepsIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String stirgeId;
    private String wightId;
    private String crawlerId;
    private String snakeId;
    private String ghoulId;
    private String dragonId;
    private String giantId;
    private String otherGiantId;
    private String zombieId;
    private int participants;
    // The stat blocks saved so far, so a second hill giant shares the first's
    private final Map<String, Monster> statBlocks = new HashMap<>();

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);

        JsonNode definitions = read();
        stirgeId = addMonster(definitions, monster("Stirge", "MM", "Tiny", "Beast", 2, 14), "Stirge");
        wightId = addMonster(definitions, monster("Wight", "MM", "Medium", "Undead", 45, 14), "Wight");
        crawlerId = addMonster(definitions, monster("Carrion Crawler", "MM", "Large", "Monstrosity", 51, 13),
                "Carrion Crawler");
        snakeId = addMonster(definitions, monster("Giant Constrictor Snake", "MM", "Huge", "Beast", 60, 12),
                "Giant Constrictor Snake");
        ghoulId = addMonster(definitions, monster("Ghoul", "MM", "Medium", "Undead", 22, 12), "Ghoul");
        dragonId = addMonster(definitions, monster("Adult Red Dragon", "MM", "Huge", "Dragon", 256, 19),
                "Adult Red Dragon");
        giantId = addMonster(definitions, monster("Hill Giant", "MM", "Huge", "Giant", 105, 13), "Hill Giant");
        otherGiantId = addMonster(definitions, monster("Hill Giant", "MM", "Huge", "Giant", 105, 13), "Hill Giant 2");
        zombieId = addMonster(definitions, monster("Zombie", "MM", "Medium", "Undead", 22, 8), "Zombie");

        List<String> order = List.of(stirgeId, wightId, crawlerId, snakeId, ghoulId, dragonId, giantId, otherGiantId,
                zombieId);
        participants = order.size();
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

    @Test
    @DisplayName("A wight's Life Drain lowers the hill giant's hit point maximum by the necrotic damage on a failed DC "
            + "13 Constitution save, until a long rest, and not on a success (MM p.300)")
    void wightLifeDrain() {
        // 15 + 4 hits AC 13; 1d6 4 + 2 = 6 necrotic; CON save 5 fails
        dice.script(15, 4, 5);
        expect(HttpStatus.OK, monsterAction(wightId, "Life Drain", giantId));
        JsonNode giant = participant(dmView(), giantId);
        assertThat(giant.get("hpMax").asInt()).isEqualTo(99);
        assertThat(giant.get("hpCurrent").asInt()).isEqualTo(99);
        assertThat(descriptions(dmLog())).contains("Hill Giant's hit point maximum is reduced by 6 (Wight's Life "
                + "Drain) until it finishes a long rest; it is now 99.");

        // A success: 6 more necrotic, the maximum kept
        dice.script(15, 4, 18);
        expect(HttpStatus.OK, monsterAction(wightId, "Life Drain", giantId));
        giant = participant(dmView(), giantId);
        assertThat(giant.get("hpMax").asInt()).isEqualTo(99);
        assertThat(giant.get("hpCurrent").asInt()).isEqualTo(93);
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A carrion crawler's paralysis lasts only while its poison does: removing the poison ends both "
            + "(MM p.37)")
    void carrionCrawlerParalysis() {
        // 15 + 8 hits; 1d4 2 + 2 = 4 poison; CON save 3 fails DC 13
        dice.script(15, 2, 3);
        expect(HttpStatus.OK, monsterAction(crawlerId, "Tentacle", giantId));
        assertThat(conditions(participant(dmView(), giantId))).containsExactlyInAnyOrder("poisoned", "paralyzed");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/remove",
                Map.of("targetId", giantId, "condition", "poisoned"), dm.accessToken()));
        assertThat(conditions(participant(dmView(), giantId))).isEmpty();
        assertThat(descriptions(dmLog())).contains("paralyzed ends on Hill Giant (what it lasted while has ended)");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A giant constrictor snake grapples and restrains one creature: another is refused until the grapple "
            + "ends, which the held giant's rolled escape does, its restraint with it (MM p.324, PHB p.195)")
    void constrictorSnake() {
        // 15 + 6 hits; 2d8 3 + 3 + 4 = 10 bludgeoning
        dice.script(15, 3, 3);
        expect(HttpStatus.OK, monsterAction(snakeId, "Constrict", giantId));
        JsonNode giant = participant(dmView(), giantId);
        assertThat(giant.get("hpCurrent").asInt()).isEqualTo(95);
        assertThat(conditions(giant)).containsExactlyInAnyOrder("grappled", "restrained");
        JsonNode grappled = entry(giant, "grappled");
        assertThat(grappled.path("repeatSave").path("escape").asBoolean()).isTrue();
        assertThat(grappled.path("repeatSave").path("dc").asInt()).isEqualTo(16);
        assertThat(grappled.path("sourceAction").asString()).isEqualTo("Constrict");
        assertThat(entry(giant, "restrained").path("linkedTo").asString()).isEqualTo(grappled.get("id").asString());

        ResponseEntity<String> refused = monsterAction(snakeId, "Constrict", otherGiantId);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("Giant Constrictor Snake's Constrict already holds Hill Giant, and it "
                + "can't be used on another creature until that grapple ends");

        // The giant's escape: Athletics +0, d20 9 fails DC 16, then 18 succeeds
        dice.script(9);
        expect(HttpStatus.OK, actionSave(giantId, grappled.get("id").asString()));
        assertThat(conditions(participant(dmView(), giantId))).containsExactlyInAnyOrder("grappled", "restrained");
        dice.script(18);
        expect(HttpStatus.OK, actionSave(giantId, grappled.get("id").asString()));
        assertThat(conditions(participant(dmView(), giantId))).isEmpty();
        assertThat(descriptions(dmLog())).anyMatch(line -> line.startsWith("Hill Giant uses its action to escape the "
                + "grapple (Giant Constrictor Snake's Constrict): Strength (Athletics) check d20(18) + 0 = 18 vs DC 16 "
                + "— escapes"));

        // Free again, the snake constricts the other giant
        dice.script(15, 3, 3);
        expect(HttpStatus.OK, monsterAction(snakeId, "Constrict", otherGiantId));
        assertThat(conditions(participant(dmView(), otherGiantId))).containsExactlyInAnyOrder("grappled",
                "restrained");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A stirge attaches, then drains 1d4 + 3 at the start of each of its turns until it has drained 10, "
            + "and detaches (MM p.284)")
    void stirgeAttaches() {
        // 15 + 5 hits AC 13; 1d4 2 + 3 = 5 piercing
        dice.script(15, 2);
        expect(HttpStatus.OK, monsterAction(stirgeId, "Blood Drain", giantId));
        assertThat(conditions(participant(dmView(), giantId))).containsExactly("attached");

        // A round later, at the start of the stirge's turn: 1d4 3 + 3 = 6 hit points lost
        dice.script(3);
        nextRound();
        assertThat(participant(dmView(), giantId).get("hpCurrent").asInt()).isEqualTo(105 - 5 - 6);
        assertThat(conditions(participant(dmView(), giantId))).containsExactly("attached");

        // Another 1d4 2 + 3 = 5: 11 drained, so it detaches
        dice.script(2);
        nextRound();
        assertThat(participant(dmView(), giantId).get("hpCurrent").asInt()).isEqualTo(105 - 5 - 6 - 5);
        assertThat(conditions(participant(dmView(), giantId))).isEmpty();
        assertThat(descriptions(dmLog())).contains("attached ends on Hill Giant (Stirge's Blood Drain ends)");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A ghoul's claws don't paralyze an undead zombie, which makes no save, but a hill giant that fails "
            + "its save is paralyzed (MM p.148)")
    void ghoulPassesOverUndead() {
        // 15 + 4 hits AC 8; 2d4 1 + 1 + 2 = 4 slashing; no save
        dice.script(15, 1, 1);
        expect(HttpStatus.OK, monsterAction(ghoulId, "Claw", zombieId));
        assertThat(conditions(participant(dmView(), zombieId))).isEmpty();
        assertThat(descriptions(dmLog())).contains("Claw doesn't leave Zombie paralyzed: it passes over elf and undead "
                + "creatures.");

        dice.script(15, 1, 1, 2);
        expect(HttpStatus.OK, monsterAction(ghoulId, "Claw", giantId));
        assertThat(conditions(participant(dmView(), giantId))).containsExactly("paralyzed");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A hill giant that saves against an adult red dragon's Frightful Presence is immune to it for the "
            + "rest of the encounter (MM p.98)")
    void frightfulPresenceImmunity() {
        // WIS save 20 succeeds DC 19
        dice.script(20);
        expect(HttpStatus.OK, monsterAction(dragonId, "Frightful Presence", giantId));
        assertThat(descriptions(dmLog())).contains("Hill Giant is immune to Adult Red Dragon's Frightful Presence for "
                + "the next 24 hours.");

        // Used again, it rolls nothing for the giant
        dice.script();
        expect(HttpStatus.OK, monsterAction(dragonId, "Frightful Presence", giantId));
        assertThat(conditions(participant(dmView(), giantId))).isEmpty();
        assertThat(descriptions(dmLog())).anyMatch(line -> line.startsWith("Hill Giant is immune to Adult Red "
                + "Dragon's Frightful Presence ("));
    }

    // ── Helpers ──────────────────────────────────────────────────

    // Advances the turn until the first creature's turn comes round again
    private void nextRound() {
        for (int i = 0; i < participants; i++) {
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                    null, dm.accessToken()));
        }
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type, int hp,
                                                  int ac) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(hp).armourClass(ac).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    // The monster with the action templates the bundled definitions give it, added under this name; a second of the
    // same stat block shares it
    private String addMonster(JsonNode definitions, Monster.MonsterBuilder builder, String displayName) {
        Monster monster = builder.build();
        Monster saved = statBlocks.get(monster.getName());
        for (JsonNode definition : definitions) {
            if (saved == null && monster.getName().equals(definition.path("monsterName").asString(""))
                    && monster.getSource().equals(definition.path("source").asString(""))) {
                monster.setActionTemplates(MonsterTemplates.of(definition));
                saved = monsterRepository.save(monster);
                statBlocks.put(monster.getName(), saved);
            }
        }
        if (saved == null) {
            throw new AssertionError(monster.getName() + " has no definition");
        }
        return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", saved.getId(), "displayName", displayName)), displayName);
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

    private ResponseEntity<String> actionSave(String participantId, String conditionId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/action-save",
                Map.of("participantId", participantId, "conditionId", conditionId), dm.accessToken());
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

    private static JsonNode entry(JsonNode participant, String name) {
        for (JsonNode condition : participant.path("activeConditions")) {
            if (condition.get("name").asString().equals(name)) {
                return condition;
            }
        }
        throw new AssertionError(name + " is not on " + participant.get("displayName").asString());
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
