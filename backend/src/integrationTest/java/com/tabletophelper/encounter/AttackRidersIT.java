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
 * Weapon attack riders end to end (M17 workstream B, 2014 PHB). A player controls Sly, a Rogue 3 who goes first, Aria,
 * a Paladin 3 who goes second, and Kai, a Monk 5 who goes third, each with STR 16 and DEX 16; Kai has WIS 14 and 5 ki
 * points. A zombie, an undead with 200 HP, AC 8 and CON 10, goes last. Sly and Aria carry a rapier and a longsword.
 */
class AttackRidersIT extends IntegrationTest {

    private static final String KI = "class:monk-ki";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private ItemRepository itemRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String slyId;
    private String ariaId;
    private String kaiId;
    private String zombieId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("party");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        CharacterClass rogue = characterClass("Rogue", 8);
        // A half caster with CHA, whose 3rd level has three 1st-level slots (PHB p.83)
        CharacterClass paladin = characterClassRepository.save(CharacterClass.builder().name("Paladin").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
                .build());
        CharacterClass monk = characterClass("Monk", 8);
        ResourcePoolDefinition ki = poolDefinitionRepository.save(ResourcePoolDefinition.builder().poolId(KI)
                .displayName("Ki Points").sourceType("CLASS").maxUsesFormula("5").resetOn("shortRest")
                .spendActionType("FREE").metadata("{\"effect\": \"ki\"}").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(monk).minLevel(2)
                .poolDefinition(ki).build());
        itemRepository.saveAll(List.of(
                Item.builder().name("Rapier").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("piercing").properties("{\"tags\": [\"F\"]}").source("PHB").build(),
                Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                        .source("PHB").build()));

        UUID race = insertRace("Human");
        String sly = create("Sly", race, rogue.getId(), 3);
        String aria = create("Aria", race, paladin.getId(), 3);
        String kai = create("Kai", race, monk.getId(), 5);
        Monster zombie = monsterRepository.save(Monster.builder().name("Zombie").type("undead").challengeRating("1/4")
                .source("MM").hitPoints(200).armourClass(8).dexterity(6).constitution(10).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        slyId = join(sly, "Sly");
        ariaId = join(aria, "Aria");
        kaiId = join(kai, "Kai");
        zombieId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", zombie.getId(), "displayName", "Zombie")),
                "Zombie");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", slyId, "initiative", 20),
                        Map.of("participantId", ariaId, "initiative", 15),
                        Map.of("participantId", kaiId, "initiative", 12),
                        Map.of("participantId", zombieId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Sneak Attack claimed with an ally beside the zombie is dealt once on Sly's turn, refused a second time, "
            + "and dealt again by an opportunity attack on the zombie's turn")
    void sneakAttackOncePerTurn() {
        // 15 + 5 hits; 1d8 + 3 is 3 + 3, Sneak Attack 2d6 is 2 + 5
        dice.script(15, 3, 2, 5);
        expect(HttpStatus.OK, attack(slyId, Map.of("weapon", "Rapier", "sneakAttack", true)));
        ResponseEntity<String> again = attack(slyId, Map.of("weapon", "Rapier", "sneakAttack", true));
        for (int i = 0; i < 3; i++) {
            nextTurn();
        }
        dice.script(15, 3, 1, 1);
        expect(HttpStatus.OK, attack(slyId, Map.of("weapon", "Rapier", "sneakAttack", true, "cost", "REACTION")));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(participant(dmView(), zombieId).get("hpCurrent").asInt()).isEqualTo(200 - 13 - 8);
        assertThat(descriptions(logEntries("ATTACK"))).contains(
                "Sly uses Rapier (Sneak Attack +2d6) on Zombie: d20(15) + 5 = 20 vs AC 8 — hit, 13 piercing damage.",
                "Sly uses Rapier (Sneak Attack +2d6) (reaction) on Zombie: d20(15) + 5 = 20 vs AC 8 — hit, 8 piercing "
                        + "damage.");
    }

