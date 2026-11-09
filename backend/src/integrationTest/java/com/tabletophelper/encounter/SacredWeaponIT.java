package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sacred Weapon end to end (Oath of Devotion 3, PHB p.86): the paladin imbues one weapon it holds, and only that
 * weapon's attack rolls add its Charisma modifier. A player controls Aria, a Paladin 3 of the Oath of Devotion with
 * Charisma 16, a rapier and a longsword, who goes first; a 200 HP zombie goes second.
 */
class SacredWeaponIT extends IntegrationTest {

    private static final String CHANNEL_DIVINITY = "class:paladin-channel-divinity";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass paladin = characterClassRepository.save(CharacterClass.builder().name("Paladin").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
                .build());
        Subclass devotion = subclassRepository.save(Subclass.builder().name("Oath of Devotion").source("PHB")
                .characterClass(paladin).build());
        ResourcePoolDefinition pool = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId(CHANNEL_DIVINITY).displayName("Channel Divinity").sourceType("CLASS").maxUsesFormula("1")
                .resetOn("shortRest").spendActionType("ACTION").metadata("{\"effect\": \"channel-divinity\"}").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(paladin).minLevel(3)
                .poolDefinition(pool).build());
        itemRepository.saveAll(List.of(
                Item.builder().name("Rapier").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("piercing").properties("{\"tags\": [\"F\"]}").source("PHB").build(),
                Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                        .source("PHB").build()));

        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Aria", "raceId", insertRace("Human"),
                "classId", paladin.getId(), "level", 3, "strength", 16, "dexterity", 10, "constitution", 12,
                "intelligence", 8, "wisdom", 10, "charisma", 16));
        body.put("campaignId", campaignId);
        body.put("subclassId", devotion.getId());
        body.put("weaponProficiencies", List.of("simple", "martial"));
        String aria = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + aria + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Rapier", "quantity", 1), Map.of("name", "Longsword",
                        "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken()));
        Monster zombie = monsterRepository.save(Monster.builder().name("Zombie").type("undead").challengeRating("1/4")
                .source("MM").hitPoints(200).armourClass(8).dexterity(6).constitution(10).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        String zombieId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", zombie.getId(), "displayName", "Zombie")),
                "Zombie");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", zombieId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Sacred Weapon on the longsword adds +3 to the longsword's attack option only; undo takes it back")
    void imbuesTheLongsword() {
        int longswordBefore = option(attackOptions(), "Longsword").get("attackBonus").asInt();
        int rapierBefore = option(attackOptions(), "Rapier").get("attackBonus").asInt();

        JsonNode imbued = expect(HttpStatus.OK, usePool(Map.of("option", "sacred-weapon", "weapon", "Longsword")));

        JsonNode entry = participant(imbued, ariaId).get("activeConditions").get(0);
        assertThat(entry.get("name").asString()).isEqualTo("sacred weapon");
        assertThat(entry.get("weapon").asString()).isEqualTo("Longsword");
        JsonNode options = attackOptions();
        assertThat(option(options, "Longsword").get("attackBonus").asInt()).isEqualTo(longswordBefore + 3);
        assertThat(option(options, "Rapier").get("attackBonus").asInt()).isEqualTo(rapierBefore);

        String newest = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken())).get("undo").get("commandId").asString();
        JsonNode restored = participant(expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/"
                + encounterId + "/combat/undo", Map.of("expectedCommandId", newest), dm.accessToken())), ariaId);
        assertThat(restored.get("activeConditions").isEmpty()).isTrue();
        assertThat(option(attackOptions(), "Longsword").get("attackBonus").asInt()).isEqualTo(longswordBefore);
    }

    @Test
    @DisplayName("With two weapons and none named or equipped, Sacred Weapon is a 400 that spends nothing")
    void mustChoose() {
        ResponseEntity<String> refused = usePool(Map.of("option", "sacred-weapon"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Choose the weapon Sacred Weapon imbues: Rapier, Longsword");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> usePool(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("participantId", ariaId);
        body.put("poolId", CHANNEL_DIVINITY);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool", body,
                player.accessToken());
    }

    private JsonNode attackOptions() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-options?participantId=" + ariaId, null, player.accessToken()));
    }

    private static JsonNode option(JsonNode options, String weapon) {
        for (JsonNode option : options) {
            if (option.get("weapon").asString().equals(weapon)) {
                return option;
            }
        }
        throw new AssertionError("No attack option " + weapon);
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
