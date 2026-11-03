package com.tabletophelper.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The character commands over HTTP on PostgreSQL (D158), which replaced the PUT. Each is the owner's only, takes the
 * character's row lock and answers with the character as the server then works it out; resting and levelling wait
 * while the character is in an Active or Paused encounter (D166). Aria is a Tiefling Fighter 3 with CON 14 (28 hit
 * points) in the DM's campaign; Bram is a Tiefling Warlock 2 / Wizard 1 with CON 14 (23 hit points), Hellish Rebuke
 * once a long rest and the pact and wizard slots of both classes. Since R6 workstream F a warlock learns its spells
 * through its levels' Pending Choices rather than the interim known-spells command (D159).
 */
class CharacterCommandsIT extends IntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;
    private static final String LOCK_TIMEOUT = "Another change is still in progress. Please try again in a moment.";

    @Autowired private ItemRepository itemRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private DataSource dataSource;

    private final ExecutorService requests = Executors.newFixedThreadPool(2);

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID raceId;
    private UUID fighterId;
    private UUID warlockId;
    private UUID wizardId;
    private String aria;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        raceId = insertRace("Tiefling");
        fighterId = insertClass("Fighter", 10);
        warlockId = insertCaster("Warlock", 8, "CHA", true);
        wizardId = insertCaster("Wizard", 6, "INT", false);
        aria = create(Map.of("name", "Aria", "classId", fighterId, "level", 3, "campaignId", campaignId));
    }

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    @Test
    @DisplayName("The sheet's commands change only what they name and answer with the character the server works out")
    void sheetCommands() {
        UUID longsword = itemRepository.save(Item.builder().name("Longsword").type("Melee Weapon").subtype("martial")
                .damageDice("1d8").damageType("slashing").source("PHB").build()).getId();

        JsonNode details = ok(HttpMethod.PATCH, aria, "/details", Map.of("name", "Aria Swift", "notes", "Owes 10 gp"));
        assertThat(details.get("name").asText()).isEqualTo("Aria Swift");
        assertThat(details.get("notes").asText()).isEqualTo("Owes 10 gp");
        assertThat(details.get("hpMax").asInt()).isEqualTo(28);

        Map<String, Object> noCampaign = new HashMap<>();
        noCampaign.put("campaignId", null);
        assertThat(ok(HttpMethod.PUT, aria, "/campaign", noCampaign).get("campaignId").isNull()).isTrue();
        assertThat(ok(HttpMethod.PUT, aria, "/campaign", Map.of("campaignId", campaignId)).get("campaignId").asText())
                .isEqualTo(campaignId.toString());

        JsonNode inventory = ok(HttpMethod.PUT, aria, "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Heirloom blade", "quantity", 1, "itemId", longsword),
                        Map.of("name", "Torch", "quantity", 3)),
                "currency", Map.of("cp", 0, "sp", 5, "ep", 0, "gp", 12, "pp", 0)));
        assertThat(inventory.get("equipment").get(0).get("itemId").asText()).isEqualTo(longsword.toString());
        assertThat(inventory.get("currency").get("gp").asInt()).isEqualTo(12);
        assertThat(error(command(HttpMethod.PUT, aria, "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Vorpal Sword", "itemId", UUID.randomUUID())),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0))), HttpStatus.BAD_REQUEST))
                .isEqualTo("The item Vorpal Sword is linked to doesn't exist");

        JsonNode temporary = hp("TEMPORARY", 5);
        assertThat(temporary.get("hpTemp").asInt()).isEqualTo(5);
        JsonNode damaged = hp("DAMAGE", 8);
        assertThat(damaged.get("hpTemp").asInt()).isZero();
        assertThat(damaged.get("hpCurrent").asInt()).isEqualTo(25);
        assertThat(hp("HEALING", 50).get("hpCurrent").asInt()).isEqualTo(28);

        assertThat(error(command(HttpMethod.POST, aria, "/spell-slots/1/spend", null), HttpStatus.NOT_FOUND))
                .isEqualTo("Aria Swift has no level 1 spell slots");
    }

    @Test
    @DisplayName("Slots and pools are spent and got back; a short rest spends the dice entered or rolled and says what each rolled, and a long rest restores the largest first")
    void restsAndUses() {
        String bram = create(Map.of("name", "Bram", "classId", warlockId, "level", 3, "multiclassClassEntries",
                List.of(Map.of("classId", warlockId, "level", 2), Map.of("classId", wizardId, "level", 1)),
                "spellsKnown", List.of(Map.of("name", "Hellish Rebuke", "level", 1, "source", "race:Tiefling",
                        "usesPerLongRest", 1))));
        JsonNode created = get(bram);
        assertThat(created.get("hpMax").asInt()).isEqualTo(23);
        assertThat(created.get("spellSlots").toString()).isEqualTo("{\"1\":{\"max\":2,\"used\":0},\"pact_1\":{\"max\":2,\"used\":0}}");

        ok(HttpMethod.POST, bram, "/spell-slots/pact_1/spend", null);
        ok(HttpMethod.POST, bram, "/spell-slots/1/spend", null);
        ok(HttpMethod.POST, bram, "/spell-slots/1/spend", null);
        assertThat(ok(HttpMethod.POST, bram, "/spell-slots/1/restore", null).get("spellSlots").get("1").get("used")
                .asInt()).isEqualTo(1);
        Map<String, Object> rebuke = Map.of("poolId", "innate:hellish-rebuke");
        assertThat(ok(HttpMethod.POST, bram, "/pools/spend", rebuke).get("resourcePools").get(0).get("currentUses")
                .asInt()).isZero();
        assertThat(error(command(HttpMethod.POST, bram, "/pools/spend", rebuke), HttpStatus.CONFLICT))
                .isEqualTo("Hellish Rebuke has 0 of 1 uses left, not 1");
        ok(HttpMethod.POST, bram, "/hp", Map.of("kind", "DAMAGE", "amount", 15));

        dice.script(3);
        JsonNode shortRest = ok(HttpMethod.POST, bram, "/short-rest", Map.of("hitDice", List.of(
                Map.of("className", "Warlock", "roll", 6), Map.of("className", "Wizard"))));
        assertThat(shortRest.get("rolls").toString()).isEqualTo("[{\"className\":\"Warlock\",\"dieSize\":8,\"roll\":6,"
                + "\"rolledByServer\":false,\"healing\":8},{\"className\":\"Wizard\",\"dieSize\":6,\"roll\":3,"
                + "\"rolledByServer\":true,\"healing\":5}]");
        JsonNode rested = shortRest.get("character");
        // (6 + 2) + (3 + 2) on top of 8
        assertThat(rested.get("hpCurrent").asInt()).isEqualTo(21);
        assertThat(rested.get("hitDice").toString())
                .isEqualTo("{\"Warlock\":{\"dieSize\":8,\"total\":2,\"remaining\":1},\"Wizard\":{\"dieSize\":6,\"total\":1,\"remaining\":0}}");
        assertThat(rested.get("spellSlots").toString()).isEqualTo("{\"1\":{\"max\":2,\"used\":1},\"pact_1\":{\"max\":2,\"used\":0}}");
        assertThat(rested.get("resourcePools").get(0).get("currentUses").asInt()).isZero();
        assertThat(dice.remaining()).isZero();

        ok(HttpMethod.POST, bram, "/hp", Map.of("kind", "TEMPORARY", "amount", 4));
        JsonNode longRest = ok(HttpMethod.POST, bram, "/long-rest", null);
        assertThat(longRest.get("hpCurrent").asInt()).isEqualTo(23);
        assertThat(longRest.get("hpTemp").asInt()).isZero();
        // Three Hit Dice bring one back, the warlock's d8 before the wizard's d6
        assertThat(longRest.get("hitDice").toString())
                .isEqualTo("{\"Warlock\":{\"dieSize\":8,\"total\":2,\"remaining\":2},\"Wizard\":{\"dieSize\":6,\"total\":1,\"remaining\":0}}");
        assertThat(longRest.get("spellSlots").toString()).isEqualTo("{\"1\":{\"max\":2,\"used\":0},\"pact_1\":{\"max\":2,\"used\":0}}");
        assertThat(longRest.get("resourcePools").get(0).get("currentUses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("A wizard copies spells into its spellbook and prepares from it; the spells a warlock's level owes are checked against its list")
    void spellCommands() {
        spell("Magic Missile", 1, "[\"Sorcerer\",\"Wizard\"]");
        spell("Shield", 1, "[\"Sorcerer\",\"Wizard\",\"Warlock (The Hexblade)\"]");
        spell("Fireball", 3, "[\"Sorcerer\",\"Wizard\"]");
        spell("Hex", 1, "[\"Warlock\"]");
        spell("Armor of Agathys", 1, "[\"Warlock\"]");
        spell("Eldritch Blast", 0, "[\"Warlock\"]");
        spell("Minor Illusion", 0, "[\"Warlock\",\"Wizard\"]");
        String bram = create(Map.of("name", "Bram", "classId", warlockId, "level", 3, "multiclassClassEntries",
                List.of(Map.of("classId", warlockId, "level", 2), Map.of("classId", wizardId, "level", 1))));

        ok(HttpMethod.POST, bram, "/spellbook", Map.of("spells", List.of("Magic Missile", "Shield")));
        JsonNode prepared = ok(HttpMethod.PUT, bram, "/spells/prepared",
                Map.of("className", "Wizard", "spells", List.of("Shield")));
        assertThat(prepared.get("spellsKnown").toString()).isEqualTo("[{\"name\":\"Magic Missile\",\"level\":1,"
                + "\"source\":\"class:Wizard\",\"prepared\":false},{\"name\":\"Shield\",\"level\":1,"
                + "\"source\":\"class:Wizard\",\"prepared\":true}]");
        assertThat(error(command(HttpMethod.POST, bram, "/spellbook", Map.of("spells", List.of("Fireball"))),
                HttpStatus.BAD_REQUEST))
                .isEqualTo("A level 1 Wizard casts spells up to level 1, so can't take Fireball (level 3)");
        JsonNode removed = ok(HttpMethod.DELETE, bram, "/spellbook", Map.of("spells", List.of("Magic Missile")));
        assertThat(removed.get("spellsKnown")).hasSize(1);

        JsonNode owed = get(bram);
        ok(HttpMethod.POST, bram, "/apply-choices", Map.of("choiceId", choiceId(owed, "CANTRIPS", 1),
                "spells", List.of("Eldritch Blast", "Minor Illusion")));
        assertThat(error(command(HttpMethod.POST, bram, "/apply-choices", Map.of("choiceId",
                choiceId(owed, "SPELLS", 1), "spells", List.of("Hex", "Magic Missile"))), HttpStatus.BAD_REQUEST))
                .isEqualTo("Magic Missile isn't on the Warlock spell list");
        JsonNode known = ok(HttpMethod.POST, bram, "/apply-choices", Map.of("choiceId", choiceId(owed, "SPELLS", 1),
                "spells", List.of("Hex", "Armor of Agathys")));
        assertThat(known.get("spellsKnown").toString()).contains("\"name\":\"Eldritch Blast\",\"level\":0,"
                + "\"source\":\"class:Warlock\"", "\"name\":\"Hex\",\"level\":1,\"source\":\"class:Warlock\"");
        // The interim command is gone
        assertThat(command(HttpMethod.PUT, bram, "/spells/known", Map.of("className", "Warlock",
                "spells", List.of("Hex"))).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("Every command is the owner's: another user gets 403 and a character that doesn't exist 404, and neither changes anything")
    void onlyTheOwner() {
        RegisteredUser stranger = register("bram");
        String nobody = UUID.randomUUID().toString();
        Map<String, Object> noCampaign = new HashMap<>();
        noCampaign.put("campaignId", null);
        Map<String, Map.Entry<HttpMethod, Object>> commands = new LinkedHashMap<>();
        commands.put("/details", Map.entry(HttpMethod.PATCH, Map.of("notes", "Mine now")));
        commands.put("/campaign", Map.entry(HttpMethod.PUT, noCampaign));
        commands.put("/inventory", Map.entry(HttpMethod.PUT, Map.of("equipment", List.of(),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0))));
        commands.put("/hp", Map.entry(HttpMethod.POST, Map.of("kind", "DAMAGE", "amount", 5)));
        commands.put("/spell-slots/1/spend", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/spell-slots/1/restore", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/pools/spend", Map.entry(HttpMethod.POST, Map.of("poolId", "class:fighter-action-surge")));
        commands.put("/pools/recover", Map.entry(HttpMethod.POST, Map.of("poolId", "class:fighter-action-surge")));
        commands.put("/short-rest", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/long-rest", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/spells/prepared", Map.entry(HttpMethod.PUT, Map.of("className", "Wizard", "spells", List.of())));
        commands.put("/spellbook", Map.entry(HttpMethod.POST, Map.of("spells", List.of("Shield"))));
        commands.put("/level-up", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/level-down", Map.entry(HttpMethod.POST, Map.of()));
        commands.put("/apply-choices", Map.entry(HttpMethod.POST, Map.of("choiceId", UUID.randomUUID())));
        commands.put("", Map.entry(HttpMethod.DELETE, Map.of()));
        int version = version(aria);

        commands.forEach((path, command) -> {
            var asStranger = exchange(command.getKey(), "/api/characters/" + aria + path, command.getValue(),
                    stranger.accessToken());
            assertThat(asStranger.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(json(asStranger).get("error").asText()).as(path).isEqualTo("You do not own this character");
            var unknown = exchange(command.getKey(), "/api/characters/" + nobody + path, command.getValue(),
                    player.accessToken());
            assertThat(unknown.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(json(unknown).get("error").asText()).as(path).isEqualTo("Character not found");
        });
        var removeFromSpellbook = exchange(HttpMethod.DELETE, "/api/characters/" + aria + "/spellbook",
                Map.of("spells", List.of("Shield")), stranger.accessToken());
        assertThat(removeFromSpellbook.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(version(aria)).isEqualTo(version);
        assertThat(get(aria).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("Rests and levelling wait while the character is in an Active or Paused encounter, which the sheet shows; other commands go on")
    void restsAndLevellingWaitForEncounters() {
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria"));
        hp("DAMAGE", 10);

        // Preparing: the character may rest, but not be deleted
        assertThat(get(aria).get("inLiveEncounter").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.POST, aria, "/short-rest", null).get("character").get("hpCurrent").asInt())
                .isEqualTo(18);
        assertThat(error(exchange(HttpMethod.DELETE, "/api/characters/" + aria, null, player.accessToken()),
                HttpStatus.CONFLICT)).isEqualTo("Cannot delete a character that is in an active encounter");

        startEncounter(dm.accessToken(), encounterId);
        assertThat(get(aria).get("inLiveEncounter").asBoolean()).isTrue();
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("/short-rest", "take a short rest");
        refusals.put("/long-rest", "take a long rest");
        refusals.put("/level-up", "level up");
        refusals.put("/level-down", "level down");
        refusals.put("/apply-choices", "make the choices its levels bring");
        refusals.forEach((path, doing) -> assertThat(error(command(HttpMethod.POST, aria, path,
                path.equals("/apply-choices") ? Map.of("choiceId", UUID.randomUUID()) : Map.of()),
                HttpStatus.CONFLICT)).as(path)
                .isEqualTo("Aria is in an encounter that is under way, so can't " + doing + " until it ends"));
        assertThat(hp("HEALING", 1).get("hpCurrent").asInt()).isEqualTo(19);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/pause", null,
                dm.accessToken()));
        assertThat(error(command(HttpMethod.POST, aria, "/long-rest", null), HttpStatus.CONFLICT))
                .isEqualTo("Aria is in an encounter that is under way, so can't take a long rest until it ends");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end", null,
                dm.accessToken()));
        assertThat(get(aria).get("inLiveEncounter").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.POST, aria, "/long-rest", null).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("A short rest with a roll outside the die, more dice than remain or dice of no class is a 400 and changes nothing")
    void badRollsAre400() {
        hp("DAMAGE", 10);
        int version = version(aria);

        assertThat(error(command(HttpMethod.POST, aria, "/short-rest",
                Map.of("hitDice", List.of(Map.of("className", "Fighter", "roll", 11)))), HttpStatus.BAD_REQUEST))
                .isEqualTo("A d10 rolls 1 to 10, not 11");
        assertThat(error(command(HttpMethod.POST, aria, "/short-rest",
                Map.of("hitDice", List.of(Map.of("className", "Fighter", "roll", 0)))), HttpStatus.BAD_REQUEST))
                .isEqualTo("A d10 rolls 1 to 10, not 0");
        List<Map<String, Object>> fourDice = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fourDice.add(Map.of("className", "Fighter", "roll", 5));
        }
        assertThat(error(command(HttpMethod.POST, aria, "/short-rest", Map.of("hitDice", fourDice)),
                HttpStatus.BAD_REQUEST)).isEqualTo("Aria has 3 Fighter Hit Dice left, not 4");
        assertThat(error(command(HttpMethod.POST, aria, "/short-rest",
                Map.of("hitDice", List.of(Map.of("className", "Wizard", "roll", 3)))), HttpStatus.BAD_REQUEST))
                .isEqualTo("Aria has no Wizard Hit Dice");

        JsonNode unchanged = get(aria);
        assertThat(unchanged.get("hpCurrent").asInt()).isEqualTo(18);
        assertThat(unchanged.get("hitDice").get("Fighter").get("remaining").asInt()).isEqualTo(3);
        assertThat(version(aria)).isEqualTo(version);
    }

    @Test
    @DisplayName("Two commands sent at once to one character both apply, the second from the first's committed state")
    void commandsQueueBehindTheRowLock() throws Exception {
        List<CompletableFuture<ResponseEntity<String>>> calls;
        Connection lock = lockCharacterRow();
        try {
            calls = List.of(damageAsync(5), damageAsync(7));
            awaitRequestsWaitingForLock(2);
        } finally {
            release(lock);
        }

        List<Integer> answeredHp = new ArrayList<>();
        for (CompletableFuture<ResponseEntity<String>> call : calls) {
            answeredHp.add(expect(HttpStatus.OK, call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get("hpCurrent").asInt());
        }

        assertThat(get(aria).get("hpCurrent").asInt()).isEqualTo(28 - 5 - 7);
        assertThat(answeredHp).contains(16).containsAnyOf(23, 21);
    }

    @Test
    @DisplayName("A command that waits more than five seconds for the character's row lock gets 409 and changes nothing")
    void commandWaitingTooLongIsRefused() throws Exception {
        ResponseEntity<String> response;
        long waitedMillis;
        Connection lock = lockCharacterRow();
        try {
            long start = System.nanoTime();
            response = damageAsync(5).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            release(lock);
        }

        assertThat(error(response, HttpStatus.CONFLICT)).isEqualTo(LOCK_TIMEOUT);
        assertThat(waitedMillis).isBetween(4_500L, 15_000L);
        assertThat(get(aria).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("Each command raises the character's version by one, and a stale copy can't overwrite a newer row")
    void theVersionCatchesAStaleWrite() {
        int before = version(aria);
        PlayerCharacter stale = characterRepository.findById(UUID.fromString(aria)).orElseThrow();

        hp("DAMAGE", 3);
        assertThat(version(aria)).isEqualTo(before + 1);

        stale.setNotes("Written from an old copy");
        assertThatThrownBy(() -> characterRepository.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        JsonNode current = get(aria);
        assertThat(current.get("hpCurrent").asInt()).isEqualTo(25);
        assertThat(current.get("notes").isNull()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String create(Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("raceId", raceId,
                "strength", 16, "dexterity", 14, "constitution", 14, "intelligence", 14, "wisdom", 10,
                "charisma", 14));
        body.putAll(details);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asText();
    }

    // The ID of the character's pending choice of the type at the character level (D159)
    private static String choiceId(JsonNode character, String type, int characterLevel) {
        for (JsonNode choice : character.get("pendingChoices")) {
            if (choice.get("type").asText().equals(type) && choice.get("characterLevel").asInt() == characterLevel) {
                return choice.get("id").asText();
            }
        }
        throw new AssertionError("No pending " + type + " at level " + characterLevel);
    }

    private void spell(String name, int level, String classes) {
        spellRepository.save(Spell.builder().name(name).level(level).school("Evocation").source("PHB").classes(classes)
                .build());
    }

    private ResponseEntity<String> command(HttpMethod method, String characterId, String path, Object body) {
        return exchange(method, "/api/characters/" + characterId + path, body, player.accessToken());
    }

    private JsonNode ok(HttpMethod method, String characterId, String path, Object body) {
        return expect(HttpStatus.OK, command(method, characterId, path, body));
    }

    private JsonNode hp(String kind, int amount) {
        return ok(HttpMethod.POST, aria, "/hp", Map.of("kind", kind, "amount", amount));
    }

    private JsonNode get(String characterId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
    }

    private String error(ResponseEntity<String> response, HttpStatus status) {
        return expect(status, response).get("error").asText();
    }

    private int version(String characterId) {
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM player_characters WHERE id = ?",
                Integer.class, UUID.fromString(characterId));
        return version != null ? version : -1;
    }

    private CompletableFuture<ResponseEntity<String>> damageAsync(int amount) {
        return CompletableFuture.supplyAsync(() -> command(HttpMethod.POST, aria, "/hp",
                Map.of("kind", "DAMAGE", "amount", amount)), requests);
    }

    /** Opens a transaction holding the character's row lock until {@link #release}, as a slow command would. */
    private Connection lockCharacterRow() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM player_characters WHERE id = ? FOR UPDATE")) {
            select.setObject(1, UUID.fromString(aria));
            select.executeQuery().close();
        } catch (SQLException e) {
            release(connection);
            throw e;
        }
        return connection;
    }

    private static void release(Connection lock) throws SQLException {
        try (lock) {
            lock.rollback();
        }
    }

    // Requests must queue well inside the five-second lock timeout, so this gives up after four
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
        throw new AssertionError(count + " requests did not reach the character's lock within 4s");
    }
}
