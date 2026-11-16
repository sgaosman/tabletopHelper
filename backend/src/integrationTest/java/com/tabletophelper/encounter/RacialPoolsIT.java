package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceFeaturePool;
import com.tabletophelper.reference.RaceFeaturePoolRepository;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Racial resource pools end to end (M24.5b, D208): a race row's pools reach the sheet through race_feature_pools, an
 * encounter copies them, and their features work at the table. A player controls Kriv, a red Dragonborn Fighter 6 (CON
 * 14, proficiency +3), and Grosh, a Half-Orc Fighter 3; they go first and second, then a fire-resistant goblin and an
 * orc.
 */
class RacialPoolsIT extends IntegrationTest {

    private static final String BREATH = "race:breath-weapon";
    private static final String ENDURANCE = "race:relentless-endurance";

    @Autowired private CharacterClassRepository characterClassRepository;
    @Autowired private RaceRepository raceRepository;
    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private RaceFeaturePoolRepository raceFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private String kriv;
    private String grosh;
    private UUID encounterId;
    private String krivId;
    private String groshId;
    private String goblinId;
    private String orcId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("kriv");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        // As the races loader writes the rows, and resource-pools.json defines and maps the pools
        Race dragonborn = raceRepository.save(Race.builder().name("Dragonborn").source("PHB").speed("{\"walk\":30}")
                .traits("[{\"name\": \"Draconic Ancestry\", \"description\": \"Choose a dragon\"}, "
                        + "{\"name\": \"Breath Weapon\", \"description\": \"Exhale\"}]")
                .build());
        Race halfOrc = raceRepository.save(Race.builder().name("Half-Orc").source("PHB").speed("{\"walk\":30}")
                .build());
        racePool(dragonborn, definition(BREATH, "Breath Weapon", "shortRest", "ACTION",
                "{\"effect\": \"breath-weapon\"}"));
        racePool(halfOrc, definition(ENDURANCE, "Relentless Endurance", "longRest", "FREE",
                "{\"effect\": \"relentless-endurance\"}"));

