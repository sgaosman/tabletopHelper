package com.tabletophelper.character;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-back over HTTP on PostgreSQL (D166): what an encounter changed for each player character is applied to their
 * sheet as it stands when the DM completes the encounter with its results applied, or removes them mid-fight, so two
 * encounters add up and every total stays within its limits; temporary hit points are what was left; a character who
 * died is dead until the DM revives them, and one still Dying or Stable comes back alive at 0; and the DM may decline,
 * changing nothing. Completion locks the encounter's row, then the characters' in ID order. Aria is a Human Fighter 3
 * with CON 14 (28 hit points), and Bram a Human Wizard 3 with CON 14 (20 hit points, four level 1 and two level 2
 * slots, spell save DC 12) who knows Hellish Rebuke once a long rest; both are in the DM's campaign.
 */
class WriteBackIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;
    private static final String HELLISH_REBUKE = """
            {"spellName": "Hellish Rebuke", "spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "fire"}]}""";
    private static final String REBUKE_POOL = "innate:hellish-rebuke";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private DataSource dataSource;

    private final ExecutorService requests = Executors.newFixedThreadPool(2);

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID raceId;
    private UUID fighterId;
    private UUID wizardId;
    private Monster goblin;
    private String aria;
    private String bram;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        raceId = insertRace("Human");
        fighterId = insertClass("Fighter", 10);
        wizardId = insertCaster("Wizard", 6, "INT", false);
        goblin = monsterRepository.save(Monster.builder().name("Goblin").type("Humanoid").challengeRating("1/4")
                .source("MM").hitPoints(30).armourClass(15).dexterity(14).build());
        spellRepository.save(Spell.builder().name("Hellish Rebuke").level(1).source("PHB")
                .effectTemplate(SpellTemplate.parse(HELLISH_REBUKE)).build());
        aria = create(Map.of("name", "Aria", "classId", fighterId, "level", 3));
        bram = create(Map.of("name", "Bram", "classId", wizardId, "level", 3, "spellsKnown", List.of(Map.of(
                "name", "Hellish Rebuke", "level", 1, "source", "race:Tiefling", "usesPerLongRest", 1))));
    }

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    @Test
    @DisplayName("Two encounters Aria is in at once add up, each applied when it ends, with the temporary hit points she had left; the DM previews each first")
    void twoEncountersAddUp() {
        UUID first = encounterWith(aria);
        UUID second = encounterWith(aria);
        String inFirst = start(first).get(aria);
        String inSecond = start(second).get(aria);

        damage(first, inFirst, 10);
        damage(second, inSecond, 5);
        combat(second, "hp", Map.of("targetId", inSecond, "hpCurrent", 23, "hpTemp", 4));

        JsonNode firstPreview = preview(first).get(0);
        assertThat(firstPreview.get("characterId").asString()).isEqualTo(aria);
        assertThat(firstPreview.get("hpBefore").asInt()).isEqualTo(28);
        assertThat(firstPreview.get("hpAfter").asInt()).isEqualTo(18);
        assertThat(firstPreview.get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(firstPreview.get("deadAfter").asBoolean()).isFalse();
        // The preview changes nothing
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(28);

        end(first, true);
        JsonNode afterFirst = sheet(aria);
        assertThat(afterFirst.get("hpCurrent").asInt()).isEqualTo(18);
        assertThat(afterFirst.get("hpTemp").asInt()).isZero();
        assertThat(afterFirst.get("inLiveEncounter").asBoolean()).isTrue();

        JsonNode secondPreview = preview(second).get(0);
        assertThat(secondPreview.get("hpBefore").asInt()).isEqualTo(18);
        assertThat(secondPreview.get("hpAfter").asInt()).isEqualTo(13);
        assertThat(secondPreview.get("hpTempBefore").asInt()).isZero();
        assertThat(secondPreview.get("hpTempAfter").asInt()).isEqualTo(4);

        end(second, true);
        JsonNode afterBoth = sheet(aria);
        assertThat(afterBoth.get("hpCurrent").asInt()).isEqualTo(13);
        assertThat(afterBoth.get("hpTemp").asInt()).isEqualTo(4);
        assertThat(afterBoth.get("inLiveEncounter").asBoolean()).isFalse();
        assertThat(afterBoth.get("dead").asBoolean()).isFalse();
        assertThat(resultsApplied(first)).isTrue();
        assertThat(resultsApplied(second)).isTrue();
    }

    @Test
    @DisplayName("Hit points, slots and pools stay within their limits when the sheet changed during the fight too, and a pool spent in combat is written back")
    void totalsStayWithinTheirLimits() {
        UUID encounter = encounterWith(bram);
        String goblinId = idOf(addParticipant(dm.accessToken(), encounter,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")), "Goblin");
        String inFight = start(encounter).get(bram);

        // In combat: 10 damage, two level 1 slots and the innate Hellish Rebuke
        damage(encounter, inFight, 10);
        combat(encounter, "spell-slot/use", Map.of("participantId", inFight, "slotLevel", 1));
        combat(encounter, "spell-slot/use", Map.of("participantId", inFight, "slotLevel", 1));
        // 10 on 2d10, and the goblin's DEX save of 2 + 2 fails against DC 12
        dice.script(5, 5, 2);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounter
                + "/combat/cast-spell?actorId=" + inFight, Map.of("spellName", "Hellish Rebuke", "spellSource", "INNATE",
                "slotLevel", 0, "targetIds", List.of(goblinId)), dm.accessToken()));
        assertThat(cast.get("spellSource").asString()).isEqualTo("INNATE");
        // Meanwhile on the sheet: 15 damage, three level 1 slots and the same use
        command(bram, "/hp", Map.of("kind", "DAMAGE", "amount", 15));
        for (int i = 0; i < 3; i++) {
            command(bram, "/spell-slots/1/spend", null);
        }
        command(bram, "/pools/spend", Map.of("poolId", REBUKE_POOL));

        end(encounter, true);

        JsonNode after = sheet(bram);
        // 5 - 10 stops at 0, still alive; 3 + 2 slots stop at 4; 0 - 1 uses stops at 0
        assertThat(after.get("hpCurrent").asInt()).isZero();
        assertThat(after.get("dead").asBoolean()).isFalse();
        assertThat(after.get("spellSlots").toString()).isEqualTo("{\"1\":{\"max\":4,\"used\":4},\"2\":{\"max\":2,\"used\":0}}");
        assertThat(pool(after, REBUKE_POOL).get("currentUses").asInt()).isZero();
    }

    @Test
    @DisplayName("A pool use spent only in combat comes off the sheet, and a slot recovered on the sheet stays recovered")
    void changesApplyToTheSheetAsItStands() {
        command(bram, "/spell-slots/2/spend", null);
        UUID encounter = encounterWith(bram);
        String goblinId = idOf(addParticipant(dm.accessToken(), encounter,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")), "Goblin");
        String inFight = start(encounter).get(bram);
        dice.script(5, 5, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounter + "/combat/cast-spell?actorId="
                + inFight, Map.of("spellName", "Hellish Rebuke", "spellSource", "INNATE", "slotLevel", 0, "targetIds",
                List.of(goblinId)), dm.accessToken()));
        command(bram, "/spell-slots/2/restore", null);

        JsonNode preview = preview(encounter).get(0);
        assertThat(preview.get("pools").toString()).isEqualTo("[{\"poolId\":\"innate:hellish-rebuke\","
                + "\"displayName\":\"Hellish Rebuke\",\"maxUses\":1,\"currentBefore\":1,\"currentAfter\":0}]");
        assertThat(preview.get("slots")).isEmpty();
        end(encounter, true);

        JsonNode after = sheet(bram);
        assertThat(pool(after, REBUKE_POOL).get("currentUses").asInt()).isZero();
        assertThat(after.get("spellSlots").get("2").get("used").asInt()).isZero();
        assertThat(after.get("hpCurrent").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("A character who died is dead at 0: refused rests, healing and new encounters until the DM, and only the DM, revives them at 1 hit point")
    void deathAndRevival() {
        UUID encounter = encounterWith(aria);
        String inFight = start(encounter).get(aria);
        damage(encounter, inFight, 28);
        // Three failed death saves
        dice.script(2, 2, 2);
        for (int i = 0; i < 3; i++) {
            combat(encounter, "death-save", Map.of("participantId", inFight));
        }
        assertThat(participant(encounter, inFight).get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(preview(encounter).get(0).get("deadAfter").asBoolean()).isTrue();

        end(encounter, true);

        JsonNode dead = sheet(aria);
        assertThat(dead.get("dead").asBoolean()).isTrue();
        assertThat(dead.get("hpCurrent").asInt()).isZero();
        assertThat(error(command(aria, "/short-rest", null, HttpStatus.CONFLICT)))
                .isEqualTo("Aria is dead, so can't take a short rest until the DM revives them");
        assertThat(error(command(aria, "/long-rest", null, HttpStatus.CONFLICT)))
                .isEqualTo("Aria is dead, so can't take a long rest until the DM revives them");
        assertThat(error(command(aria, "/hp", Map.of("kind", "HEALING", "amount", 5), HttpStatus.CONFLICT)))
                .isEqualTo("Aria is dead, so can't be healed until the DM revives them");
        assertThat(error(command(aria, "/level-up", Map.of(), HttpStatus.CONFLICT)))
                .isEqualTo("Aria is dead, so can't level up until the DM revives them");
        UUID next = createEncounter(dm.accessToken(), campaignId);
        assertThat(error(exchange(HttpMethod.POST, "/api/encounters/" + next + "/participants",
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria"), dm.accessToken()),
                HttpStatus.CONFLICT)).isEqualTo("Aria is dead, so can't join an encounter until the DM revives them");

        // The player can't revive their own character, and nobody can revive one alive
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/revive", null, player.accessToken()),
                HttpStatus.FORBIDDEN)).isEqualTo("Only the DM of this character's campaign can adjust it");
        JsonNode revived = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + aria + "/revive",
                null, dm.accessToken()));
        assertThat(revived.get("dead").asBoolean()).isFalse();
        assertThat(revived.get("hpCurrent").asInt()).isEqualTo(1);
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/revive", null, dm.accessToken()),
                HttpStatus.CONFLICT)).isEqualTo("Aria isn't dead");
        assertThat(expect(HttpStatus.OK, command(aria, "/long-rest", null)).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("A character still Dying or Stable when the encounter ends is written back alive at 0 hit points")
    void dyingAndStableComeBackAlive() {
        UUID encounter = encounterWith(aria, bram);
        Map<String, String> inFight = start(encounter);
        damage(encounter, inFight.get(aria), 28);
        damage(encounter, inFight.get(bram), 20);
        // Three successful death saves make Bram stable
        dice.script(15, 15, 15);
        for (int i = 0; i < 3; i++) {
            combat(encounter, "death-save", Map.of("participantId", inFight.get(bram)));
        }
        assertThat(participant(encounter, inFight.get(aria)).get("lifeState").asString()).isEqualTo("DYING");
        assertThat(participant(encounter, inFight.get(bram)).get("lifeState").asString()).isEqualTo("STABLE");

        end(encounter, true);

        for (String character : List.of(aria, bram)) {
            JsonNode after = sheet(character);
            assertThat(after.get("hpCurrent").asInt()).as(character).isZero();
            assertThat(after.get("dead").asBoolean()).as(character).isFalse();
        }
        // Alive at 0, so a long rest waits for a hit point (PHB p.186), and healing brings it
        assertThat(error(command(aria, "/long-rest", null, HttpStatus.CONFLICT)))
                .isEqualTo("Aria needs at least 1 hit point to benefit from a long rest");
        assertThat(expect(HttpStatus.OK, command(aria, "/hp", Map.of("kind", "HEALING", "amount", 3)))
                .get("hpCurrent").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("When the DM declines, nothing is written back, pool uses included, and the choice is recorded")
    void theDmDeclines() {
        UUID encounter = encounterWith(aria, bram);
        String goblinId = idOf(addParticipant(dm.accessToken(), encounter,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")), "Goblin");
        Map<String, String> inFight = start(encounter);
        damage(encounter, inFight.get(aria), 12);
        combat(encounter, "spell-slot/use", Map.of("participantId", inFight.get(bram), "slotLevel", 2));
        dice.script(5, 5, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounter + "/combat/cast-spell?actorId="
                + inFight.get(bram), Map.of("spellName", "Hellish Rebuke", "spellSource", "INNATE", "slotLevel", 0,
                "targetIds", List.of(goblinId)), dm.accessToken()));
        int ariaVersion = version(aria);
        int bramVersion = version(bram);

        JsonNode ended = end(encounter, false);

        assertThat(ended.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(resultsApplied(encounter)).isFalse();
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(28);
        JsonNode bramAfter = sheet(bram);
        assertThat(bramAfter.get("spellSlots").get("2").get("used").asInt()).isZero();
        assertThat(pool(bramAfter, REBUKE_POOL).get("currentUses").asInt()).isEqualTo(1);
        assertThat(version(aria)).isEqualTo(ariaVersion);
        assertThat(version(bram)).isEqualTo(bramVersion);
    }

    @Test
    @DisplayName("A character removed while the encounter is Active takes its changes then, and the encounter's end leaves them alone; one removed while preparing takes none")
    void removedWhileActive() {
        UUID encounter = encounterWith(aria, bram);
        Map<String, String> inFight = start(encounter);
        damage(encounter, inFight.get(aria), 7);
        combat(encounter, "hp", Map.of("targetId", inFight.get(aria), "hpCurrent", 21, "hpTemp", 3));

        expect(HttpStatus.OK, exchange(HttpMethod.DELETE, "/api/encounters/" + encounter + "/participants/"
                + inFight.get(aria), null, dm.accessToken()));

        JsonNode removed = sheet(aria);
        assertThat(removed.get("hpCurrent").asInt()).isEqualTo(21);
        assertThat(removed.get("hpTemp").asInt()).isEqualTo(3);
        assertThat(removed.get("inLiveEncounter").asBoolean()).isFalse();
        // Back on the sheet, Aria may rest at once
        assertThat(expect(HttpStatus.OK, command(aria, "/long-rest", null)).get("hpCurrent").asInt()).isEqualTo(28);
        damage(encounter, inFight.get(bram), 4);
        end(encounter, true);
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(28);
        assertThat(sheet(bram).get("hpCurrent").asInt()).isEqualTo(16);

        UUID preparing = encounterWith(aria);
        command(aria, "/hp", Map.of("kind", "DAMAGE", "amount", 2));
        String added = participants(preparing).get(aria);
        expect(HttpStatus.OK, exchange(HttpMethod.DELETE, "/api/encounters/" + preparing + "/participants/" + added,
                null, dm.accessToken()));
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(26);
    }

    @Test
    @DisplayName("A character added while the encounter was prepared joins again with its sheet when it starts, so what changed before then isn't written back twice")
    void startingCopiesTheSheetAgain() {
        UUID encounter = encounterWith(aria);
        String added = participants(encounter).get(aria);
        command(aria, "/hp", Map.of("kind", "DAMAGE", "amount", 8));
        command(aria, "/hp", Map.of("kind", "TEMPORARY", "amount", 2));
        assertThat(participant(encounter, added).get("hpCurrent").asInt()).isEqualTo(28);

        String inFight = start(encounter).get(aria);
        JsonNode started = participant(encounter, inFight);
        assertThat(started.get("hpCurrent").asInt()).isEqualTo(20);
        assertThat(started.get("hpTemp").asInt()).isEqualTo(2);
        damage(encounter, inFight, 5);
        end(encounter, true);

        JsonNode after = sheet(aria);
        // 2 temporary absorbed 2 of the 5
        assertThat(after.get("hpCurrent").asInt()).isEqualTo(17);
        assertThat(after.get("hpTemp").asInt()).isZero();
    }

    @Test
    @DisplayName("The End dialog's preview is the DM's alone, for an encounter that can be completed")
    void previewIsTheDms() {
        UUID encounter = encounterWith(aria);
        assertThat(error(exchange(HttpMethod.GET, "/api/encounters/" + encounter + "/results", null,
                dm.accessToken()), HttpStatus.CONFLICT))
                .isEqualTo("Cannot complete an encounter that is still being prepared");
        start(encounter);
        assertThat(error(exchange(HttpMethod.GET, "/api/encounters/" + encounter + "/results", null,
                player.accessToken()), HttpStatus.FORBIDDEN)).isEqualTo("Only the DM can perform this action");
        assertThat(preview(encounter)).hasSize(1);
        end(encounter, true);
        assertThat(error(exchange(HttpMethod.GET, "/api/encounters/" + encounter + "/results", null,
                dm.accessToken()), HttpStatus.CONFLICT)).isEqualTo("The encounter is already completed");
    }

    @Test
    @DisplayName("Completion locks the encounter's row, then each character's in ID order, waiting for a character command that holds one")
    void completionLocksInOrder() throws Exception {
        UUID encounter = encounterWith(aria, bram);
        Map<String, String> inFight = start(encounter);
        damage(encounter, inFight.get(aria), 3);
        damage(encounter, inFight.get(bram), 4);
        List<String> byId = List.of(aria, bram).stream().sorted(Comparator.naturalOrder()).toList();
        String first = byId.get(0);
        String second = byId.get(1);

        // Holding the first character's row: completion has the encounter's row and waits, the second still free
        CompletableFuture<ResponseEntity<String>> ending;
        Connection lock = lockRow("player_characters", first);
        try {
            ending = CompletableFuture.supplyAsync(() -> exchange(HttpMethod.POST, "/api/encounters/" + encounter
                    + "/end", Map.of("applyResults", true), dm.accessToken()), requests);
            awaitRequestsWaitingForLock(1);
            assertThat(isLocked("encounters", encounter.toString())).isTrue();
            assertThat(isLocked("player_characters", second)).isFalse();
        } finally {
            release(lock);
        }
        expect(HttpStatus.OK, ending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(25);
        assertThat(sheet(bram).get("hpCurrent").asInt()).isEqualTo(16);

        // Holding the second: completion has the encounter's row and the first character's by the time it waits
        UUID again = encounterWith(aria, bram);
        Map<String, String> inSecondFight = start(again);
        damage(again, inSecondFight.get(aria), 1);
        lock = lockRow("player_characters", second);
        try {
            ending = CompletableFuture.supplyAsync(() -> exchange(HttpMethod.POST, "/api/encounters/" + again
                    + "/end", null, dm.accessToken()), requests);
            awaitRequestsWaitingForLock(1);
            assertThat(isLocked("encounters", again.toString())).isTrue();
            assertThat(isLocked("player_characters", first)).isTrue();
        } finally {
            release(lock);
        }
        expect(HttpStatus.OK, ending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(sheet(aria).get("hpCurrent").asInt()).isEqualTo(24);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String create(Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("raceId", raceId, "campaignId", campaignId,
                "strength", 16, "dexterity", 14, "constitution", 14, "intelligence", 14, "wisdom", 10,
                "charisma", 14));
        body.putAll(details);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();
    }

    // A new encounter in the campaign with these characters added
    private UUID encounterWith(String... characters) {
        UUID encounter = createEncounter(dm.accessToken(), campaignId);
        for (String character : characters) {
            addParticipant(dm.accessToken(), encounter,
                    Map.of("participantType", "PLAYER", "characterId", character, "displayName", "PC"));
        }
        return encounter;
    }

    // Starts the encounter and gives each character's participant ID
    private Map<String, String> start(UUID encounter) {
        startEncounter(dm.accessToken(), encounter);
        return participants(encounter);
    }

    private Map<String, String> participants(UUID encounter) {
        Map<String, String> byCharacter = new HashMap<>();
        for (JsonNode participant : encounterState(encounter).get("participants")) {
            if (!participant.get("characterId").isNull()) {
                byCharacter.put(participant.get("characterId").asString(), participant.get("id").asString());
            }
        }
        return byCharacter;
    }

    private JsonNode encounterState(UUID encounter) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounter, null, dm.accessToken()));
    }

    private JsonNode participant(UUID encounter, String participantId) {
        for (JsonNode participant : encounterState(encounter).get("participants")) {
            if (participant.get("id").asString().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("No participant " + participantId);
    }

    private static String idOf(JsonNode state, String displayName) {
        for (JsonNode participant : state.get("participants")) {
            if (participant.get("displayName").asString().equals(displayName)) {
                return participant.get("id").asString();
            }
        }
        throw new AssertionError("No participant named " + displayName);
    }

    private JsonNode combat(UUID encounter, String action, Object body) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounter + "/combat/" + action,
                body, dm.accessToken()));
    }

    private void damage(UUID encounter, String participantId, int amount) {
        combat(encounter, "damage", Map.of("targetId", participantId, "amount", amount));
    }

    private JsonNode preview(UUID encounter) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounter + "/results", null,
                dm.accessToken()));
    }

    private JsonNode end(UUID encounter, boolean applyResults) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounter + "/end",
                Map.of("applyResults", applyResults), dm.accessToken()));
    }

    private ResponseEntity<String> command(String character, String path, Object body) {
        return exchange(HttpMethod.POST, "/api/characters/" + character + path, body, player.accessToken());
    }

    private ResponseEntity<String> command(String character, String path, Object body, HttpStatus status) {
        ResponseEntity<String> response = command(character, path, body);
        assertThat(response.getStatusCode()).as(path).isEqualTo(status);
        return response;
    }

    private JsonNode sheet(String character) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + character, null,
                player.accessToken()));
    }

    private static JsonNode pool(JsonNode character, String poolId) {
        for (JsonNode pool : character.get("resourcePools")) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId);
    }

    private String error(ResponseEntity<String> response) {
        return json(response).get("error").asString();
    }

    private String error(ResponseEntity<String> response, HttpStatus status) {
        return expect(status, response).get("error").asString();
    }

    private Boolean resultsApplied(UUID encounter) {
        return jdbcTemplate.queryForObject("SELECT results_applied FROM encounters WHERE id = ?", Boolean.class,
                encounter);
    }

    private int version(String character) {
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM player_characters WHERE id = ?",
                Integer.class, UUID.fromString(character));
        return version != null ? version : -1;
    }

    /** Opens a transaction holding the row's lock until {@link #release}, as a slow character command would. */
    private Connection lockRow(String table, String id) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            select.setObject(1, UUID.fromString(id));
            select.executeQuery().close();
        } catch (SQLException e) {
            release(connection);
            throw e;
        }
        return connection;
    }

    // Whether another transaction holds the row's lock: taking it at once fails while one does
    private boolean isLocked(String table, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                select.setObject(1, UUID.fromString(id));
                select.executeQuery().close();
                return false;
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {
                    return true;
                }
                throw e;
            } finally {
                connection.rollback();
            }
        }
    }

    private static void release(Connection lock) throws SQLException {
        try (lock) {
            lock.rollback();
        }
    }

    // The request must reach the lock well inside the five-second lock timeout, so this gives up after four
    private void awaitRequestsWaitingForLock(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(count + " requests did not reach a character's lock within 4s");
    }
}
