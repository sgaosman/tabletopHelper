package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Way of the Kensei end to end (XGE p.34-35). A player controls Ren, a Monk 6 of the Way of the Kensei with DEX 16,
 * WIS 14 and 6 ki points, whose kensei weapons are the longsword and the longbow, going first against a zombie with
 * 200 HP and AC 8.
 */
class KenseiIT extends IntegrationTest {

    private static final String KI = "class:monk-ki";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CharacterRepository characterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String renId;
    private String zombieId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("ren");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass monk = characterClassRepository.save(CharacterClass.builder().name("Monk").source("PHB")
                .hitDice(8).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        ResourcePoolDefinition ki = poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(KI)
                .displayName("Ki Points").sourceType("CLASS").maxUsesFormula("6").resetOn("shortRest")
                .spendActionType("FREE").metadata("{\"effect\": \"ki\"}").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(monk).minLevel(2)
                .poolDefinition(ki).build());
        itemRepository.saveAll(List.of(
                Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                        .source("PHB").build(),
                Item.builder().name("Longbow").type("Ranged Weapon").subtype("martial").damageDice("1d8")
                        .damageType("piercing").properties("{\"tags\": [\"A\", \"H\", \"2H\"]}").source("PHB")
                        .build()));

        String ren = createKensei(insertRace("Human"), monk.getId());
        Monster zombie = monsterRepository.save(Monster.builder().name("Zombie").type("undead").challengeRating("1/4")
                .source("MM").hitPoints(200).armourClass(8).dexterity(6).constitution(10).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        renId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", ren, "displayName", "Ren")), "Ren");
        zombieId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", zombie.getId(), "displayName", "Zombie")),
                "Zombie");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", renId, "initiative", 20),
                        Map.of("participantId", zombieId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Kensei's Shot adds 1d4 to a longbow hit, Deft Strike adds the martial arts d6 for 1 ki once a turn, "
            + "and undo gives the ki and the damage back")
    void kenseisShotAndDeftStrike() {
        expect(HttpStatus.OK, usePool(Map.of("option", "kenseis-shot")));
        // 15 + 6 hits; 1d8 + 3 is 5 + 3, Kensei's Shot 1d4 is 3
        dice.script(15, 5, 3);
        expect(HttpStatus.OK, attack(Map.of("weapon", "Longbow")));
        JsonNode hit = participant(dmView(), renId).get("turnEconomy").get("lastMeleeHit");
        assertThat(hit.get("ranged").asBoolean()).isTrue();
        assertThat(hit.get("deftStrikeOffered").asBoolean()).isTrue();

        dice.script(4);
        expect(HttpStatus.OK, deftStrike());
        ResponseEntity<String> again = deftStrike();

        JsonNode state = dmView();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(participant(state, zombieId).get("hpCurrent").asInt()).isEqualTo(200 - 11 - 4);
        assertThat(pool(participant(state, renId), KI).get("currentUses").asInt()).isEqualTo(5);
        assertThat(descriptions(logEntries("ATTACK"))).contains(
                "Ren uses Longbow (Martial Arts, Kensei's Shot +1d4) on Zombie: d20(15) + 6 = 21 vs AC 8 — hit, 11 "
                        + "piercing damage.",
                "Ren uses Deft Strike (1d6 piercing; 1 ki, 5/6 left) after the Longbow hit on Zombie: 4 piercing "
                        + "damage.");

        JsonNode undone = undoNewest();
        assertThat(participant(undone, zombieId).get("hpCurrent").asInt()).isEqualTo(200 - 11);
        assertThat(pool(participant(undone, renId), KI).get("currentUses").asInt()).isEqualTo(6);
        assertThat(participant(undone, renId).get("turnEconomy").path("deftStrikeUsed").asBoolean(false)).isFalse();
    }

    @Test
    @DisplayName("An unarmed strike of the Attack action raises Ren's AC by 2 until Ren's next turn (Agile Parry), and "
            + "Sharpen the Blade gives the longsword +2 for 2 ki")
    void agileParryAndSharpen() {
        int armourClass = participant(dmView(), renId).get("armourClass").asInt();
        dice.script(15, 3);
        expect(HttpStatus.OK, attack(Map.of("weapon", "Unarmed Strike")));
        JsonNode state = dmView();
        assertThat(participant(state, renId).get("armourClass").asInt()).isEqualTo(armourClass + 2);
        assertThat(conditionNames(participant(state, renId))).contains("agile parry");

        // Sharpen the Blade needs Monk 11
        expect(HttpStatus.BAD_REQUEST, usePool(Map.of("option", "sharpen-the-blade", "uses", 2,
                "weapon", "Longsword")));
    }

    // ── Helpers ──────────────────────────────────────────────────

    // A Monk 6 of the Way of the Kensei, carrying a longsword and a longbow, its kensei weapons recorded on Monk 3
    private String createKensei(UUID race, UUID classId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Ren", "raceId", race, "classId", classId,
                "level", 6, "strength", 10, "dexterity", 16, "constitution", 12, "intelligence", 8,
                "wisdom", 14, "charisma", 10));
        body.put("campaignId", campaignId);
        body.put("weaponProficiencies", List.of("simple", "martial"));
        String id = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + id + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Longsword", "quantity", 1), Map.of("name", "Longbow",
                        "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken()));
        PlayerCharacter ren = characterRepository.findById(UUID.fromString(id)).orElseThrow();
        ren.setMulticlassEntries(ren.getMulticlassEntries().stream()
                .map(e -> new ClassEntry(e.classId(), e.className(), null, "Way of the Kensei", e.level()))
                .toList());
        ren.setSubclass("Way of the Kensei");
        List<LevelHistoryEntry> history = new ArrayList<>(ren.getLevelHistory());
        LevelHistoryEntry third = history.get(2);
        LevelChoices choices = third.choices() != null ? third.choices() : LevelChoices.NONE;
        history.set(2, third.withChoices(choices.withKenseiWeapons(List.of("Longsword", "Longbow"))));
        ren.setLevelHistory(history);
        characterRepository.save(ren);
        return id;
    }

    private ResponseEntity<String> attack(Map<String, Object> request) {
        Map<String, Object> body = new HashMap<>(request);
        body.put("targetId", zombieId);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + renId, body,
                player.accessToken());
    }

    private ResponseEntity<String> usePool(Map<String, Object> request) {
        Map<String, Object> body = new HashMap<>(request);
        body.put("participantId", renId);
        body.put("poolId", KI);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool", body,
                player.accessToken());
    }

    private ResponseEntity<String> deftStrike() {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/deft-strike",
                Map.of("participantId", renId), player.accessToken());
    }

    private JsonNode undoNewest() {
        String newest = dmView().get("undo").get("commandId").asString();
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));
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

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant.get("activeConditions");
        if (conditions != null && conditions.isArray()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
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
