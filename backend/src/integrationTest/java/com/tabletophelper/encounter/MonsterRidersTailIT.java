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
 * Monster riders, steps 6 and 7 (D204), through the API with the bundled definitions: a giant toad grapples a halfling,
 * swallows it and digests it at the start of its turns until it dies and the halfling crawls out prone; a froghemoth
 * swallows a death knight, which can't be reached from outside, and regurgitates it after enough damage from inside; a
 * gelatinous cube engulfs a guard, who escapes with a Strength check; a cockatrice's bite petrifies over two saves; a
 * shadow drains a weak guard's Strength to death; and a ghost possesses a guard until the body drops. Every creature's
 * abilities are 10 unless the test says so, so its saves and checks are +0.
 */
class MonsterRidersTailIT extends IntegrationTest {

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
    @DisplayName("A giant toad grapples a halfling, swallows it with a bite at advantage, digests it with 3d6 acid at "
            + "the start of its turns, and dies, the halfling crawling out prone (MM p.329)")
    void giantToad() {
        String toad = addMonster(monster("Giant Toad", "MM", "Large", "Beast", 39, 11), "Giant Toad");
        String halfling = addTarget("Halfling", "Small", "Humanoid", 40, 12, 10);
        start(toad, halfling);

        // The Swallow is refused on a creature the toad isn't grappling
        assertThat(monsterAction(toad, "Swallow", halfling).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // 15 + 4 hits AC 12; 1d10 4 + 2 piercing, 1d10 3 poison; grappled and restrained
        dice.script(15, 4, 3);
        expect(HttpStatus.OK, monsterAction(toad, "Bite", halfling));
        assertThat(conditions(participant(dmView(), halfling))).containsExactlyInAnyOrder("grappled", "restrained");
        // Restrained, the halfling is bitten at advantage: two d20s; 1d10 2 + 2, 1d10 1
        dice.script(15, 12, 2, 1);
        expect(HttpStatus.OK, monsterAction(toad, "Swallow", halfling));
        JsonNode swallowed = participant(dmView(), halfling);
        assertThat(conditions(swallowed)).containsExactlyInAnyOrder("swallowed", "blinded", "restrained");
        assertThat(swallowed.get("hpCurrent").asInt()).isEqualTo(40 - 9 - 5);

        // The toad's next turn: 3d6 acid
        dice.script(3, 3, 3);
        nextTurn();
        nextTurn();
        assertThat(participant(dmView(), halfling).get("hpCurrent").asInt()).isEqualTo(26 - 9);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", toad, "amount", 100), dm.accessToken()));
        assertThat(conditions(participant(dmView(), halfling))).containsExactly("prone");
        assertThat(descriptions(dmLog())).contains("Halfling escapes from Giant Toad's corpse using 5 feet of "
                + "movement, exiting prone; it is prone.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A froghemoth swallows a death knight, which nothing outside can reach; 45 damage from inside makes "
            + "the froghemoth fail its DC 20 Constitution save at the turn's end and regurgitate it prone (VGM p.145)")
    void froghemoth() {
        String froghemoth = addMonster(monster("Froghemoth", "VGM", "Huge", "Monstrosity", 184, 14), "Froghemoth");
        String knight = addMonster(monster("Death Knight", "MM", "Medium", "Undead", 180, 20), "Death Knight");
        String cockatrice = addMonster(monster("Cockatrice", "MM", "Small", "Monstrosity", 27, 11), "Cockatrice");
        start(froghemoth, knight, cockatrice);

        // 15 + 10 hits AC 20; 3d10 5 + 5 + 5 + 6 = 21 piercing; a Medium creature, swallowed
        dice.script(15, 5, 5, 5);
        expect(HttpStatus.OK, monsterAction(froghemoth, "Bite", knight));
        assertThat(conditions(participant(dmView(), knight))).containsExactlyInAnyOrder("swallowed", "blinded",
                "restrained");
        // Nothing outside reaches it
        assertThat(monsterAction(cockatrice, "Bite", knight).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Blinded, the knight attacks from inside at disadvantage: 15 and 14 + 11 hit AC 14; 1d8 8 + 5, 4d8 8 x 4
        dice.script(15, 14, 8, 8, 8, 8, 8);
        expect(HttpStatus.OK, monsterAction(knight, "Longsword", froghemoth));
        assertThat(participant(dmView(), froghemoth).get("hpCurrent").asInt()).isEqualTo(184 - 45);

        // The froghemoth's turn ends: its Constitution save, 5 + 0, fails DC 20
        dice.script(5);
        nextTurn();
        JsonNode freed = participant(dmView(), knight);
        assertThat(conditions(freed)).containsExactly("prone");
        assertThat(descriptions(dmLog())).anyMatch(line -> line.startsWith("Froghemoth took 45 damage from inside it "
                + "this turn (Froghemoth's Bite (swallowed)) and makes a Constitution save: d20(5)"));
        assertThat(descriptions(dmLog())).contains("Froghemoth regurgitates Death Knight, which lands within 10 feet "
                + "of it; it is prone.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A gelatinous cube engulfs a guard on a failed DEX save: 3d6 acid, restrained while engulfed, and out "
            + "with a DC 12 Strength check made as an action (MM p.242)")
    void gelatinousCube() {
        String cube = addMonster(monster("Gelatinous Cube", "MM", "Large", "Ooze", 84, 6), "Gelatinous Cube");
        String guard = addTarget("Guard", "Medium", "Humanoid", 40, 16, 10);
        start(cube, guard);

        // 3d6 3 + 3 + 3 acid; DEX save 5 fails DC 12
        dice.script(3, 3, 3, 5);
        expect(HttpStatus.OK, monsterAction(cube, "Engulf", guard));
        JsonNode engulfed = participant(dmView(), guard);
        assertThat(conditions(engulfed)).containsExactlyInAnyOrder("engulfed", "restrained");
        assertThat(engulfed.get("hpCurrent").asInt()).isEqualTo(31);

        // A Strength check, 14 + 0, against DC 12 with its action
        dice.script(14);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/action-save",
                Map.of("participantId", guard, "conditionId", entry(engulfed, "engulfed").get("id").asString()),
                dm.accessToken()));
        assertThat(conditions(participant(dmView(), guard))).isEmpty();
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A cockatrice's bite: a failed DC 11 Constitution save restrains the guard as it begins to turn to "
            + "stone, and the save failed again at the end of its next turn petrifies it (MM p.42)")
    void cockatrice() {
        String cockatrice = addMonster(monster("Cockatrice", "MM", "Small", "Monstrosity", 27, 11), "Cockatrice");
        String guard = addTarget("Guard", "Medium", "Humanoid", 40, 12, 10);
        start(cockatrice, guard);

        // 15 + 3 hits AC 12; 1d4 2 + 1 piercing; CON save 4 fails DC 11
        dice.script(15, 2, 4);
        expect(HttpStatus.OK, monsterAction(cockatrice, "Bite", guard));
        assertThat(conditions(participant(dmView(), guard))).containsExactly("restrained");

        // The cockatrice's turn ends, then the guard's: its save, 6 + 0, fails
        nextTurn();
        dice.script(6);
        nextTurn();
        assertThat(conditions(participant(dmView(), guard))).containsExactly("petrified");
        assertThat(descriptions(dmLog())).anyMatch(line -> line.contains("Petrified for 24 hours."));
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A shadow's Strength Drain takes a Strength 3 guard's last 3 points, and the guard dies (MM p.269)")
    void shadow() {
        String shadow = addMonster(monster("Shadow", "MM", "Medium", "Undead", 16, 12), "Shadow");
        String guard = addTarget("Guard", "Medium", "Humanoid", 40, 12, 3);
        start(shadow, guard);

        // 15 + 4 hits AC 12; 2d6 2 + 2 + 2 necrotic; 1d4 3 Strength
        dice.script(15, 2, 2, 3);
        expect(HttpStatus.OK, monsterAction(shadow, "Strength Drain", guard));
        JsonNode drained = participant(dmView(), guard);
        assertThat(drained.get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(descriptions(dmLog())).contains("Guard is killed outright by Shadow's Strength Drain (its STR "
                + "reduced to 0).");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A ghost possesses a guard on a failed DC 13 Charisma save and can't be targeted; when the body drops "
            + "to 0 hit points the possession ends and the ghost reappears (MM p.147)")
    void ghost() {
        String ghost = addMonster(monster("Ghost", "MM", "Medium", "Undead", 45, 11), "Ghost");
        String cockatrice = addMonster(monster("Cockatrice", "MM", "Small", "Monstrosity", 27, 11), "Cockatrice");
        String guard = addTarget("Guard", "Medium", "Humanoid", 40, 12, 10);
        start(ghost, cockatrice, guard);

        // CHA save 5 fails DC 13
        dice.script(5);
        expect(HttpStatus.OK, monsterAction(ghost, "Possession", guard));
        assertThat(conditions(participant(dmView(), guard))).containsExactly("possessed");
        assertThat(conditions(participant(dmView(), ghost))).containsExactly("possessing");
        ResponseEntity<String> refused = monsterAction(cockatrice, "Bite", ghost);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("inside the body it possesses");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", guard, "amount", 40), dm.accessToken()));
        assertThat(conditions(participant(dmView(), ghost))).isEmpty();
        assertThat(descriptions(dmLog())).contains("Ghost reappears within 5 feet of the body it possessed");
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

    // The monster with the action templates the bundled definitions give it, added under this name
    private String addMonster(Monster.MonsterBuilder builder, String displayName) {
        Monster monster = builder.build();
        for (JsonNode definition : definitions) {
            if (monster.getName().equals(definition.path("monsterName").asString(""))
                    && monster.getSource().equals(definition.path("source").asString(""))) {
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", displayName)), displayName);
            }
        }
        throw new AssertionError(monster.getName() + " has no definition");
    }

    // A creature with no actions of its own, to be the target
    private String addTarget(String name, String size, String type, int hp, int ac, int strength) {
        Monster saved = monsterRepository.save(monster(name + " Target", "IT", size, type, hp, ac)
                .strength(strength).build());
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
