package com.tabletophelper.encounter;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.maintenance.BackfillPendingChoicesJob;
import com.tabletophelper.maintenance.MaintenanceReport;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A warlock's Mystic Arcanum (PHB p.108) over HTTP on PostgreSQL: the choice Warlock 11 owes, learned through
 * apply-choices with a pool of one use, forgotten by levelling down; cast in an encounter with the arcanum's use rather
 * than a pact slot, refused once spent, written back to the sheet when the DM applies the results and restored by a
 * long rest; a multiclass character's arcana from its warlock levels; and the backfill owing an existing warlock's.
 * Aria is a Human warlock with CHA 16 in the DM's campaign.
 */
class MysticArcanumIT extends IntegrationTest {

    private static final String CIRCLE_OF_DEATH = """
            {"spellName": "Circle of Death", "spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON",
             "halfOnSave": true, "components": {"verbal": true, "somatic": true, "material": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "necrotic",
               "upcastScaling": {"additionalDicePerLevel": "2d6"}}]}""";

    @Autowired private SpellRepository spellRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private BackfillPendingChoicesJob backfillPendingChoicesJob;
    @Autowired private PlatformTransactionManager transactionManager;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID humanId;
    private UUID warlockId;
    private UUID sorcererId;

    @BeforeEach
    void insertReferenceData() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        humanId = insertRace("Human");
        warlockId = insertCaster("Warlock", 8, "CHA", true);
        sorcererId = insertCaster("Sorcerer", 6, "CHA", false);
        spellRepository.save(Spell.builder().name("Circle of Death").level(6).school("Necromancy").source("PHB")
                .castingTime("1 action").classes("[\"Sorcerer\",\"Warlock\",\"Wizard\"]")
                .effectTemplate(SpellTemplate.parse(CIRCLE_OF_DEATH)).build());
        spellRepository.save(Spell.builder().name("Heal").level(6).school("Evocation").source("PHB")
                .classes("[\"Cleric\",\"Druid\"]").build());
        spellRepository.save(Spell.builder().name("Plane Shift").level(7).school("Conjuration").source("PHB")
                .classes("[\"Cleric\",\"Druid\",\"Sorcerer\",\"Warlock\",\"Wizard\"]").build());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private JsonNode create(Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Aria", "raceId", humanId, "classId", warlockId,
                "strength", 10, "dexterity", 14, "constitution", 12, "intelligence", 10, "wisdom", 10,
                "charisma", 16));
        body.put("campaignId", campaignId);
        body.putAll(details);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()));
    }

    private ResponseEntity<String> apply(String characterId, Map<String, Object> answer) {
        return exchange(HttpMethod.POST, "/api/characters/" + characterId + "/apply-choices", answer,
                player.accessToken());
    }

    private JsonNode get(String characterId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
    }

    private static List<JsonNode> arcanumChoices(JsonNode character) {
        List<JsonNode> choices = new ArrayList<>();
        character.get("pendingChoices").forEach(c -> {
            if (c.get("type").asString().equals("MYSTIC_ARCANUM")) choices.add(c);
        });
        return choices;
    }

    private static List<String> arcanumSpells(JsonNode character) {
        List<String> names = new ArrayList<>();
        character.get("spellsKnown").forEach(s -> {
            if ("arcanum:Warlock".equals(s.path("source").asString(""))) names.add(s.get("name").asString());
        });
        return names;
    }

    private static JsonNode pool(JsonNode pools, String poolId) {
        for (JsonNode pool : pools) {
            if (pool.get("poolId").asString().equals(poolId)) return pool;
        }
        return null;
    }

    // Aria at Warlock 11 with Circle of Death as her 6th-level arcanum
    private String warlockWithArcanum() {
        JsonNode created = create(Map.of("level", 11));
        String id = created.get("id").asString();
        JsonNode choice = arcanumChoices(created).getFirst();
        expect(HttpStatus.OK, apply(id, Map.of("choiceId", choice.get("id").asString(),
                "spells", List.of("Circle of Death"))));
        return id;
    }

    // ── The choice ───────────────────────────────────────────────

    @Test
    @DisplayName("Warlock 11 owes a 6th-level arcanum; it is learned apart from the spells known with a pool of one "
            + "long-rest use, and levelling down forgets it, its pool and its choice")
    void choiceAtEleven() {
        JsonNode created = create(Map.of("level", 10));
        String id = created.get("id").asString();
        assertThat(arcanumChoices(created)).isEmpty();

        JsonNode levelled = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/level-up",
                Map.of("classId", warlockId), player.accessToken())).get("character");
        JsonNode choice = arcanumChoices(levelled).getFirst();
        assertThat(choice.get("characterLevel").asInt()).isEqualTo(11);
        assertThat(choice.get("maxSpellLevel").asInt()).isEqualTo(6);
        assertThat(choice.get("spellList").asString()).isEqualTo("Warlock");

        var heal = apply(id, Map.of("choiceId", choice.get("id").asString(), "spells", List.of("Heal")));
        var planeShift = apply(id, Map.of("choiceId", choice.get("id").asString(), "spells", List.of("Plane Shift")));
        assertThat(heal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(heal).get("error").asString()).isEqualTo("Heal isn't on the Warlock spell list");
        assertThat(planeShift.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(arcanumSpells(get(id))).isEmpty();

        JsonNode learned = expect(HttpStatus.OK, apply(id, Map.of("choiceId", choice.get("id").asString(),
                "spells", List.of("Circle of Death"))));

        assertThat(arcanumSpells(learned)).containsExactly("Circle of Death");
        assertThat(arcanumChoices(learned)).isEmpty();
        JsonNode arcanum = pool(learned.get("resourcePools"), "mystic-arcanum:6");
        assertThat(arcanum.get("displayName").asString()).isEqualTo("Mystic Arcanum (6th)");
        assertThat(arcanum.get("currentUses").asInt()).isEqualTo(1);
        assertThat(arcanum.get("maxUses").asInt()).isEqualTo(1);
        assertThat(arcanum.get("resetOn").asString()).isEqualTo("longRest");
        assertThat(learned.get("levelHistory").get(10).get("choices").get("mysticArcanum").asString())
                .isEqualTo("Circle of Death");

        JsonNode down = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/level-down", null,
                player.accessToken()));
        assertThat(arcanumSpells(down)).isEmpty();
        assertThat(pool(down.get("resourcePools"), "mystic-arcanum:6")).isNull();
        assertThat(arcanumChoices(down)).isEmpty();
    }

    @Test
    @DisplayName("A multiclass character's arcana come from its warlock levels only")
    void multiclassUsesWarlockLevels() {
        JsonNode warlockEleven = create(Map.of("level", 14, "multiclassClassEntries", List.of(
                Map.of("classId", warlockId, "level", 11), Map.of("classId", sorcererId, "level", 3))));
        JsonNode warlockNine = create(Map.of("name", "Bryn", "classId", sorcererId, "level", 20,
                "multiclassClassEntries", List.of(
                        Map.of("classId", sorcererId, "level", 11), Map.of("classId", warlockId, "level", 9))));

        assertThat(arcanumChoices(warlockEleven)).singleElement().satisfies(c -> {
            assertThat(c.get("className").asString()).isEqualTo("Warlock");
            assertThat(c.get("classLevel").asInt()).isEqualTo(11);
            assertThat(c.get("maxSpellLevel").asInt()).isEqualTo(6);
        });
        assertThat(arcanumChoices(warlockNine)).isEmpty();
    }

    @Test
    @DisplayName("The backfill owes an existing warlock's arcana, and a second run adds nothing")
    void backfill() {
        String id = create(Map.of("level", 13)).get("id").asString();
        jdbcTemplate.update("UPDATE player_characters SET pending_choices = '[]'::jsonb");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        MaintenanceReport applied = transaction.execute(status -> backfillPendingChoicesJob.run(true));

        assertThat(applied.changes()).singleElement().satisfies(change -> assertThat(change)
                .contains("MYSTIC_ARCANUM at Warlock 11 (level 11)", "MYSTIC_ARCANUM at Warlock 13 (level 13)"));
        assertThat(arcanumChoices(get(id))).extracting(c -> c.get("maxSpellLevel").asInt()).containsExactly(6, 7);
        assertThat(transaction.execute(status -> backfillPendingChoicesJob.run(false)).changes()).isEmpty();
    }

    // ── Casting ──────────────────────────────────────────────────

    @Test
    @DisplayName("In an encounter the arcanum casts its spell at its own level without a pact slot, is refused once "
            + "spent, is written back to the sheet, and a long rest restores it")
    void castSpendsTheArcanum() {
        String aria = warlockWithArcanum();
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").hitPoints(59).armourClass(11)
                .dexterity(8).constitution(10).type("Giant").challengeRating("2").source("MM")
                .actionTemplates(MonsterTemplates.parse("{}")).build());
        String ariaId = participantId(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        String ogreId = participantId(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 5))), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/spell-options?participantId=" + ariaId, null, player.accessToken()));
        JsonNode circle = null;
        for (JsonNode option : options) {
            if (option.get("name").asString().equals("Circle of Death")) circle = option;
        }
        assertThat(circle).isNotNull();
        assertThat(circle.get("sources").toString())
                .isEqualTo("[{\"source\":\"MYSTIC_ARCANUM\",\"levels\":[6],\"usesLeft\":1,\"usesMax\":1}]");

        Map<String, Object> cast = Map.of("spellName", "Circle of Death", "spellSource", "MYSTIC_ARCANUM",
                "slotLevel", 0, "targetIds", List.of(ogreId));
        // 24 on 8d6, and the ogre's CON save of 2 fails against Aria's DC 15 (8 + 4 + CHA 3)
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 2);
        JsonNode result = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, cast, player.accessToken()));
        var again = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + ariaId, cast, dm.accessToken());
        var upcast = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + ariaId, Map.of("spellName", "Circle of Death", "spellSource", "MYSTIC_ARCANUM", "slotLevel", 7,
                "targetIds", List.of(ogreId)), dm.accessToken());

        assertThat(result.get("spellSource").asString()).isEqualTo("MYSTIC_ARCANUM");
        assertThat(result.get("slotLevelUsed").asInt()).isEqualTo(6);
        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
        JsonNode ariaNow = participant(state, ariaId);
        assertThat(participant(state, ogreId).get("hpCurrent").asInt()).isEqualTo(35);
        assertThat(pool(ariaNow.get("resourcePoolsCurrent"), "mystic-arcanum:6").get("currentUses").asInt()).isZero();
        assertThat(ariaNow.get("spellSlotsCurrent").get("pact_5").get("used").asInt()).isZero();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(again).get("error").asString())
                .isEqualTo("Aria has used their level 6 Mystic Arcanum; it comes back on a long rest");
        assertThat(upcast.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode log = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        assertThat(descriptions).contains("Aria uses their level 6 Mystic Arcanum for Circle of Death (0/1 remaining)");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(pool(get(aria).get("resourcePools"), "mystic-arcanum:6").get("currentUses").asInt()).isZero();

        JsonNode rested = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + aria + "/long-rest",
                null, player.accessToken()));
        assertThat(pool(rested.get("resourcePools"), "mystic-arcanum:6").get("currentUses").asInt()).isEqualTo(1);
    }

    private static String participantId(JsonNode encounter, String displayName) {
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
