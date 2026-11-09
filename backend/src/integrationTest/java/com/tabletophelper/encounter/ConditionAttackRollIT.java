package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attacker's and the target's conditions on attack rolls, end to end (PHB appendix A, p.290-292): the conditions
 * the table adds through the condition endpoint change the attack's roll, the log names them, and the preview the
 * attack form reads shows them first. Aria, a player's fighter with STR 16 and +2 proficiency, carries a longsword
 * (+5, 1d8 + 3); she goes first, against a 59 HP ogre with AC 11 and a +6 greatclub, then a grimlock, which has
 * blindsight 30 ft. (MM p.175) and here the same greatclub.
 */
class ConditionAttackRollIT extends IntegrationTest {

    private static final String GREATCLUB = """
            {"actions": [{"name": "Greatclub", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 6, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "2d8 + 4", "damageType": "bludgeoning"}]}]}""";

    @Autowired private ItemRepository itemRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;
    private String grimlockId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        Map<String, Object> aria = new HashMap<>(Map.of("name", "Aria", "raceId", insertRace("Human"),
                "classId", insertClass("Fighter", 10), "strength", 16, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10));
        aria.put("weaponProficiencies", List.of("simple", "martial"));
        aria.put("campaignId", campaignId);
        UUID character = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters",
                aria, player.accessToken())).get("id").asString());
        itemRepository.save(Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                .source("PHB").build());
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Longsword", "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)),
                player.accessToken()));

        encounterId = createEncounter(dm.accessToken(), campaignId);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("Giant").challengeRating("2")
                .source("MM").hitPoints(59).armourClass(11).dexterity(8)
                .senses("{\"special\":[\"darkvision 60 ft.\"],\"passive_perception\":8}")
                .actionTemplates(MonsterTemplates.parse(GREATCLUB)).build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        Monster grimlock = monsterRepository.save(Monster.builder().name("Grimlock").type("Humanoid")
                .challengeRating("1/4").source("MM").hitPoints(11).armourClass(11).dexterity(12)
                .senses("{\"special\":[\"blindsight 30 ft. or 10 ft. while deafened (blind beyond this radius)\"],"
                        + "\"passive_perception\":13}")
                .actionTemplates(MonsterTemplates.parse(GREATCLUB)).build());
        grimlockId = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", grimlock.getId(), "displayName", "Grimlock")), "Grimlock");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 10),
                        Map.of("participantId", grimlockId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("The player sees a restrained ogre's advantage before attacking, and the attack rolls it; the "
            + "player's own disadvantage then cancels it")
    void restrainedTarget() {
        condition(ogreId, "restrained", dm.accessToken());

        JsonNode preview = expect(HttpStatus.OK, preview(ariaId, ogreId, true, player.accessToken()));
        assertThat(preview.toString()).isEqualTo("{\"rollMode\":\"ADVANTAGE\",\"sources\":[{\"mode\":\"ADVANTAGE\","
                + "\"reason\":\"target restrained\"}],\"hitIsCritical\":false}");

        // 3 and 14 with advantage: 14 + 5 hits AC 11; 1d8 + 3 is 6 + 3
        dice.script(3, 14, 6);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Longsword"), player.accessToken()));
        // Aria's next turn, past the ogre's and the grimlock's, for an action to attack with again (D174)
        for (int i = 0; i < 3; i++) {
            nextTurn();
        }
        dice.script(14, 2);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Longsword", "advantage", false),
                dm.accessToken()));

        assertThat(descriptions(dmLog())).containsSubsequence(
                "Aria uses Longsword on Ogre (advantage: target restrained): 2d20(3, 14) with advantage → 14 + 5 = 19 "
                        + "vs AC 11 — hit, 9 slashing damage.",
                "Aria uses Longsword on Ogre (advantage: target restrained): d20(14) + 5 = 19 vs AC 11 — hit, 5 "
                        + "slashing damage.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A poisoned, prone attacker has disadvantage against a stunned target's advantage: one d20")
    void bothSidesCancel() {
        condition(ariaId, "poisoned", player.accessToken());
        condition(ariaId, "prone", player.accessToken());
        condition(ogreId, "stunned", dm.accessToken());

        dice.script(9, 4);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Longsword"), player.accessToken()));

        assertThat(descriptions(dmLog())).contains("Aria uses Longsword on Ogre (advantage: target stunned; "
                + "disadvantage: attacker poisoned, attacker prone): d20(9) + 5 = 14 vs AC 11 — hit, 7 slashing "
                + "damage.");
    }

    @Test
    @DisplayName("A monster's attack on an invisible Aria has disadvantage; one with blindsight attacks normally, and "
            + "a prone Aria from within 5 ft gives it advantage")
    void monsterAttacksAndBlindsight() {
        condition(ariaId, "invisible", player.accessToken());

        nextTurn();
        dice.script(16, 4);
        expect(HttpStatus.OK, monsterAction(ogreId));
        nextTurn();
        dice.script(16, 1, 1);
        expect(HttpStatus.OK, monsterAction(grimlockId));
        condition(ariaId, "prone", dm.accessToken());
        JsonNode preview = expect(HttpStatus.OK, preview(grimlockId, ariaId, true, dm.accessToken()));

        assertThat(descriptions(dmLog())).containsSubsequence(
                "Ogre uses Greatclub on Aria (disadvantage: target invisible): 2d20(16, 4) with disadvantage → 4 + 6 "
                        + "= 10 vs AC 12 — miss.",
                "Grimlock uses Greatclub on Aria: d20(16) + 6 = 22 vs AC 12 — hit, 6 bludgeoning damage.");
        assertThat(preview.get("rollMode").asString()).isEqualTo("ADVANTAGE");
        assertThat(preview.get("sources").toString()).isEqualTo("[{\"mode\":\"ADVANTAGE\","
                + "\"reason\":\"target prone within 5 ft\"}]");
    }

    @Test
    @DisplayName("Only the DM or the attacker's controller reads the preview; without an attacker it is the DM's own "
            + "attack, whose target's conditions alone count")
    void previewAccess() {
        condition(ariaId, "poisoned", player.accessToken());
        condition(ogreId, "blinded", dm.accessToken());

        assertThat(preview(ogreId, ariaId, true, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(preview(ariaId, ogreId, false, dm.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/attack-roll-preview?targetId="
                + ogreId, null, player.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode dmsOwn = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-roll-preview?targetId=" + ogreId, null, dm.accessToken()));
        assertThat(dmsOwn.toString()).isEqualTo("{\"rollMode\":\"ADVANTAGE\",\"sources\":[{\"mode\":\"ADVANTAGE\","
                + "\"reason\":\"target blinded\"}],\"hitIsCritical\":false}");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void condition(String participantId, String condition, String token) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/add",
                Map.of("targetId", participantId, "condition", condition), token));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                null, dm.accessToken()));
    }

    private ResponseEntity<String> attack(Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + ariaId,
                new HashMap<>(request), token);
    }

    private ResponseEntity<String> monsterAction(String monsterId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", monsterId, "actionName", "Greatclub", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken());
    }

    private ResponseEntity<String> preview(String attackerId, String targetId, boolean withinFiveFeet, String token) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/attack-roll-preview?attackerId="
                + attackerId + "&targetId=" + targetId + "&withinFiveFeet=" + withinFiveFeet, null, token);
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asString().equals(displayName)) {
                return participant.get("id").asString();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
