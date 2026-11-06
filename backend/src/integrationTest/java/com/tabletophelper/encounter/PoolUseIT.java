package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pools used at the table through the pool-use command, end to end (D174, D127, D166). A player controls Vex, a
 * Fighter 3 Psi Warrior with Second Wind, Action Surge, Psionic Energy dice and Psionic Recovery, and Wren, a
 * Fighter 3 Cavalier with Warding Maneuver; they go first and second, then a goblin with plenty of hit points.
 */
class PoolUseIT extends IntegrationTest {

    private static final String SECOND_WIND = "class:fighter-second-wind";
    private static final String ACTION_SURGE = "class:fighter-action-surge";
    private static final String DICE = "subclass:psi-warrior-psionic-energy";
    private static final String RECOVERY = "subclass:psi-warrior-psionic-recovery";
    private static final String WARDING = "subclass:cavalier-warding-maneuver";

    @Autowired private CharacterClassRepository characterClassRepository;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private String inviteCode;
    private String vex;
    private String wren;
    private UUID encounterId;
    private String vexId;
    private String wrenId;
    private String goblinId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("vex");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        inviteCode = campaign.get("inviteCode").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join", Map.of("inviteCode", inviteCode),
                player.accessToken()));

        CharacterClass fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        Subclass psiWarrior = subclassRepository.save(Subclass.builder().name("Psi Warrior").source("TCE")
                .characterClass(fighter).build());
        Subclass cavalier = subclassRepository.save(Subclass.builder().name("Cavalier").source("XGE")
                .characterClass(fighter).build());
        // As resource-pools.json defines them
        pool(fighter, null, 1, definition(SECOND_WIND, "Second Wind", "1", "shortRest", "BONUS_ACTION", null));
        pool(fighter, null, 2, definition(ACTION_SURGE, "Action Surge", "1", "shortRest", "FREE",
                "{\"grantsAction\": true}"));
        pool(fighter, psiWarrior, 3, definition(DICE, "Psionic Energy Dice", "2*proficiencyBonus", "longRest", "FREE",
                "{\"die\": \"d6\"}"));
        pool(fighter, psiWarrior, 3, definition(RECOVERY, "Psionic Recovery", "1", "shortRest", "BONUS_ACTION",
                "{\"recovers\": \"" + DICE + "\"}"));
        pool(fighter, cavalier, 3, definition(WARDING, "Warding Maneuver", "max(1, constitutionModifier)", "longRest",
                "REACTION", null));

        UUID race = insertRace("Human");
        vex = create("Vex", race, fighter.getId(), psiWarrior.getId());
        wren = create("Wren", race, fighter.getId(), cavalier.getId());
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("Humanoid")
                .challengeRating("1/4").source("MM").hitPoints(200).armourClass(10).dexterity(14).build());

        // One die spent before the fight, on the sheet
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + vex + "/pools/spend",
                Map.of("poolId", DICE), player.accessToken()));

        encounterId = createEncounter(dm.accessToken(), campaignId);
        vexId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", vex, "displayName", "Vex")), "Vex");
        wrenId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", wren, "displayName", "Wren")), "Wren");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", vexId, "initiative", 20),
                        Map.of("participantId", wrenId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
        // Started directly: startEncounter would roll initiative again
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Psionic Recovery as a bonus action regains a die, a second bonus action that turn is a 409, and "
            + "Write-back carries the die to the sheet at the end")
    void psionicRecoveryWrittenBack() {
        assertThat(pool(sheet(vex), DICE).get("currentUses").asInt()).isEqualTo(3);

        JsonNode used = expect(HttpStatus.OK, usePool(vexId, RECOVERY, player.accessToken()));

        JsonNode inFight = participant(used, vexId);
        assertThat(pool(inFight, DICE).get("currentUses").asInt()).isEqualTo(4);
        assertThat(pool(inFight, RECOVERY).get("currentUses").asInt()).isZero();
        assertThat(inFight.get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();

        ResponseEntity<String> secondWind = usePool(vexId, SECOND_WIND, player.accessToken());
        assertThat(secondWind.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(secondWind).get("error").asString())
                .isEqualTo("It has already taken its bonus action this turn");
        assertThat(pool(participant(dmView(), vexId), SECOND_WIND).get("currentUses").asInt()).isEqualTo(1);

        JsonNode entry = logEntries("POOL_USE").getFirst();
        assertThat(entry.get("description").asString()).isEqualTo("Vex uses Psionic Recovery (bonus action) and "
                + "regains a use of Psionic Energy Dice (4/4); 0/1 left");
        assertThat(entry.get("actorId").asString()).isEqualTo(vexId);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        JsonNode after = sheet(vex);
        assertThat(pool(after, DICE).get("currentUses").asInt()).isEqualTo(4);
        assertThat(pool(after, RECOVERY).get("currentUses").asInt()).isZero();
        assertThat(pool(after, SECOND_WIND).get("currentUses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Action Surge gives a Fighter 3 a second Attack action, and is used only on her turn")
    void actionSurge() {
        dice.script(15, 3);
        expect(HttpStatus.OK, attack(vexId));
        ResponseEntity<String> refused = attack(vexId);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        expect(HttpStatus.OK, usePool(vexId, ACTION_SURGE, player.accessToken()));
        dice.script(15, 3);
        expect(HttpStatus.OK, attack(vexId));

        JsonNode fighter = participant(dmView(), vexId);
        assertThat(fighter.get("turnEconomy").get("actions").asInt()).isEqualTo(2);
        assertThat(fighter.get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(2);
        assertThat(fighter.get("turnEconomy").get("actionSurgeUsed").asBoolean()).isTrue();
        assertThat(pool(fighter, ACTION_SURGE).get("currentUses").asInt()).isZero();
        assertThat(participant(dmView(), goblinId).get("hpCurrent").asInt()).isEqualTo(200 - 6);
        assertThat(descriptions(logEntries("POOL_USE")))
                .containsExactly("Vex uses Action Surge and takes an additional action; 0/1 left");

        // On Wren's turn the surge, which costs nothing, still needs Vex's own turn
        nextTurn();
        assertThat(usePool(vexId, ACTION_SURGE, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Wren's reaction pool is used on Vex's turn, but a bonus action pool isn't, and a second reaction is "
            + "a 409")
    void reactionOffTurn() {
        JsonNode used = expect(HttpStatus.OK, usePool(wrenId, WARDING, player.accessToken()));

        JsonNode cavalier = participant(used, wrenId);
        assertThat(pool(cavalier, WARDING).get("currentUses").asInt()).isEqualTo(1);
        assertThat(cavalier.get("turnEconomy").get("reactionUsed").asBoolean()).isTrue();
        assertThat(usePool(wrenId, SECOND_WIND, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(usePool(wrenId, WARDING, player.accessToken()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(descriptions(logEntries("POOL_USE")))
                .containsExactly("Wren uses Warding Maneuver (reaction); 1/2 left");
    }

    @Test
    @DisplayName("A player can't use a pool of a participant they don't control; the DM can use anyone's")
    void access() {
        RegisteredUser other = register("other");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join", Map.of("inviteCode", inviteCode),
                other.accessToken()));

        assertThat(usePool(vexId, SECOND_WIND, other.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(usePool(goblinId, SECOND_WIND, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pool(participant(dmView(), vexId), SECOND_WIND).get("currentUses").asInt()).isEqualTo(1);

        expect(HttpStatus.OK, usePool(vexId, SECOND_WIND, dm.accessToken()));
        assertThat(pool(participant(dmView(), vexId), SECOND_WIND).get("currentUses").asInt()).isZero();
        assertThat(usePool(vexId, "class:monk-ki", dm.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResourcePoolDefinition definition(String poolId, String name, String maxUses, String resetOn,
                                              String cost, String metadata) {
        return poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(poolId).displayName(name)
                .sourceType("CLASS").maxUsesFormula(maxUses).resetOn(resetOn).spendActionType(cost)
                .metadata(metadata).build());
    }

    private void pool(CharacterClass characterClass, Subclass subclass, int minLevel,
                      ResourcePoolDefinition definition) {
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(characterClass).subclass(subclass)
                .minLevel(minLevel).poolDefinition(definition).build());
    }

    private String create(String name, UUID race, UUID classId, UUID subclassId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "subclassId", subclassId, "level", 3, "strength", 16, "dexterity", 12, "constitution", 14,
                "intelligence", 8, "wisdom", 10));
        body.put("charisma", 10);
        body.put("campaignId", campaignId);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();
    }

    private ResponseEntity<String> usePool(String participantId, String poolId, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool",
                Map.of("participantId", participantId, "poolId", poolId), token);
    }

    private ResponseEntity<String> attack(String actorId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + actorId,
                new HashMap<>(Map.of("targetId", goblinId, "attackBonus", 5, "damageDice", "1d6")),
                player.accessToken());
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
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

    private JsonNode sheet(String character) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + character, null,
                player.accessToken()));
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
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }
}
