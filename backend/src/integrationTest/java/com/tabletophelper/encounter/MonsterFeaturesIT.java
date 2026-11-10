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
 * Monster features end to end (D199): a revenant's Turn Immunity keeps it out of Turn Undead, Abjure Enemy holds an
 * ogre's speed, and a wolf's Pack Tactics gives it advantage while another wolf fights beside it. A player controls
 * Tam, a Life Domain Cleric 5 (Wisdom 18, save DC 15) on 20, and Aria, an Oath of Vengeance Paladin 3 (Charisma 14,
 * save DC 12) on 18; the monsters act after them.
 */
class MonsterFeaturesIT extends IntegrationTest {

    private static final String CLERIC_CD = "class:cleric-channel-divinity";
    private static final String PALADIN_CD = "class:paladin-channel-divinity";
    private static final String PACK_TACTICS = "[{\"name\": \"Pack Tactics\", \"description\": \"The wolf has "
            + "advantage on an attack roll against a creature if at least one of the wolf's allies is within 5 feet of "
            + "the creature and the ally isn't incapacitated.\"}]";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private UUID race;
    private String tamId;
    private String ariaId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("tam");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass cleric = caster("Cleric", 8, "WIS");
        CharacterClass paladin = caster("Paladin", 10, "CHA");
        Subclass life = subclassRepository.save(Subclass.builder().name("Life Domain").source("PHB")
                .characterClass(cleric).build());
        Subclass vengeance = subclassRepository.save(Subclass.builder().name("Oath of Vengeance").source("PHB")
                .characterClass(paladin).build());
        pool(cleric, 2, definition(CLERIC_CD));
        pool(paladin, 3, definition(PALADIN_CD));
        race = insertRace("Human");
        String tam = create("Tam", cleric.getId(), life.getId(), 5);
        String aria = create("Aria", paladin.getId(), vengeance.getId(), 3);