        kriv = create("Kriv", dragonborn.getId(), fighter.getId(), 6, "Red");
        grosh = create("Grosh", halfOrc.getId(), fighter.getId(), 3, null);
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("Humanoid")
                .challengeRating("1/4").source("MM").hitPoints(40).armourClass(13).dexterity(10)
                .damageResistances("[\"fire\"]").build());
        Monster orc = monsterRepository.save(Monster.builder().name("Orc").type("Humanoid")
                .challengeRating("1/2").source("MM").hitPoints(40).armourClass(13).dexterity(10).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        krivId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", kriv, "displayName", "Kriv")), "Kriv");
        groshId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", grosh, "displayName", "Grosh")), "Grosh");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        orcId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", orc.getId(), "displayName", "Orc")), "Orc");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", krivId, "initiative", 20),
                        Map.of("participantId", groshId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10),
                        Map.of("participantId", orcId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The sheet has the race's pools, and a red dragonborn's ancestry and fire resistance from its "
            + "creation request, owing no choice")
    void sheetsHaveRacePools() {
        JsonNode dragonborn = sheet(kriv);
        assertThat(pool(dragonborn, BREATH).get("sourceType").asString()).isEqualTo("RACE");
        assertThat(pool(dragonborn, BREATH).get("sourceName").asString()).isEqualTo("Dragonborn");
        assertThat(pool(dragonborn, BREATH).get("currentUses").asInt()).isEqualTo(1);
        assertThat(dragonborn.get("damageResistances").toString()).contains("fire");
        assertThat(dragonborn.get("pendingChoices").toString()).doesNotContain("DRACONIC_ANCESTRY");

        assertThat(pool(sheet(grosh), ENDURANCE).get("resetOn").asString()).isEqualTo("longRest");
    }

    @Test
    @DisplayName("A swallowed dragonborn's Breath Weapon reaches only the creature that swallowed it: at the creatures "
            + "outside it is a 409 that spends nothing (D204)")
    void breathWeaponFromInsideASwallower() {
        EncounterParticipant dragonborn = participantRepository.findById(UUID.fromString(krivId)).orElseThrow();
        dragonborn.setActiveConditions(new ArrayList<>(List.of(com.tabletophelper.encounter.state.ConditionEntry
                .manual("swallowed", null, 1, UUID.fromString(orcId)))));
        participantRepository.save(dragonborn);

        ResponseEntity<String> outside = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/use-pool", Map.of("participantId", krivId, "poolId", BREATH,
                "targetIds", List.of(goblinId)), player.accessToken());
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outside.getBody()).contains("Kriv is swallowed");
        JsonNode unspent = participant(dmView(), krivId);
        assertThat(pool(unspent, BREATH).get("currentUses").asInt()).isEqualTo(1);
        assertThat(unspent.get("turnEconomy").get("actionsUsed").asInt()).isZero();

        // The orc that holds it: 3d6 of 4, 5, 6 and a failed save
        dice.script(4, 5, 6, 5);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool",
                Map.of("participantId", krivId, "poolId", BREATH, "targetIds", List.of(orcId)),
                player.accessToken()));
        assertThat(participant(dmView(), orcId).get("hpCurrent").asInt()).isEqualTo(40 - 15);
    }

    @Test
    @DisplayName("Kriv's breath, 3d6 fire against DEX DC 13, halves for the orc's save and again for the goblin's "
            + "resistance; the action and the use are spent, and Write-back carries the use to the sheet")
    void breathWeaponAgainstTwo() {
        // The damage once for the area, then each creature's save in the order named: the goblin fails, the orc saves
        dice.script(4, 5, 6, 5, 18);

        JsonNode used = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/use-pool", Map.of("participantId", krivId, "poolId", BREATH,
                "targetIds", List.of(goblinId, orcId)), player.accessToken()));

        // The player's view hides monsters' hit points (D157), so the DM's is read
        JsonNode dmView = dmView();
        assertThat(participant(dmView, goblinId).get("hpCurrent").asInt()).isEqualTo(40 - 7);
        assertThat(participant(dmView, orcId).get("hpCurrent").asInt()).isEqualTo(40 - 7);
        JsonNode dragonborn = participant(used, krivId);
        assertThat(pool(dragonborn, BREATH).get("currentUses").asInt()).isZero();
        assertThat(dragonborn.get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        assertThat(descriptions(logEntries("POOL_USE")).getFirst()).isEqualTo("Kriv uses Breath Weapon (Red "
                + "ancestry, 15 ft. cone, DEX save DC 13): 3d6 fire damage, half on a success; 0/1 left");

        ResponseEntity<String> again = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/use-pool", Map.of("participantId", krivId, "poolId", BREATH,
                "targetIds", List.of(orcId)), player.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(pool(sheet(kriv), BREATH).get("currentUses").asInt()).isZero();
    }

    @Test
    @DisplayName("Grosh drops to 1 HP instead of 0 once, spending Relentless Endurance; the next drop leaves him "
            + "dying")
    void relentlessEndurance() {
        int hp = participant(dmView(), groshId).get("hpCurrent").asInt();

        JsonNode hit = damage(groshId, hp + 3);

        JsonNode halfOrc = participant(hit, groshId);
        assertThat(halfOrc.get("hpCurrent").asInt()).isEqualTo(1);
        assertThat(pool(halfOrc, ENDURANCE).get("currentUses").asInt()).isZero();
        assertThat(descriptions(logEntries("DAMAGE")).getFirst())
                .endsWith("Grosh drops to 1 hit point instead of 0 (Relentless Endurance; 0/1 left).");

        JsonNode again = damage(groshId, 4);
        assertThat(participant(again, groshId).get("hpCurrent").asInt()).isZero();
        assertThat(participant(again, groshId).get("lifeState").asString()).isEqualTo("DYING");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResourcePoolDefinition definition(String poolId, String name, String resetOn, String cost,
                                              String metadata) {
        return poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(poolId).displayName(name)
                .sourceType("RACE").maxUsesFormula("1").resetOn(resetOn).spendActionType(cost).metadata(metadata)
                .build());
    }

    private void racePool(Race race, ResourcePoolDefinition definition) {
        raceFeaturePoolRepository.save(RaceFeaturePool.builder().race(race).minLevel(1).poolDefinition(definition)
                .build());
    }

    private String create(String name, UUID race, UUID classId, int level, String ancestry) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", level, "strength", 16, "dexterity", 12, "constitution", 14, "intelligence", 8,
                "wisdom", 10, "charisma", 10));
        body.put("campaignId", campaignId);
        if (ancestry != null) {
            body.put("draconicAncestry", ancestry);
        }
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();
    }

    private JsonNode damage(String targetId, int amount) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode sheet(String character) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + character, null,
                player.accessToken()));
    }

    private List<JsonNode> logEntries(String actionType) {
        JsonNode log = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<JsonNode> entries = new ArrayList<>();
        log.forEach(entry -> {
            if (entry.get("actionType").asString().equals(actionType)) {
                entries.add(entry);
            }
        });
        return entries;
    }

    private static List<String> descriptions(List<JsonNode> entries) {
        return entries.stream().map(entry -> entry.get("description").asString()).toList();
    }

    // A pool of a character sheet or of a participant
    private static JsonNode pool(JsonNode owner, String poolId) {
        JsonNode pools = owner.has("resourcePools") ? owner.get("resourcePools") : owner.get("resourcePoolsCurrent");
        for (JsonNode pool : pools) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId);
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
        throw new AssertionError(participantId + " is not in the encounter");
    }
}
