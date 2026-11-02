package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legendary Resistance on a player's spell, end to end (D163). A player's character, first in the order,
 * casts Fireball at a 200 HP dragon with three uses of Legendary Resistance; the dice are scripted so the
 * dragon fails its save against 32 fire damage.
 */
class LegendaryResistanceIT extends IntegrationTest {

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String dragonId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Wizard", 6));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));
        spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB").effectTemplate("""
                {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
                 "halfOnSave": true, "concentration": false, "components": {"verbal": true},
                 "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire"}],
                 "conditionsInflicted": null}""").build());
        Monster dragon = monsterRepository.save(Monster.builder()
                .name("Adult Blue Dragon").type("Dragon").challengeRating("16").source("MM")
                .hitPoints(200).armourClass(19).dexterity(10)
                .build());

        JsonNode state = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria"));
        ariaId = state.get("participants").get(0).get("id").asText();
        state = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", dragon.getId(), "displayName", "Dragon"));
        for (JsonNode participant : state.get("participants")) {
            if (participant.get("displayName").asText().equals("Dragon")) {
                dragonId = participant.get("id").asText();
            }
        }

        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(Map.of("3", new SpellSlot(2, 0)));
        aria.setSpellSaveDc(15);
        aria.setSpellsKnown(List.of(new KnownSpell(null, "Fireball", 3, "class:Wizard", true, null, null, null, null)));
        participantRepository.save(aria);
        EncounterParticipant dragonParticipant = participantRepository.findById(UUID.fromString(dragonId)).orElseThrow();
        dragonParticipant.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(
                "monster:legendary-resistance", "Legendary Resistance", "MONSTER", "Adult Blue Dragon",
                3, "3", 3, "longRest", null, null, "FREE", "shield", null))));
        participantRepository.save(dragonParticipant);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", dragonId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("On a player's save-for-half spell, Legendary Resistance gives back only the difference")
    void refundsOnlyTheDifference() {
        // 8d6 rolls 32, and the dragon's DEX save of 3 fails against DC 15
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", "Fireball", "slotLevel", 3, "targetIds", List.of(dragonId)),
                player.accessToken()));
        assertThat(dragon().get("hpCurrent").asInt()).isEqualTo(168);

        JsonNode entry = failedSaveEntry();
        assertThat(entry.get("legendaryResistanceEligible").asBoolean()).isTrue();
        assertThat(entry.get("lrSuccessDamage").asInt()).isEqualTo(16);
        assertThat(entry.get("lrConditionIds").isEmpty()).isTrue();
        // The players' log never carries the Legendary Resistance fields (D157)
        JsonNode playerLog = playerLog();
        JsonNode playerEntry = playerLog.get(entryIndex(playerLog, entry.get("id").asText()));
        assertThat(playerEntry.path("legendaryResistanceEligible").isBoolean()).isFalse();
        assertThat(playerEntry.path("lrSuccessDamage").isNumber()).isFalse();

        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entry.get("id").asText()), dm.accessToken()));

        // It ends as if it had made the save: 200 less half of 32
        assertThat(participant(state, dragonId).get("hpCurrent").asInt()).isEqualTo(184);
        assertThat(participant(state, dragonId).get("resourcePoolsCurrent").get(0).get("currentUses").asInt())
                .isEqualTo(2);
        assertThat(failedSaveEntry().get("lrResolved").asBoolean()).isTrue();
        assertThat(dmLog().toString()).contains("Dragon uses Legendary Resistance and succeeds on the save instead: "
                + "regains 16 HP (2 remaining)");
    }

    @Test
    @DisplayName("Legendary Resistance can't be used twice on one save, and players can't use it")
    void usedOnceByTheDm() {
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", "Fireball", "slotLevel", 3, "targetIds", List.of(dragonId)),
                player.accessToken()));
        String entryId = failedSaveEntry().get("id").asText();

        var byPlayer = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entryId), player.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entryId), dm.accessToken()));
        var again = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entryId), dm.accessToken());

        assertThat(byPlayer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dragon().get("hpCurrent").asInt()).isEqualTo(184);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private JsonNode dragon() {
        return participant(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken())), dragonId);
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private JsonNode playerLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                player.accessToken()));
    }

    // The dragon's entry for the Fireball
    private JsonNode failedSaveEntry() {
        for (JsonNode entry : dmLog()) {
            if (dragonId.equals(entry.path("targetId").asText()) && "SPELL_CAST".equals(entry.get("actionType").asText())) {
                return entry;
            }
        }
        throw new AssertionError("The dragon has no Fireball entry: " + dmLog());
    }

    private static int entryIndex(JsonNode log, String id) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).get("id").asText().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("Entry " + id + " is not in the log");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }
}
