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
 * A shambling mound's Engulf and a hydra's heads (D206), through the API with the bundled definitions: the mound's two
 * Slams on one guard grapple and engulf it, the guard saves at the start of each of the mound's turns against 2d8 + 4
 * bludgeoning, the mound engulfs one creature at a time and only one it grapples; a dracohydra added with three heads
 * makes three Bites, a hydra added without a count has the stat block's five, and a monster without heads refuses a
 * count. Every creature's abilities are 10, so its saves are +0.
 */
class EngulfAndHeadsIT extends IntegrationTest {

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
    @DisplayName("Both Slams hit a guard: grappled (escape DC 14) and engulfed, blinded and restrained; at the start of "
            + "each of the mound's turns a failed DC 14 Constitution save takes 2d8 + 4 bludgeoning and a success none "
            + "(MM p.270)")
    void slamSlamEngulf() {
        String mound = addMonster("Shambling Mound", "MM", "Large", "Plant", 136, 15, null);
        String guard = addTarget("Guard", "Medium", 120, 12);
        start(mound, guard);

        // Each Slam: 10 + 7 hits AC 12 for 2d8 3 + 3 + 4 = 10 bludgeoning
        dice.script(10, 3, 3, 10, 3, 3);
        expect(HttpStatus.OK, monsterAction(mound, "Multiattack", guard));
        JsonNode engulfed = participant(dmView(), guard);
        assertThat(conditions(engulfed)).containsExactlyInAnyOrder("grappled", "engulfed", "blinded", "restrained");
        assertThat(engulfed.get("hpCurrent").asInt()).isEqualTo(100);
        List<String> log = descriptions(dmLog());
        assertThat(log).anyMatch(line -> line.startsWith("Both Slam attacks hit Guard: If both attacks hit a Medium or "
                + "smaller target"));
        assertThat(log).anyMatch(line -> line.contains("Guard takes 2d8 + 4 bludgeoning damage at the start of each "
                + "of Shambling Mound's turns (Shambling Mound's Engulf) unless it succeeds on a DC 14 Constitution "
                + "saving throw made each time"));

        // The guard's turn, then the mound's: 2d8 4 + 4 + 4 = 12, the guard's save 5 fails
        nextTurn();
        dice.script(4, 4, 5);
        nextTurn();
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(88);
        // Next round: the save, 15, succeeds, and the guard takes nothing
        nextTurn();
        dice.script(4, 4, 15);
        nextTurn();
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(88);
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("Engulf is refused on a creature the mound isn't grappling, a Large one isn't grappled by the Slams, "
            + "and a second grappled creature isn't engulfed while the first is")
    void refusals() {
        String mound = addMonster("Shambling Mound", "MM", "Large", "Plant", 136, 15, null);
        String guard = addTarget("Guard", "Medium", 120, 12);
        String ogre = addTarget("Ogre", "Large", 120, 12);
        String scout = addTarget("Scout", "Medium", 120, 12);
        start(mound, guard, ogre, scout);

        ResponseEntity<String> refused = monsterAction(mound, "Engulf", guard);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("can be used only on a creature it is grappling");

        dice.script(10, 3, 3, 10, 3, 3);
        expect(HttpStatus.OK, monsterAction(mound, "Multiattack", ogre));
        assertThat(conditions(participant(dmView(), ogre))).isEmpty();
        assertThat(descriptions(dmLog())).contains("Ogre is Large, too big to be grappled by Multiattack (Medium or "
                + "smaller).");

        // Next round: the guard is engulfed; the round after, the scout is grappled but not engulfed
        nextRound();
        dice.script(10, 3, 3, 10, 3, 3);
        expect(HttpStatus.OK, monsterAction(mound, "Multiattack", guard));
        assertThat(conditions(participant(dmView(), guard))).contains("engulfed");
        // At the start of the mound's turn, the guard's save: 2d8 4 + 4, then 15 succeeds
        nextRound(4, 4, 15);
        dice.script(10, 3, 3, 10, 3, 3);
        expect(HttpStatus.OK, monsterAction(mound, "Multiattack", scout));
        assertThat(conditions(participant(dmView(), scout))).containsExactly("grappled");
        assertThat(descriptions(dmLog())).anyMatch(line -> line.contains("already holds 1 creature engulfed, so Scout "
                + "isn't engulfed"));
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A dracohydra added with three heads makes three Bites, each the DM's for its chosen damage type, and "
            + "its Heads pool shows 3/3 (FTD)")
    void dracohydraWithThreeHeads() {
        String dracohydra = addMonster("Dracohydra", "FTD", "Huge", "Dragon", 172, 15, 3);
        String guard = addTarget("Guard", "Medium", 120, 12);
        start(dracohydra, guard);

        JsonNode heads = pool(participant(dmView(), dracohydra), "monster:heads");
        assertThat(heads.get("currentUses").asInt()).isEqualTo(3);
        assertThat(heads.get("maxUses").asInt()).isEqualTo(3);
        assertThat(components(dracohydra)).containsExactly("Bite", "Bite", "Bite");

        expect(HttpStatus.OK, monsterAction(dracohydra, "Multiattack", guard));
        assertThat(descriptions(dmLog())).anyMatch(line -> line.startsWith("Dracohydra uses Multiattack.")
                && line.contains("With 3 heads it makes 3 Bite attacks. Multiple Heads (the DM changes its Heads pool "
                + "as heads die or regrow): While it has more than one head"));
    }

    @Test
    @DisplayName("A hydra added without a count has the stat block's five heads and bites five times; a head that "
            + "dies leaves four Bites (MM p.190)")
    void hydraDefaultsToFive() {
        String hydra = addMonster("Hydra", "MM", "Huge", "Monstrosity", 172, 15, null);
        String guard = addTarget("Guard", "Medium", 200, 12);
        start(hydra, guard);

        assertThat(pool(participant(dmView(), hydra), "monster:heads").get("currentUses").asInt()).isEqualTo(5);
        assertThat(components(hydra)).containsExactly("Bite", "Bite", "Bite", "Bite", "Bite");
        // Five Bites: 10 + 8 hits AC 12 for 1d10 5 + 5 = 10 piercing each
        dice.script(10, 5, 10, 5, 10, 5, 10, 5, 10, 5);
        expect(HttpStatus.OK, monsterAction(hydra, "Multiattack", guard));
        assertThat(participant(dmView(), guard).get("hpCurrent").asInt()).isEqualTo(150);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool",
                Map.of("participantId", hydra, "poolId", "monster:heads", "option", "lose"), dm.accessToken()));
        assertThat(pool(participant(dmView(), hydra), "monster:heads").get("currentUses").asInt()).isEqualTo(4);
        assertThat(components(hydra)).hasSize(4);
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A count of heads for a monster without heads is refused with a 400")
    void headsRefusedForOthers() {
        Monster plain = monsterRepository.save(monster("Bandit", "IT", "Medium", "Humanoid", 11, 12).build());
        ResponseEntity<String> refused = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/participants",
                Map.of("participantType", "MONSTER", "monsterId", plain.getId(), "displayName", "Bandit", "heads", 3),
                dm.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("has no heads to count");
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

    // Around the table of four back to the mound, whose turn's start rolls these
    private void nextRound(int... moundTurnRolls) {
        nextTurn();
        nextTurn();
        nextTurn();
        dice.script(moundTurnRolls);
        nextTurn();
    }

    private static Monster.MonsterBuilder monster(String name, String source, String size, String type, int hp,
                                                  int ac) {
        return Monster.builder().name(name).source(source).size(size).type(type).challengeRating("1")
                .hitPoints(hp).armourClass(ac).strength(10).dexterity(10).constitution(10).intelligence(10)
                .wisdom(10).charisma(10);
    }

    // The monster with the action templates of the bundled definitions, with the heads the DM chose, if any
    private String addMonster(String name, String source, String size, String type, int hp, int ac, Integer heads) {
        Monster monster = monster(name, source, size, type, hp, ac).build();
        for (JsonNode definition : definitions) {
            if (name.equals(definition.path("monsterName").asString(""))
                    && source.equals(definition.path("source").asString(""))) {
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                Map<String, Object> request = new HashMap<>(Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", name));
                if (heads != null) {
                    request.put("heads", heads);
                }
                return idOf(addParticipant(dm.accessToken(), encounterId, request), name);
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

    private List<String> components(String participantId) {
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/multiattack-options?participantId=" + participantId, null, dm.accessToken()));
        List<String> names = new ArrayList<>();
        options.get(0).get("components").forEach(c -> names.add(c.get("name").asString()));
        return names;
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.path("resourcePoolsCurrent")) {
            if (poolId.equals(pool.path("poolId").asString())) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId);
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
