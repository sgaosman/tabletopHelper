package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
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
 * Channel Divinity, Bardic Inspiration and Arcane Recovery end to end (M17 workstream C, 2014 PHB). A player controls
 * Tam, a Life Domain Cleric 5 with Wisdom 18 (a save DC of 15) who goes first, and Kai, a Bard 1 who goes second; a
 * zombie (CR 1/4) and a ghoul (CR 1) go last. The pools are defined as resource-pools.json defines them.
 */
class ChannelDivinityIT extends IntegrationTest {

    private static final String CHANNEL_DIVINITY = "class:cleric-channel-divinity";
    private static final String INSPIRATION = "class:bard-bardic-inspiration";
    private static final String ARCANE_RECOVERY = "class:wizard-arcane-recovery";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private UUID race;
    private CharacterClass wizard;
    private String tamId;
    private String kaiId;
    private String zombieId;
    private String ghoulId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("tam");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass cleric = caster("Cleric", 8, "WIS");
        CharacterClass bard = caster("Bard", 8, "CHA");
        wizard = caster("Wizard", 6, "INT");
        Subclass life = subclassRepository.save(Subclass.builder().name("Life Domain").source("PHB")
                .characterClass(cleric).build());
        pool(cleric, 2, definition(CHANNEL_DIVINITY, "Channel Divinity", "1", "shortRest", "ACTION",
                "{\"effect\": \"channel-divinity\"}"));
        pool(bard, 1, definition(INSPIRATION, "Bardic Inspiration", "max(1, charismaModifier)", "longRest",
                "BONUS_ACTION", "{\"die\": \"d6\", \"effect\": \"bardic-inspiration\"}"));
        pool(wizard, 1, definition(ARCANE_RECOVERY, "Arcane Recovery", "1", "longRest", "FREE",
                "{\"effect\": \"arcane-recovery\"}"));