    @Test
    @DisplayName("Divine Smite spends a slot only after a hit, adds 1d8 against the undead zombie, and undo gives the "
            + "slot and the damage back")
    void divineSmite() {
        nextTurn();
        expectSlot(0);
        dice.script(1);
        expect(HttpStatus.OK, attack(ariaId, Map.of("weapon", "Longsword")));
        ResponseEntity<String> afterMiss = smite(1, null);
        expectSlot(0);
        // Aria's next turn, for another attack
        for (int i = 0; i < 4; i++) {
            nextTurn();
        }

        dice.script(15, 4);
        JsonNode hit = expect(HttpStatus.OK, attack(ariaId, Map.of("weapon", "Longsword")));
        JsonNode lastHit = participant(hit, ariaId).get("turnEconomy").get("lastMeleeHit");
        assertThat(lastHit.get("turn").asString()).isEqualTo(hit.get("turnStamp").asString());
        assertThat(lastHit.get("divineSmiteOffered").asBoolean()).isTrue();
        // 2d8 + 1d8 against undead: 1 + 2 + 3
        dice.script(1, 2, 3);
        expect(HttpStatus.OK, smite(1, lastHit.get("attackLogId").asString()));

        assertThat(afterMiss.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(participant(dmView(), zombieId).get("hpCurrent").asInt()).isEqualTo(200 - 7 - 6);
        expectSlot(1);
        assertThat(descriptions(logEntries("ATTACK"))).contains("Aria uses Divine Smite (3d8 radiant; level 1 spell "
                + "slot, 2/3 left; +1d8 against undead) after the Longsword hit on Zombie: 6 radiant damage.");

        JsonNode undone = undoNewest();
        assertThat(participant(undone, zombieId).get("hpCurrent").asInt()).isEqualTo(200 - 7);
        expectSlot(0);
        assertThat(participant(undone, ariaId).get("turnEconomy").get("lastMeleeHit").get("smitten").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("Stunning Strike after Kai's unarmed hit spends a ki point and stuns the zombie on a failed save until "
            + "the end of Kai's next turn")
    void stunningStrike() {
        nextTurn();
        nextTurn();
        // DEX 16 and +3 proficiency: 15 + 6 hits; the martial arts d6 is 4, + 3
        dice.script(15, 4);
        JsonNode hit = expect(HttpStatus.OK, attack(kaiId, Map.of("weapon", "Unarmed Strike")));
        String attackLogId = participant(hit, kaiId).get("turnEconomy").get("lastMeleeHit").get("attackLogId")
                .asString();
        // CON save: 2 + 0 against DC 8 + 3 + 2 = 13
        dice.script(2);
        JsonNode stunned = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/stunning-strike", Map.of("participantId", kaiId, "attackLogId", attackLogId),
                player.accessToken()));

        assertThat(conditionNames(participant(stunned, zombieId))).containsExactly("stunned");
        assertThat(pool(participant(stunned, kaiId), KI).get("currentUses").asInt()).isEqualTo(4);
        assertThat(descriptions(logEntries("ATTACK"))).contains("Kai uses Stunning Strike (1 ki, 4/5 left) after the "
                + "Unarmed Strike hit on Zombie: CON save d20(2) + 0 = 2 vs DC 13 — fails, gains stunned.");

        // The end of Kai's turn, the zombie's, Sly's and Aria's turns, and Kai's next turn starts: still stunned
        for (int i = 0; i < 4; i++) {
            nextTurn();
        }
        assertThat(conditionNames(participant(dmView(), zombieId))).containsExactly("stunned");
        nextTurn();
        assertThat(conditionNames(participant(dmView(), zombieId))).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private CharacterClass characterClass(String name, int hitDice) {
        return characterClassRepository.save(CharacterClass.builder().name(name).source("PHB").hitDice(hitDice)
                .subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
    }

    private String create(String name, UUID race, UUID classId, int level) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", level, "strength", 16, "dexterity", 16, "constitution", 12, "intelligence", 8,
                "wisdom", 14, "charisma", 10));
        body.put("campaignId", campaignId);
        body.put("weaponProficiencies", List.of("simple", "martial"));
        String id = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + id + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Rapier", "quantity", 1), Map.of("name", "Longsword",
                        "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken()));
        return id;
    }

    private String join(String characterId, String name) {
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", name)), name);
    }

    private ResponseEntity<String> attack(String actorId, Map<String, Object> request) {
        Map<String, Object> body = new HashMap<>(request);
        body.put("targetId", zombieId);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + actorId, body,
                player.accessToken());
    }

    private ResponseEntity<String> smite(int slotLevel, String attackLogId) {
        Map<String, Object> body = new HashMap<>(Map.of("participantId", ariaId, "slotLevel", slotLevel));
        if (attackLogId != null) {
            body.put("attackLogId", attackLogId);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/divine-smite", body,
                player.accessToken());
    }

    private void expectSlot(int used) {
        JsonNode slot = participant(dmView(), ariaId).get("spellSlotsCurrent").get("1");
        assertThat(slot.get("max").asInt()).isEqualTo(3);
        assertThat(slot.get("used").asInt()).isEqualTo(used);
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
