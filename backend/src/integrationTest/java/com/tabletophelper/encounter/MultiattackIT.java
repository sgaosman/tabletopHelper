package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
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
 * A multiattack with a target for each attack, end to end (D165). An owlbear, whose seeded templates make a beak and
 * a claw attack, fights Aria and Bram, a player's two characters; Aria has 40 HP and AC 12, Bram 9 HP and AC 10.
 */
class MultiattackIT extends IntegrationTest {

    // The Owlbear's seeded templates (MM), as the local database holds them
    private static final String OWLBEAR = """
            {"source": "MM", "traits": [{"name": "Keen Sight and Smell", "description": "The owlbear has advantage on Wisdom (Perception) checks that rely on sight or smell.", "combatRelevant": true}],
             "actions": [{"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true, "multiattackComponents": ["Beak", "Claw"],
                          "multiattackDescription": "The owlbear makes two attacks: one with its beak and one with its claws."},
                         {"name": "Beak", "reach": 5, "effects": [{"damageDice": "1d10 + 5", "damageType": "piercing", "effectType": "DAMAGE"}],
                          "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 7, "targetCount": 1, "deliveryMethod": "ATTACK_ROLL"},
                         {"name": "Claw", "reach": 5, "effects": [{"damageDice": "2d8 + 5", "damageType": "slashing", "effectType": "DAMAGE"}],
                          "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 7, "targetCount": 1, "deliveryMethod": "ATTACK_ROLL"}],
             "monsterName": "Owlbear", "challengeRating": "3"}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String owlbearId;
    private String ariaId;
    private String bramId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID race = insertRace("Human");
        UUID fighter = insertClass("Fighter", 10);
        UUID aria = createCharacter(player.accessToken(), race, fighter);
        UUID bram = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Bram", "raceId", race, "classId", fighter,
                "strength", 10, "dexterity", 10, "constitution", 10,
                "intelligence", 10, "wisdom", 10, "charisma", 10), player.accessToken())).get("id").asText());
        encounterId = createEncounter(dm.accessToken(), campaignId);

        Monster owlbear = monsterRepository.save(Monster.builder().name("Owlbear").type("Monstrosity")
                .challengeRating("3").source("MM").hitPoints(59).armourClass(13).dexterity(12)
                .actionTemplates(OWLBEAR).build());
        owlbearId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", owlbear.getId(), "displayName", "Owlbear")), "Owlbear");
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        bramId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", bram, "displayName", "Bram")), "Bram");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", owlbearId, "initiative", 20),
                        Map.of("participantId", ariaId, "initiative", 15),
                        Map.of("participantId", bramId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        setVitals(ariaId, 40, 12);
        setVitals(bramId, 9, 10);
    }

    @Test
    @DisplayName("The DM reads the owlbear's two attacks, then sends the beak at Aria and the claw at Bram, each "
            + "resolved and logged on its own")
    void splitAcrossTwoTargets() {
        JsonNode options = expect(HttpStatus.OK, multiattackOptions(dm.accessToken()));
        assertThat(options.toString()).isEqualTo("[{\"name\":\"Multiattack\",\"description\":\"The owlbear makes two "
                + "attacks: one with its beak and one with its claws.\",\"components\":["
                + "{\"name\":\"Beak\",\"automated\":true,\"manualReason\":null,\"maxTargets\":1},"
                + "{\"name\":\"Claw\",\"automated\":true,\"manualReason\":null,\"maxTargets\":1}],\"manualReason\":null}]");

        // The beak: 10 + 7 hits AC 12 for 4 + 5; the claw: 12 + 7 hits AC 10 for 3 + 3 + 5
        dice.script(10, 4, 12, 3, 3);
        JsonNode result = expect(HttpStatus.OK, multiattack(List.of(List.of(ariaId), List.of(bramId))));

        assertThat(result.get("resolved").asBoolean()).isTrue();
        assertThat(result.get("targetResults").size()).isEqualTo(2);
        JsonNode state = dmView();
        assertThat(participant(state, ariaId).get("hpCurrent").asInt()).isEqualTo(31);
        assertThat(participant(state, bramId).get("hpCurrent").asInt()).isZero();
        assertThat(participant(state, bramId).get("lifeState").asText()).isEqualTo("DYING");
        assertThat(descriptions(dmLog())).containsSubsequence(
                "Owlbear uses Multiattack.",
                "Owlbear uses Multiattack (Beak) on Aria: d20(10) + 7 = 17 vs AC 12 — hit, 9 piercing damage.",
                "Owlbear uses Multiattack (Claw) on Bram: d20(12) + 7 = 19 vs AC 10 — hit, 11 slashing damage.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("All attacks still go at one target without targets per attack; two targets that way, or a player "
            + "reading the options, is refused")
    void singleTargetAndRefusals() {
        // Both attacks at Aria: 15 + 7 hits for 2 + 5, then 1 misses
        dice.script(15, 2, 1);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", owlbearId, "actionName", "Multiattack", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken()));
        var bothTargets = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", owlbearId, "actionName", "Multiattack", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId, bramId)), dm.accessToken());
        var tooMany = multiattack(List.of(List.of(ariaId, bramId), List.of()));

        assertThat(participant(dmView(), ariaId).get("hpCurrent").asInt()).isEqualTo(33);
        assertThat(bothTargets.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(bothTargets).get("error").asText())
                .isEqualTo("Multiattack makes its attacks one target at a time: choose a target for each attack");
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(tooMany).get("error").asText()).isEqualTo("Beak can target 1 creature, but 2 were chosen");
        assertThat(multiattackOptions(player.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void setVitals(String participantId, int hp, int armourClass) {
        EncounterParticipant participant = participantRepository.findById(UUID.fromString(participantId)).orElseThrow();
        participant.setHpMax(hp);
        participant.setHpCurrent(hp);
        participant.setArmourClass(armourClass);
        participantRepository.save(participant);
    }

    private ResponseEntity<String> multiattack(List<List<String>> componentTargetIds) {
        Map<String, Object> request = new HashMap<>(Map.of("monsterParticipantId", owlbearId,
                "actionName", "Multiattack", "actionSource", "ACTION", "targetParticipantIds", List.of()));
        request.put("componentTargetIds", componentTargetIds);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action", request,
                dm.accessToken());
    }

    private ResponseEntity<String> multiattackOptions(String token) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/multiattack-options?participantId="
                + owlbearId, null, token);
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
