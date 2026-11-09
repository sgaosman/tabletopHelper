package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
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
 * Class features used at the table through the pool-use command, end to end (M17, 2014 PHB). A player controls Grog, a
 * Barbarian 3 who goes first, and Aria, a Paladin 3 who goes second; a goblin with plenty of hit points goes last.
 * Grog's Rage (p.48) and Aria's Lay on Hands (p.84) are defined as resource-pools.json defines them, with their
 * effects.
 */
class FeatureEffectsIT extends IntegrationTest {

    private static final String RAGE = "class:barbarian-rage";
    private static final String LAY_ON_HANDS = "class:paladin-lay-on-hands";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String grogId;
    private String ariaId;
    private String goblinId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("grog");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass barbarian = characterClassRepository.save(CharacterClass.builder().name("Barbarian")
                .source("PHB").hitDice(12).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        CharacterClass paladin = characterClassRepository.save(CharacterClass.builder().name("Paladin")
                .source("PHB").hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        pool(barbarian, definition(RAGE, "Rage", "3", "longRest", "BONUS_ACTION", "{\"effect\": \"rage\"}"));
        pool(paladin, definition(LAY_ON_HANDS, "Lay on Hands", "5*paladinLevel", "longRest", "ACTION",
                "{\"effect\": \"lay-on-hands\"}"));

        UUID race = insertRace("Human");
        String grog = create("Grog", race, barbarian.getId());
        String aria = create("Aria", race, paladin.getId());
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("humanoid")
                .challengeRating("1/4").source("MM").hitPoints(200).armourClass(10).dexterity(14).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        grogId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", grog, "displayName", "Grog")), "Grog");
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", grogId, "initiative", 20),
                        Map.of("participantId", ariaId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("A rage halves slashing damage, survives a turn in which Grog was hurt, and ends after a turn in which "
            + "he neither attacked nor was hurt")
    void rage() {
        JsonNode raged = expect(HttpStatus.OK, usePool(grogId, RAGE, Map.of()));
        JsonNode grog = participant(raged, grogId);
        assertThat(conditionNames(grog)).containsExactly("raging");
        assertThat(pool(grog, RAGE).get("currentUses").asInt()).isEqualTo(2);
        assertThat(grog.get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();

        int hp = grog.get("hpCurrent").asInt();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", grogId, "amount", 11, "damageType", "slashing"), dm.accessToken()));
        grog = participant(dmView(), grogId);
        assertThat(grog.get("hpCurrent").asInt()).isEqualTo(hp - 5);
        assertThat(grog.get("turnEconomy").get("attackedOrDamaged").asBoolean()).isTrue();

        nextTurn();
        assertThat(conditionNames(participant(dmView(), grogId))).containsExactly("raging");
        nextTurn();
        nextTurn();
        // Grog's turn again; it ends without an attack or damage
        nextTurn();

        assertThat(conditionNames(participant(dmView(), grogId))).isEmpty();
        assertThat(descriptions(logEntries("CONDITION_REMOVE")))
                .contains("Rage ends on Grog (it neither attacked nor took damage since its last turn)");
    }

    @Test
    @DisplayName("Undo brings back the rage the turn's end took away, and undoing the rage itself gives back its use")
    void undoRage() {
        expect(HttpStatus.OK, usePool(grogId, RAGE, Map.of()));
        nextTurn();
        assertThat(conditionNames(participant(dmView(), grogId))).isEmpty();

        JsonNode restored = undoNewest();
        assertThat(conditionNames(participant(restored, grogId))).containsExactly("raging");

        restored = undoNewest();
        JsonNode grog = participant(restored, grogId);
        assertThat(conditionNames(grog)).isEmpty();
        assertThat(pool(grog, RAGE).get("currentUses").asInt()).isEqualTo(3);
        assertThat(grog.get("turnEconomy").get("bonusActionUsed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Lay on Hands heals Grog by the points Aria spends; more points than the pool holds is a 409 and an "
            + "unknown option a 400")
    void layOnHands() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", grogId, "amount", 10), dm.accessToken()));
        int hurt = participant(dmView(), grogId).get("hpCurrent").asInt();
        nextTurn();

        JsonNode healed = expect(HttpStatus.OK, usePool(ariaId, LAY_ON_HANDS,
                Map.of("uses", 7, "targetIds", List.of(grogId))));

        assertThat(participant(healed, grogId).get("hpCurrent").asInt()).isEqualTo(hurt + 7);
        assertThat(pool(participant(healed, ariaId), LAY_ON_HANDS).get("currentUses").asInt()).isEqualTo(8);
        assertThat(descriptions(logEntries("HEAL"))).containsExactly("Aria heals Grog for 7 HP (Lay on Hands)");

        ResponseEntity<String> tooMuch = usePool(ariaId, LAY_ON_HANDS, Map.of("uses", 9, "targetIds",
                List.of(grogId)));
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> badOption = usePool(ariaId, LAY_ON_HANDS, Map.of("uses", 5, "option", "smite"));
        assertThat(badOption.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResourcePoolDefinition definition(String poolId, String name, String maxUses, String resetOn,
                                              String cost, String metadata) {
        return poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(poolId).displayName(name)
                .sourceType("CLASS").maxUsesFormula(maxUses).resetOn(resetOn).spendActionType(cost)
                .metadata(metadata).build());
    }

    private void pool(CharacterClass characterClass, ResourcePoolDefinition definition) {
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(characterClass)
                .minLevel(1).poolDefinition(definition).build());
    }

    private String create(String name, UUID race, UUID classId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", 3, "strength", 16, "dexterity", 12, "constitution", 14, "intelligence", 8, "wisdom", 10,
                "charisma", 14));
        body.put("campaignId", campaignId);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();
    }

    private ResponseEntity<String> usePool(String participantId, String poolId, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("participantId", participantId);
        body.put("poolId", poolId);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool", body,
                player.accessToken());
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
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