        race = insertRace("Human");
        String tam = create("Tam", cleric.getId(), life.getId(), 5);
        String kai = create("Kai", bard.getId(), null, 1);
        Monster zombie = monsterRepository.save(Monster.builder().name("Zombie").type("undead")
                .challengeRating("1/4").source("MM").hitPoints(22).armourClass(8).dexterity(6).wisdom(6).build());
        Monster ghoul = monsterRepository.save(Monster.builder().name("Ghoul").type("undead")
                .challengeRating("1").source("MM").hitPoints(22).armourClass(12).dexterity(15).wisdom(10).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        tamId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", tam, "displayName", "Tam")), "Tam");
        kaiId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", kai, "displayName", "Kai")), "Kai");
        zombieId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", zombie.getId(), "displayName", "Zombie")),
                "Zombie");
        ghoulId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ghoul.getId(), "displayName", "Ghoul")), "Ghoul");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", tamId, "initiative", 20),
                        Map.of("participantId", kaiId, "initiative", 15),
                        Map.of("participantId", zombieId, "initiative", 10),
                        Map.of("participantId", ghoulId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Turn Undead destroys the zombie and turns the ghoul, damage ends the turning, and undo brings it all "
            + "back")
    void turnUndead() {
        JsonNode tam = participant(dmView(), tamId);
        assertThat(texts(tam.get("channelDivinityOptions"))).containsExactly("turn-undead", "preserve-life");
        dice.script(2, 3);

        JsonNode turned = expect(HttpStatus.OK, usePool(tamId, CHANNEL_DIVINITY,
                Map.of("option", "turn-undead", "targetIds", List.of(zombieId, ghoulId))));

        assertThat(participant(turned, zombieId).get("lifeState").asString()).isEqualTo("DEAD");
        JsonNode ghoul = participant(turned, ghoulId);
        assertThat(conditionNames(ghoul)).containsExactly("turned");
        assertThat(ghoul.get("activeConditions").get(0).get("endRule").get("type").asString())
                .isEqualTo("ON_DAMAGE");
        assertThat(pool(participant(turned, tamId), CHANNEL_DIVINITY).get("currentUses").asInt()).isZero();
        assertThat(descriptions(logEntries("KILL"))).contains("Zombie is destroyed (Destroy Undead)");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ghoulId, "amount", 2), dm.accessToken()));
        assertThat(conditionNames(participant(dmView(), ghoulId))).isEmpty();

        JsonNode restored = undoNewest();
        assertThat(conditionNames(participant(restored, ghoulId))).containsExactly("turned");
        restored = undoNewest();
        assertThat(participant(restored, zombieId).get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(conditionNames(participant(restored, ghoulId))).isEmpty();
        assertThat(pool(participant(restored, tamId), CHANNEL_DIVINITY).get("currentUses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("A Turn Undead at a living creature is a 400, and Preserve Life past half a creature's maximum a 409")
    void refusals() {
        ResponseEntity<String> atKai = usePool(tamId, CHANNEL_DIVINITY,
                Map.of("option", "turn-undead", "targetIds", List.of(kaiId)));
        assertThat(atKai.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> tooMuch = usePool(tamId, CHANNEL_DIVINITY,
                Map.of("option", "preserve-life", "targetIds", List.of(kaiId), "amounts", List.of(5)));
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pool(participant(dmView(), tamId), CHANNEL_DIVINITY).get("currentUses").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kai inspires Tam, who rolls the d6 for a check; undo gives the die back")
    void bardicInspiration() {
        nextTurn();
        JsonNode inspired = expect(HttpStatus.OK, usePool(kaiId, INSPIRATION, Map.of("targetIds", List.of(tamId))));
        JsonNode held = participant(inspired, tamId).get("activeConditions").get(0);
        assertThat(held.get("name").asString()).isEqualTo("inspired");
        assertThat(held.get("heldDie").get("sides").asInt()).isEqualTo(6);
        assertThat(held.get("heldDie").get("onFailedSave").asBoolean()).isFalse();

        JsonNode armed = expect(HttpStatus.OK, inspiration(tamId, "ON_FAILED_SAVE"));
        assertThat(participant(armed, tamId).get("activeConditions").get(0).get("heldDie").get("onFailedSave")
                .asBoolean()).isTrue();
        dice.script(4);
        JsonNode rolled = expect(HttpStatus.OK, inspiration(tamId, "ROLL"));

        assertThat(conditionNames(participant(rolled, tamId))).isEmpty();
        assertThat(descriptions(logEntries("POOL_USE")).getLast()).startsWith("Tam rolls their Bardic Inspiration "
                + "die: d6(4)");
        assertThat(inspiration(tamId, "ROLL").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode restored = undoNewest();
        assertThat(conditionNames(participant(restored, tamId))).containsExactly("inspired");
    }

    @Test
    @DisplayName("A Wizard 5's Arcane Recovery brings back 3 levels of slots on a short rest, once until a long rest")
    void arcaneRecovery() {
        String ezra = create("Ezra", wizard.getId(), null, 5);
        for (String slot : List.of("1", "1", "2", "3")) {
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + ezra + "/spell-slots/" + slot
                    + "/spend", null, player.accessToken()));
        }

        JsonNode rested = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + ezra + "/short-rest",
                Map.of("hitDice", List.of(), "arcaneRecovery", List.of(1, 2)), player.accessToken()));

        JsonNode slots = rested.get("character").get("spellSlots");
        assertThat(slots.get("1").get("used").asInt()).isEqualTo(1);
        assertThat(slots.get("2").get("used").asInt()).isZero();
        assertThat(slots.get("3").get("used").asInt()).isEqualTo(1);
        assertThat(pool(rested.get("character"), "resourcePools", ARCANE_RECOVERY).get("currentUses").asInt())
                .isZero();

        ResponseEntity<String> again = exchange(HttpMethod.POST, "/api/characters/" + ezra + "/short-rest",
                Map.of("arcaneRecovery", List.of(1)), player.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> longRest = exchange(HttpMethod.POST, "/api/characters/" + ezra + "/long-rest", null,
                player.accessToken());
        assertThat(longRest.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private CharacterClass caster(String name, int hitDice, String ability) {
        return characterClassRepository.save(CharacterClass.builder().name(name).source("PHB").hitDice(hitDice)
                .subclassLevel(1).isSpellcaster(true).isPactMagic(false).spellcastingAbility(ability).build());
    }

    private ResourcePoolDefinition definition(String poolId, String name, String maxUses, String resetOn,
                                              String cost, String metadata) {
        return poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(poolId).displayName(name)
                .sourceType("CLASS").maxUsesFormula(maxUses).resetOn(resetOn).spendActionType(cost)
                .metadata(metadata).build());
    }

    private void pool(CharacterClass characterClass, int minLevel, ResourcePoolDefinition definition) {
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(characterClass)
                .minLevel(minLevel).poolDefinition(definition).build());
    }

    private String create(String name, UUID classId, UUID subclassId, int level) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", level, "strength", 10, "dexterity", 12, "constitution", 14, "intelligence", 16,
                "wisdom", 18, "charisma", 14));
        body.put("campaignId", campaignId);
        if (subclassId != null) {
            body.put("subclassId", subclassId);
        }
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

    private ResponseEntity<String> inspiration(String participantId, String use) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/bardic-inspiration",
                Map.of("participantId", participantId, "use", use), player.accessToken());
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

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> texts.add(item.asString()));
        }
        return texts;
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
        return pool(participant, "resourcePoolsCurrent", poolId);
    }

    private static JsonNode pool(JsonNode holder, String field, String poolId) {
        for (JsonNode pool : holder.get(field)) {
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
