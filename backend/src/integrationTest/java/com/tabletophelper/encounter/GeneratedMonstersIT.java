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
 * Monsters that had no action entry at all until D205, acting from the entries generate_monster_actions.py now gives
 * them, through the API with the bundled definitions: a purple worm swallows a guard and digests it until it dies, a
 * balor makes its longsword and whip multiattack, and a horned devil's tail leaves an infernal wound that bleeds at the
 * start of the target's turns. Every creature's abilities are 10, so its saves are +0.
 */
class GeneratedMonstersIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private JsonNode definitions;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            definitions = JacksonDefaults.mapper().readTree(in);
        }
    }

    @Test
    @DisplayName("A purple worm's bite swallows a guard who fails a DC 19 Dexterity save: blinded and restrained, out "
            + "of the horned devil's reach, 6d6 acid at the start of the worm's turns, and out of the corpse prone when "
            + "the worm dies (MM p.255)")
    void purpleWormSwallows() {
        String worm = addMonster("Purple Worm", "Gargantuan", "Monstrosity", 247, 18);
        String guard = addTarget("Guard", "Medium", 120, 12);
        String devil = addMonster("Horned Devil", "Large", "Fiend", 178, 18);
        start(worm, guard, devil);

        // 15 + 14 hits AC 12; 3d8 4 + 4 + 4 + 9 = 21 piercing; the guard's DEX save, 5 + 0, fails DC 19
        dice.script(15, 4, 4, 4, 5);
        expect(HttpStatus.OK, monsterAction(worm, "Bite", guard));
        JsonNode swallowed = participant(dmView(), guard);
        assertThat(conditions(swallowed)).containsExactlyInAnyOrder("swallowed", "blinded", "restrained");
        assertThat(swallowed.get("hpCurrent").asInt()).isEqualTo(120 - 21);
        // Nothing outside the worm reaches the guard
        assertThat(monsterAction(devil, "Fork", guard).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The guard's turn, the devil's, then the worm's: 6d6 acid at its start
        nextTurn();
        nextTurn();
        dice.script(3, 3, 3, 3, 3, 3);
        nextTurn();
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(99 - 18);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", worm, "amount", 300), dm.accessToken()));
        assertThat(conditions(participant(dmView(), guard))).containsExactly("prone");
        assertThat(descriptions(dmLog())).contains("Guard escapes from Purple Worm's corpse using 20 feet of "
                + "movement, exiting prone; it is prone.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A balor's multiattack makes its longsword, 3d8 + 8 slashing and 3d8 lightning, and its whip, 2d6 + 8 "
            + "slashing and 3d6 fire, whose pull the DM applies (MM p.55)")
    void balorMultiattack() {
        String balor = addMonster("Balor", "Huge", "Fiend", 262, 19);
        String guard = addTarget("Guard", "Medium", 200, 16);
        start(balor, guard);

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/multiattack-options?participantId=" + balor, null, dm.accessToken()));
        assertThat(options.get(0).get("manualReason").isNull()).isTrue();
        List<String> components = new ArrayList<>();
        options.get(0).get("components").forEach(c -> {
            components.add(c.get("name").asString());
            assertThat(c.get("automated").asBoolean()).isTrue();
        });
        assertThat(components).containsExactly("Longsword", "Whip");

        // Longsword: 10 + 14 hits AC 16 for 3 + 3 + 3 + 8 slashing and 2 + 2 + 2 lightning; whip: 10 + 14 hits for
        // 3 + 3 + 8 slashing and 2 + 2 + 2 fire
        dice.script(10, 3, 3, 3, 2, 2, 2, 10, 3, 3, 2, 2, 2);
        expect(HttpStatus.OK, monsterAction(balor, "Multiattack", guard));
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(200 - 23 - 20);
        List<String> log = descriptions(dmLog());
        assertThat(log).anyMatch(line -> line.startsWith("Balor uses Multiattack (Longsword) on Guard: d20(10) + 14 = "
                + "24 vs AC 16") && line.contains("hit, 23 damage (17 slashing, 6 lightning)"));
        assertThat(log).anyMatch(line -> line.startsWith("Balor uses Multiattack (Whip) on Guard: ")
                && line.contains("hit, 20 damage (14 slashing, 6 fire)"));
        // The whip's pull needs a map, so the log gives it to the DM with the multiattack
        assertThat(log).anyMatch(line -> line.startsWith("Balor uses Multiattack.")
                && line.contains("be pulled up to 25 feet toward the balor"));
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A horned devil's tail wounds a guard who fails a DC 17 Constitution save, who loses 3d6 hit points "
            + "at the start of each of its turns (MM p.74)")
    void hornedDevilTail() {
        String devil = addMonster("Horned Devil", "Large", "Fiend", 178, 18);
        String guard = addTarget("Guard", "Medium", 100, 12);
        start(devil, guard);

        // 10 + 10 hits AC 12; 1d8 4 + 6 = 10 piercing; the guard's CON save, 5 + 0, fails DC 17
        dice.script(10, 4, 5);
        expect(HttpStatus.OK, monsterAction(devil, "Tail", guard));
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(100 - 10);
        assertThat(descriptions(dmLog())).anyMatch(line -> line.contains("Guard loses 3d6 hit points at the start of "
                + "each of its turns (Horned Devil's Tail)"));

        // The guard's turn starts: 3d6, 3 + 3 + 3
        dice.script(3, 3, 3);
        nextTurn();
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(90 - 9);
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

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

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type, int hp,
                                                  int ac) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(hp).armourClass(ac).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    // The MM monster with the action templates the bundled definitions generated for it (D205)
    private String addMonster(String name, String size, String type, int hp, int ac) {
        Monster monster = monster(name, "MM", size, type, hp, ac).build();
        for (JsonNode definition : definitions) {
            if (name.equals(definition.path("monsterName").asString(""))
                    && "MM".equals(definition.path("source").asString(""))) {
                assertThat(definition.path("_entryFromStatBlock").asBoolean(false)).isTrue();
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", name)), name);
            }
        }
        throw new AssertionError(name + " has no definition");
    }

    // A humanoid with no actions of its own, to be the target
    private String addTarget(String name, String size, int hp, int ac) {
        Monster saved = monsterRepository.save(monster(name + " Target", "IT", size, "Humanoid", hp, ac).build());
        return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", saved.getId(), "displayName", name)), name);
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