        encounterId = createEncounter(dm.accessToken(), campaignId);
        tamId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", tam, "displayName", "Tam")), "Tam");
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(tamId, 20);
        initiatives.put(ariaId, 18);
    }

    @Test
    @DisplayName("Turn Undead leaves out the revenant, which is immune to being turned, and turns the zombie (MM "
            + "p.259, PHB p.59)")
    void turnImmunity() {
        String revenant = monster(Monster.builder().name("Revenant").type("undead").challengeRating("5")
                .hitPoints(136).armourClass(13).wisdom(10).traits("[{\"name\": \"Turn Immunity\", \"description\": "
                        + "\"The revenant is immune to effects that turn undead.\"}]"));
        String zombie = monster(Monster.builder().name("Zombie").type("undead").challengeRating("1")
                .hitPoints(22).armourClass(8).wisdom(6));
        start();
        // the zombie alone rolls: 2 - 2 fails
        dice.script(2);

        JsonNode state = expect(HttpStatus.OK, usePool(tamId, CLERIC_CD,
                Map.of("option", "turn-undead", "targetIds", List.of(revenant, zombie))));

        assertThat(conditionNames(participant(state, revenant))).isEmpty();
        assertThat(conditionNames(participant(state, zombie))).containsExactly("turned");
        assertThat(log()).contains("Revenant is immune to being turned (Turn Immunity).");
    }

    @Test
    @DisplayName("Abjure Enemy: the ogre fails its WIS save, is frightened and its speed is 0 until it takes damage "
            + "(PHB p.88)")
    void abjureEnemy() {
        String ogre = monster(Monster.builder().name("Ogre").type("giant").challengeRating("2").hitPoints(59)
                .armourClass(11).wisdom(7).speed("{\"walk\": 40}"));
        start();
        nextTurn();
        // 2 - 2 fails DC 12
        dice.script(2);
        JsonNode state = expect(HttpStatus.OK, usePool(ariaId, PALADIN_CD,
                Map.of("option", "abjure-enemy", "targetIds", List.of(ogre))));
        assertThat(conditionNames(participant(state, ogre))).contains("frightened", "abjured");
        assertThat(participant(state, ogre).get("speed").get("walk").asInt()).isZero();

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ogre, "amount", 2), dm.accessToken()));
        JsonNode after = participant(dmView(), ogre);
        assertThat(conditionNames(after)).doesNotContain("frightened", "abjured");
        assertThat(after.get("speed").get("walk").asInt()).isEqualTo(40);
    }

    @Test
    @DisplayName("Abjure Enemy: on a successful save the ogre's speed is halved (PHB p.88)")
    void abjureEnemySaved() {
        String ogre = monster(Monster.builder().name("Ogre").type("giant").challengeRating("2").hitPoints(59)
                .armourClass(11).wisdom(7).speed("{\"walk\": 40}"));
        start();
        nextTurn();
        dice.script(19);
        JsonNode state = expect(HttpStatus.OK, usePool(ariaId, PALADIN_CD,
                Map.of("option", "abjure-enemy", "targetIds", List.of(ogre))));
        assertThat(conditionNames(participant(state, ogre))).containsExactly("abjured (half speed)");
        assertThat(participant(state, ogre).get("speed").get("walk").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("A wolf with another wolf in the fight attacks with Pack Tactics' advantage; once the other wolf is "
            + "dead it doesn't (MM p.341)")
    void packTactics() {
        String first = monster(Monster.builder().name("Wolf").type("beast").challengeRating("1/4").hitPoints(11)
                .armourClass(13).traits(PACK_TACTICS));
        String second = monster(Monster.builder().name("Dire Wolf").type("beast").challengeRating("1")
                .hitPoints(37).armourClass(14).traits(PACK_TACTICS));
        start();
        // advantage: 3 and 18 keep 18, + 4 hits; 2d4 piercing of 2s
        dice.script(3, 18, 2, 2);
        bite(first);
        assertThat(log()).anyMatch(line -> line.contains("Wolf attacks Tam") && line.contains("Pack Tactics"));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", second, "amount", 37), dm.accessToken()));
        // one d20 of 18 now
        dice.script(18, 2, 2);
        bite(first);
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void bite(String wolfId) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + wolfId, Map.of("targetId", tamId, "attackBonus", 4, "damageDice", "2d4", "damageType", "piercing"),
                dm.accessToken()));
    }

    private String monster(Monster.MonsterBuilder builder) {
        Monster saved = monsterRepository.save(builder.source("MM").build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", saved.getName())),
                saved.getName());
        initiatives.put(id, 15 - initiatives.size());
        return id;
    }

    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    private CharacterClass caster(String name, int hitDice, String ability) {
        return characterClassRepository.save(CharacterClass.builder().name(name).source("PHB").hitDice(hitDice)
                .subclassLevel(1).isSpellcaster(true).isPactMagic(false).spellcastingAbility(ability).build());
    }

    private ResourcePoolDefinition definition(String poolId) {
        return poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(poolId)
                .displayName("Channel Divinity").sourceType("CLASS").maxUsesFormula("1").resetOn("shortRest")
                .spendActionType("ACTION").metadata("{\"effect\": \"channel-divinity\"}").build());
    }

    private void pool(CharacterClass characterClass, int minLevel, ResourcePoolDefinition definition) {
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(characterClass)
                .minLevel(minLevel).poolDefinition(definition).build());
    }

    private String create(String name, UUID classId, UUID subclassId, int level) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", level, "strength", 10, "dexterity", 12, "constitution", 14, "intelligence", 10,
                "wisdom", 18, "charisma", 14));
        body.put("campaignId", campaignId);
        body.put("subclassId", subclassId);
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

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private List<String> log() {
        JsonNode entries = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get("description").asString()));
        return lines;
    }

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant.get("activeConditions");
        if (conditions != null && conditions.isArray()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
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
