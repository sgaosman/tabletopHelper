package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.CharacterClass;
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
 * Wild Shape end to end (Druid 2, PHB p.66–67). A player controls Rowan, a Druid 2 who goes first; a goblin with plenty
 * of hit points goes second. Rowan's Wild Shape pool is defined as resource-pools.json defines it. The beasts are a
 * wolf (CR 1/4, 11 HP, AC 13, a Bite) and an ape (CR 1/2), beyond a 2nd-level druid.
 */
class WildShapeIT extends IntegrationTest {

    private static final String WILD_SHAPE = "class:druid-wild-shape";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SubclassRepository subclassRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String characterId;
    private String rowanId;
    private String goblinId;
    private Monster wolf;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("rowan");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass druid = characterClassRepository.save(CharacterClass.builder().name("Druid")
                .source("PHB").hitDice(8).subclassLevel(2).isSpellcaster(false).isPactMagic(false).build());
        ResourcePoolDefinition pool = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId(WILD_SHAPE).displayName("Wild Shape").sourceType("CLASS").maxUsesFormula("2")
                .resetOn("shortRest").spendActionType("ACTION").metadata("{\"effect\": \"wild-shape\"}").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(druid).minLevel(2)
                .poolDefinition(pool).build());

        UUID race = insertRace("Human");
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Rowan", "raceId", race, "classId",
                druid.getId(), "level", 2, "strength", 10, "dexterity", 12, "constitution", 14, "intelligence", 10,
                "wisdom", 16, "charisma", 8));
        body.put("campaignId", campaignId);
        characterId = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();

        wolf = monsterRepository.save(Monster.builder().name("Wolf").type("beast").challengeRating("1/4")
                .source("MM").hitPoints(11).armourClass(13).strength(12).dexterity(15).constitution(12)
                .speed("{\"walk\": 40}")
                .actionTemplates(MonsterTemplates.parse("""
                        {"actions": [
                           {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                            "attackType": "MELEE_WEAPON",
                            "effects": [{"effectType": "DAMAGE", "damageDice": "2d4 + 2", "damageType": "piercing"}]}
                        ]}"""))
                .build());
        monsterRepository.save(Monster.builder().name("Ape").type("beast").challengeRating("1/2").source("MM")
                .hitPoints(19).armourClass(12).speed("{\"walk\": 30, \"climb\": 30}").build());
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("humanoid")
                .challengeRating("1/4").source("MM").hitPoints(200).armourClass(10).dexterity(14).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        rowanId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Rowan")), "Rowan");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", rowanId, "initiative", 20),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The player picks the wolf from the options, becomes it, and bites the goblin with its Bite")
    void becomeAWolfAndBite() {
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/wild-shape-options?participantId=" + rowanId, null, player.accessToken()));
        assertThat(options.get("maxChallengeRating").asString()).isEqualTo("1/4");
        List<String> names = new ArrayList<>();
        options.get("beasts").forEach(beast -> names.add(beast.get("name").asString()));
        assertThat(names).containsExactly("Wolf");

        JsonNode before = participant(dmView(), rowanId);
        int ownHp = before.get("hpCurrent").asInt();
        JsonNode shaped = participant(expect(HttpStatus.OK, usePool(Map.of("beastId", wolf.getId()))), rowanId);
        assertThat(shaped.get("hpCurrent").asInt()).isEqualTo(11);
        assertThat(shaped.get("hpMax").asInt()).isEqualTo(11);
        assertThat(shaped.get("armourClass").asInt()).isEqualTo(13);
        assertThat(shaped.get("wildShape").get("beastName").asString()).isEqualTo("Wolf");
        assertThat(shaped.get("wildShape").get("normalHpCurrent").asInt()).isEqualTo(ownHp);
        assertThat(shaped.get("speed").get("walk").asInt()).isEqualTo(40);
        assertThat(pool(shaped, WILD_SHAPE).get("currentUses").asInt()).isEqualTo(1);

        // The player reads the wolf's actions, and can't bite off Rowan's turn
        JsonNode templates = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/participants/" + rowanId + "/actions", null, player.accessToken()));
        assertThat(templates.get("actions").get(0).get("name").asString()).isEqualTo("Bite");
        nextTurn();
        ResponseEntity<String> offTurn = bite();
        assertThat(offTurn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Damage past the wolf's hit points reverts Rowan and carries over; an ape is beyond 2nd level")
    void revertAndCarryOver() {
        int ownHp = participant(dmView(), rowanId).get("hpCurrent").asInt();
        Monster ape = monsterRepository.findByTypeIgnoreCase("beast").stream()
                .filter(beast -> beast.getName().equals("Ape")).findFirst().orElseThrow();
        assertThat(usePool(Map.of("beastId", ape.getId())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        expect(HttpStatus.OK, usePool(Map.of("beastId", wolf.getId())));

        // Wild Shape took Rowan's action, so he bites on his next turn
        nextTurn();
        nextTurn();
        dice.script(10, 3, 3);
        expect(HttpStatus.OK, bite());
        assertThat(participant(dmView(), goblinId).get("hpCurrent").asInt()).isEqualTo(192);

        JsonNode hurt = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/damage", Map.of("targetId", rowanId, "amount", 14), dm.accessToken()));
        JsonNode rowan = participant(hurt, rowanId);
        assertThat(rowan.get("wildShape").isNull()).isTrue();
        assertThat(rowan.get("hpCurrent").asInt()).isEqualTo(ownHp - 3);
        assertThat(rowan.get("lifeState").asString()).isEqualTo("ALIVE");
    }

    @Test
    @DisplayName("Ending the encounter in beast form writes back Rowan's own hit points, not the wolf's, and the use")
    void writeBack() {
        int sheetHp = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken())).get("hpCurrent").asInt();
        expect(HttpStatus.OK, usePool(Map.of("beastId", wolf.getId())));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", rowanId, "amount", 5), dm.accessToken()));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));

        JsonNode sheet = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
        assertThat(sheet.get("hpCurrent").asInt()).isEqualTo(sheetHp);
        JsonNode pool = null;
        for (JsonNode entry : sheet.get("resourcePools")) {
            if (entry.get("poolId").asString().equals(WILD_SHAPE)) {
                pool = entry;
            }
        }
        assertThat(pool).isNotNull();
        assertThat(pool.get("currentUses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Undo turns Rowan back from the wolf with his own hit points, maximum and AC, and gives back the use")
    void undo() {
        JsonNode before = participant(dmView(), rowanId);
        expect(HttpStatus.OK, usePool(Map.of("beastId", wolf.getId())));

        String newest = dmView().get("undo").get("commandId").asString();
        JsonNode restored = participant(expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/"
                + encounterId + "/combat/undo", Map.of("expectedCommandId", newest), dm.accessToken())), rowanId);

        assertThat(restored.get("wildShape").isNull()).isTrue();
        assertThat(restored.get("hpCurrent").asInt()).isEqualTo(before.get("hpCurrent").asInt());
        assertThat(restored.get("hpMax").asInt()).isEqualTo(before.get("hpMax").asInt());
        assertThat(restored.get("armourClass").asInt()).isEqualTo(before.get("armourClass").asInt());
        assertThat(pool(restored, WILD_SHAPE).get("currentUses").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A Circle of the Moon Druid 10 sees the elementals, spends two uses to become an air elemental, slams "
            + "with its Slam, and undo gives both uses back (PHB p.69)")
    void elementalWildShape() {
        CharacterClass druid = characterClassRepository.findAll().stream()
                .filter(characterClass -> characterClass.getName().equals("Druid")).findFirst().orElseThrow();
        Subclass moon = subclassRepository.save(Subclass.builder().name("Circle of the Moon").source("PHB")
                .characterClass(druid).build());
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Luna", "raceId", insertRace("Elf"),
                "classId", druid.getId(), "level", 10, "strength", 10, "dexterity", 12, "constitution", 14,
                "intelligence", 10, "wisdom", 16, "charisma", 8));
        body.put("campaignId", campaignId);
        body.put("subclassId", moon.getId());
        String luna = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();
        Monster air = monsterRepository.save(Monster.builder().name("Air Elemental").type("elemental")
                .challengeRating("5").source("MM").hitPoints(90).armourClass(15).strength(14).dexterity(20)
                .constitution(14).speed("{\"walk\": 0, \"fly\": 90}")
                .damageImmunities("[\"poison\"]")
                .conditionImmunities("[\"grappled\", \"poisoned\", \"prone\"]")
                .actionTemplates(MonsterTemplates.parse("""
                        {"actions": [
                           {"name": "Slam", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 8,
                            "attackType": "MELEE_WEAPON",
                            "effects": [{"effectType": "DAMAGE", "damageDice": "2d8 + 5",
                                         "damageType": "bludgeoning"}]}
                        ]}"""))
                .build());
        Monster goblin = monsterRepository.findByNameIgnoreCaseAndSource("Goblin", "MM").orElseThrow();
        UUID moonEncounter = createEncounter(dm.accessToken(), campaignId);
        String lunaId = idOf(addParticipant(dm.accessToken(), moonEncounter,
                Map.of("participantType", "PLAYER", "characterId", luna, "displayName", "Luna")), "Luna");
        String targetId = idOf(addParticipant(dm.accessToken(), moonEncounter,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + moonEncounter + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", lunaId, "initiative", 20),
                        Map.of("participantId", targetId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + moonEncounter + "/start", null,
                dm.accessToken()));

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + moonEncounter
                + "/combat/wild-shape-options?participantId=" + lunaId, null, player.accessToken()));
        assertThat(options.get("elementals")).hasSize(1);
        assertThat(options.get("elementals").get(0).get("name").asString()).isEqualTo("Air Elemental");

        Map<String, Object> use = Map.of("participantId", lunaId, "poolId", WILD_SHAPE, "beastId", air.getId());
        JsonNode shaped = participant(expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/"
                + moonEncounter + "/combat/use-pool", use, player.accessToken())), lunaId);
        assertThat(shaped.get("wildShape").get("beastName").asString()).isEqualTo("Air Elemental");
        assertThat(shaped.get("hpCurrent").asInt()).isEqualTo(90);
        assertThat(shaped.get("armourClass").asInt()).isEqualTo(15);
        assertThat(pool(shaped, WILD_SHAPE).get("currentUses").asInt()).isZero();
        assertThat(shaped.get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();

        // The elemental's Slam, on Luna's own turn: 15 + 8 hits, 2d8 + 5 is 3 + 4 + 5
        dice.script(15, 3, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + moonEncounter + "/combat/monster-action",
                Map.of("monsterParticipantId", lunaId, "actionName", "Slam", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(targetId)), player.accessToken()));
        JsonNode slammed = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + moonEncounter, null,
                dm.accessToken()));
        assertThat(participant(slammed, targetId).get("hpCurrent").asInt()).isEqualTo(188);

        // Undo the slam, then the transformation
        for (int i = 0; i < 2; i++) {
            String newest = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + moonEncounter, null,
                    dm.accessToken())).get("undo").get("commandId").asString();
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + moonEncounter + "/combat/undo",
                    Map.of("expectedCommandId", newest), dm.accessToken()));
        }
        JsonNode restored = participant(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/"
                + moonEncounter, null, dm.accessToken())), lunaId);
        assertThat(restored.get("wildShape").isNull()).isTrue();
        assertThat(pool(restored, WILD_SHAPE).get("currentUses").asInt()).isEqualTo(2);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> usePool(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("participantId", rowanId);
        body.put("poolId", WILD_SHAPE);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool", body,
                player.accessToken());
    }

    private ResponseEntity<String> bite() {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", rowanId, "actionName", "Bite", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(goblinId)), player.accessToken());
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
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
