package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Weapon attacks the server works out, end to end (D164). Aria, a player's fighter with STR 16, DEX 14 and +2
 * proficiency, carries a greatsword and a +1 longsword she lists as a plain longsword, and has Great Weapon Master;
 * she goes first, against a 59 HP ogre with AC 11. The weapon rows are the PHB's, as ItemSeeder writes them.
 */
class WeaponAttackIT extends IntegrationTest {

    @Autowired private ItemRepository itemRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character, Map.of(
                "strength", 16, "proficiencyBonus", 2,
                "weaponProficiencies", List.of("simple", "martial"),
                "features", List.of(Map.of("name", "Great Weapon Master", "description", "Feat", "source", "Feat")),
                "equipment", List.of(Map.of("name", "Greatsword", "quantity", 1),
                        Map.of("name", "Longsword", "quantity", 1, "description", "+1"),
                        Map.of("name", "Backpack", "quantity", 1))),
                player.accessToken()));
        itemRepository.saveAll(List.of(
                Item.builder().name("Greatsword").type("Melee Weapon").subtype("martial").damageDice("2d6")
                        .damageType("slashing").properties("{\"tags\": [\"H\", \"2H\"]}").source("PHB").build(),
                Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                        .source("PHB").build(),
                Item.builder().name("Backpack").type("Adventuring Gear").source("PHB").build()));

        encounterId = createEncounter(dm.accessToken(), campaignId);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("Giant").challengeRating("2")
                .source("MM").hitPoints(59).armourClass(11).dexterity(8).build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("With Manual Modifiers allowed, the player picks a weapon, takes Great Weapon Master's trade and adds "
            + "the longsword's +1 by hand, which the log records")
    void settingOn() {
        JsonNode options = expect(HttpStatus.OK, attackOptions(player.accessToken()));
        assertThat(options.toString()).isEqualTo("[{\"weapon\":\"Greatsword\",\"ability\":\"STR\",\"proficient\":true,"
                + "\"attackBonus\":5,\"damage\":\"2d6 + 3\",\"damageType\":\"slashing\",\"ranged\":false,"
                + "\"powerAttack\":{\"feat\":\"Great Weapon Master\",\"attackBonus\":0,\"damage\":\"2d6 + 13\"}},"
                + "{\"weapon\":\"Longsword\",\"ability\":\"STR\",\"proficient\":true,\"attackBonus\":5,"
                + "\"damage\":\"1d8 + 3\",\"damageType\":\"slashing\",\"ranged\":false,\"powerAttack\":null},"
                + "{\"weapon\":\"Unarmed Strike\",\"ability\":\"STR\",\"proficient\":true,\"attackBonus\":5,"
                + "\"damage\":\"4\",\"damageType\":\"bludgeoning\",\"ranged\":false,\"powerAttack\":null}]");

        // 11 + 0 hits AC 11; 2d6 + 13 is 3 + 4 + 13
        dice.script(11, 3, 4);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Greatsword", "powerAttack", true),
                player.accessToken()));
        // 5 + 5 + 1 = 11 hits; 1d8 + 3 + 1 is 6 + 3 + 1
        dice.script(5, 6);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Longsword", "extraAttackBonus", 1,
                "extraDamageDice", "1"), player.accessToken()));
        var notHeavy = attack(Map.of("targetId", ogreId, "weapon", "Longsword", "powerAttack", true),
                player.accessToken());

        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59 - 20 - 10);
        assertThat(descriptions(dmLog())).containsSubsequence(
                "Aria uses Greatsword (Great Weapon Master) on Ogre: d20(11) + 0 = 11 vs AC 11 — hit, 20 slashing damage.",
                "Manual Modifier (player): Aria's Longsword attack adds +1 to the attack roll.",
                "Manual Modifier (player): Aria's Longsword attack adds 1 slashing damage.",
                "Aria uses Longsword on Ogre: d20(5) + 6 = 11 vs AC 11 — hit, 10 slashing damage.");
        assertThat(notHeavy.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(notHeavy).get("error").asText()).isEqualTo("Great Weapon Master needs a heavy melee weapon and "
                + "Sharpshooter a ranged weapon, and the Longsword is neither");
        // The players' log has the same entries: they name no hidden participant
        assertThat(descriptions(expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, player.accessToken()))))
                .contains("Manual Modifier (player): Aria's Longsword attack adds +1 to the attack roll.");
    }

    @Test
    @DisplayName("With Manual Modifiers disallowed, the player's additions and attacks entered by hand are 403s, but "
            + "the weapon attack the server works out goes ahead; the DM's numbers by hand are still logged")
    void settingOff() {
        expect(HttpStatus.OK, exchange(HttpMethod.PATCH, "/api/campaigns/" + campaignId + "/settings",
                Map.of("allowPlayerManualModifiers", false), dm.accessToken()));

        var magic = attack(Map.of("targetId", ogreId, "weapon", "Longsword", "extraAttackBonus", 1),
                player.accessToken());
        var crit = attack(Map.of("targetId", ogreId, "weapon", "Longsword", "forceCrit", true), player.accessToken());
        var entered = attack(Map.of("targetId", ogreId, "attackBonus", 7, "damageDice", "1d8+4"),
                player.accessToken());
        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59);

        // 15 + 5 hits; 1d8 + 3 is 4 + 3
        dice.script(15, 4);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "weapon", "Longsword"), player.accessToken()));
        // The DM attacks for Aria with numbers by hand: 10 + 7 hits; 1d8 + 4 is 2 + 4
        dice.script(10, 2);
        expect(HttpStatus.OK, attack(Map.of("targetId", ogreId, "attackBonus", 7, "damageDice", "1d8+4"),
                dm.accessToken()));

        for (ResponseEntity<String> refused : List.of(magic, crit, entered)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(json(refused).get("error").asText())
                    .isEqualTo("This campaign doesn't allow players to enter Manual Modifiers");
        }
        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59 - 7 - 6);
        assertThat(descriptions(dmLog())).containsSubsequence(
                "Aria uses Longsword on Ogre: d20(15) + 5 = 20 vs AC 11 — hit, 7 slashing damage.",
                "Manual Modifier (DM): Aria's attack uses attack bonus +7 and 1d8 + 4 damage.",
                "Aria attacks Ogre: d20(10) + 7 = 17 vs AC 11 — hit, 6 damage.");
        assertThat(descriptions(dmLog())).noneMatch(entry -> entry.startsWith("Manual Modifier (player)"));
    }

    @Test
    @DisplayName("A player reads only their own character's attack options; the DM reads anyone's")
    void attackOptionsAccess() {
        RegisteredUser other = register("bram");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join", Map.of("inviteCode",
                expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/campaigns/" + campaignId, null, dm.accessToken()))
                        .get("inviteCode").asText()), other.accessToken()));

        assertThat(attackOptions(other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(expect(HttpStatus.OK, attackOptions(dm.accessToken())).size()).isEqualTo(3);
        assertThat(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-options?participantId=" + ogreId, null, dm.accessToken())).size()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> attack(Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + ariaId,
                new HashMap<>(request), token);
    }

    private ResponseEntity<String> attackOptions(String token) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/attack-options?participantId="
                + ariaId, null, token);
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asText().equals(displayName)) {
                return participant.get("id").asText();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }
}
