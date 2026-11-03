package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
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
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens at turn boundaries, end to end (D162, D163): Repeat Saves rolled by the server or left to the
 * player as the campaign prefers, a spell's rounds ending at the start of its caster's turn, and a death save
 * due at the start of a Dying PC's turn. A table of a DM and one player, whose character Aria acts on 20, and a
 * ghoul on 10 whose claws paralyze until a DC 10 CON save at the end of its target's turn.
 */
class TurnBoundaryIT extends IntegrationTest {

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String ariaId;
    private String ghoulId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        encounterId = createEncounter(dm.accessToken(), campaignId);

        // The claws hit on the scripted d20 and deal 1 damage, which rolls no dice
        Monster ghoul = monsterRepository.save(Monster.builder()
                .name("Ghoul").type("Undead").challengeRating("1").source("MM")
                .hitPoints(22).armourClass(12).dexterity(15).wisdom(10)
                .actionTemplates("""
                        {"actions": [{"name": "Claws", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 2,
                          "attackType": "MELEE_WEAPON",
                          "effects": [{"effectType": "DAMAGE", "flatDamage": 1, "damageType": "slashing"},
                                      {"effectType": "CONDITION", "condition": "paralyzed", "durationRounds": 10,
                                       "saveToEndEachTurn": true, "saveToEndDC": 10, "saveToEndAbility": "CON"}]}]}""")
                .build());
        // Cantrips, so no slot is needed; their conditions are listed as the spell data lists them
        spell("Hideous Laugh", """
                {"spellName": "Hideous Laugh", "spellLevel": 0, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                 "concentration": true, "durationRounds": 10, "effects": [],
                 "conditionsInflicted": [{"condition": "incapacitated", "saveToAvoid": "WIS", "durationRounds": 10,
                   "saveToEndEachTurn": true, "saveToEndAbility": "WIS"}]}""");
        spell("Sickening Ray", """
                {"spellName": "Sickening Ray", "spellLevel": 0, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON",
                 "concentration": false, "durationRounds": null, "effects": [],
                 "conditionsInflicted": [{"condition": "poisoned", "saveToAvoid": "CON", "durationRounds": 1}]}""");

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        ghoulId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ghoul.getId(), "displayName", "Ghoul")), "Ghoul");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ghoulId, "initiative", 10))), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies Aria's sheet into her participant again (D166)
        // Aria knows both cantrips, so the spell rules let her cast them (D165)
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellsKnown(List.of(
                new KnownSpell(null, "Hideous Laugh", 0, "class:Fighter", null, null, null, null, null),
                new KnownSpell(null, "Sickening Ray", 0, "class:Fighter", null, null, null, null, null)));
        participantRepository.save(aria);
    }

    @Test
    @DisplayName("While the campaign doesn't prompt players, the server rolls a PC's Repeat Save as its turn ends")
    void serverRollsThePcsRepeatSave() {
        clawAria();
        nextTurn();
        assertThat(conditionNames(aria(dm.accessToken()))).containsExactly("paralyzed");

        // The end of Aria's turn: 15 beats DC 10
        dice.script(15);
        JsonNode state = nextTurn();

        assertThat(conditionNames(participant(state, ariaId))).isEmpty();
        List<String> playerLog = descriptions(log(player.accessToken()));
        assertThat(playerLog).anySatisfy(description -> assertThat(description)
                .startsWith("Aria repeats the CON save against paralyzed: d20(15)")
                .endsWith("— succeeds, and paralyzed ends."));
        assertThat(actionTypes(log(dm.accessToken()))).contains("REPEAT_SAVE");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("When the campaign prompts players, a PC's Repeat Save waits for its player, who rolls it")
    void playerRollsTheRepeatSave() {
        JsonNode settings = expect(HttpStatus.OK, exchange(HttpMethod.PATCH, "/api/campaigns/" + campaignId + "/settings",
                Map.of("promptPlayerRepeatSaves", true), dm.accessToken()));
        assertThat(settings.get("promptPlayerRepeatSaves").asBoolean()).isTrue();
        assertThat(settings.get("showExactMonsterHp").asBoolean()).isFalse();
        clawAria();
        nextTurn();

        nextTurn();
        JsonNode paralyzed = aria(player.accessToken()).get("activeConditions").get(0);
        assertThat(paralyzed.get("repeatSave").get("awaitingRoll").asBoolean()).isTrue();
        assertThat(paralyzed.get("repeatSave").get("ability").asText()).isEqualTo("CON");
        assertThat(paralyzed.get("repeatSave").get("dc").asInt()).isEqualTo(10);
        assertThat(actionTypes(log(dm.accessToken()))).doesNotContain("REPEAT_SAVE");

        // Aria's player rolls it on the ghoul's turn: 14 beats DC 10
        dice.script(14);
        JsonNode state = expect(HttpStatus.OK, repeatSave(paralyzed.get("id").asText(), player.accessToken()));

        assertThat(conditionNames(participant(state, ariaId))).isEmpty();
        assertThat(descriptions(log(player.accessToken()))).anySatisfy(description -> assertThat(description)
                .startsWith("Aria repeats the CON save against paralyzed: d20(14)"));
        ResponseEntity<String> again = repeatSave(paralyzed.get("id").asText(), player.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("A monster repeats its save as its turn ends, and a spell's rounds end at the start of its caster's turn")
    void monsterRepeatSaveAndSpellRounds() {
        // Aria's turn: the ghoul fails both saves, a 2 and a 1
        dice.script(2);
        cast("Hideous Laugh");
        dice.script(1);
        cast("Sickening Ray");
        assertThat(conditionNames(participant(getEncounter(dm.accessToken()), ghoulId)))
                .containsExactly("incapacitated", "poisoned");

        // The ghoul's turn starts, and the rays's round is counted on Aria's turns, not the ghoul's
        JsonNode state = nextTurn();
        assertThat(conditionNames(participant(state, ghoulId))).containsExactly("incapacitated", "poisoned");

        // The end of the ghoul's turn: its WIS save of 3 fails; then Aria's turn starts and the ray's round is over
        dice.script(3);
        state = nextTurn();
        assertThat(conditionNames(participant(state, ghoulId))).containsExactly("incapacitated");
        assertThat(state.get("roundNumber").asInt()).isEqualTo(2);

        nextTurn();
        // The end of the ghoul's next turn: 18 succeeds
        dice.script(18);
        state = nextTurn();
        assertThat(conditionNames(participant(state, ghoulId))).isEmpty();

        List<String> log = descriptions(log(dm.accessToken()));
        assertThat(log).contains("poisoned ends on Ghoul (its duration ends)");
        assertThat(log).anySatisfy(description -> assertThat(description)
                .isEqualTo("Ghoul repeats the WIS save against incapacitated: d20(3) + 0 = 3 vs DC 13 — fails, "
                        + "and stays incapacitated."));
        assertThat(log).anySatisfy(description -> assertThat(description)
                .startsWith("Ghoul repeats the WIS save against incapacitated: d20(18)"));
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A Dying PC is due a death save as its turn starts, in both views, until it rolls one")
    void deathSaveDue() {
        JsonNode aria = aria(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ariaId, "amount", aria.get("hpCurrent").asInt()), dm.accessToken()));
        assertThat(aria(dm.accessToken()).get("lifeState").asText()).isEqualTo("DYING");
        assertThat(aria(dm.accessToken()).get("deathSaveDue").asBoolean()).isFalse();

        nextTurn();
        nextTurn();
        assertThat(aria(dm.accessToken()).get("deathSaveDue").asBoolean()).isTrue();
        assertThat(aria(player.accessToken()).get("deathSaveDue").asBoolean()).isTrue();

        dice.script(12);
        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/death-save", Map.of("participantId", ariaId),
                player.accessToken()));

        assertThat(participant(state, ariaId).get("deathSaveDue").asBoolean()).isFalse();
        assertThat(participant(state, ariaId).get("deathSaveSuccesses").asInt()).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void spell(String name, String template) {
        spellRepository.save(Spell.builder().name(name).level(0).source("PHB").effectTemplate(template).build());
    }

    // On Aria's turn the DM passes it to the ghoul, whose claws hit with an 18 and paralyze her
    private void clawAria() {
        nextTurn();
        dice.script(18);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", ghoulId, "actionName", "Claws", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken()));
    }

    private void cast(String spell) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", spell, "slotLevel", 0, "targetIds", List.of(ghoulId), "overrideSpellSaveDC", 13),
                dm.accessToken()));
    }

    private ResponseEntity<String> repeatSave(String conditionId, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/repeat-save",
                Map.of("participantId", ariaId, "conditionId", conditionId), token);
    }

    private JsonNode nextTurn() {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                null, dm.accessToken()));
    }

    private JsonNode getEncounter(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null, token));
    }

    private JsonNode aria(String token) {
        return participant(getEncounter(token), ariaId);
    }

    private JsonNode log(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                token));
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }

    private static List<String> actionTypes(JsonNode log) {
        List<String> types = new ArrayList<>();
        log.forEach(entry -> types.add(entry.get("actionType").asText()));
        return types;
    }

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        participant.path("activeConditions").forEach(condition -> names.add(condition.get("name").asText()));
        return names;
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
}
