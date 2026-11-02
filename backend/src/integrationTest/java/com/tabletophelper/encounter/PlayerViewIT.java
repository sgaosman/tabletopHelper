package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
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
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DM View and the Player View over REST (D157). A table of a DM and one player, whose character
 * acts first, against a visible 7 HP goblin and a 27 HP bugbear the DM has hidden.
 */
class PlayerViewIT extends IntegrationTest {

    @Autowired
    private MonsterRepository monsterRepository;
    @Autowired
    private SpellRepository spellRepository;
    @Autowired
    private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String characterId;
    private String goblinId;
    private String bugbearId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID aria = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        encounterId = createEncounter(dm.accessToken(), campaignId);

        characterId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");
        goblinId = addMonster("Goblin", 7);
        bugbearId = addMonster("Bugbear", 27);
        expect(HttpStatus.OK, setVisibility(bugbearId, false, dm.accessToken()));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", characterId, "initiative", 20),
                        Map.of("participantId", goblinId, "initiative", 10),
                        Map.of("participantId", bugbearId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("A player's GET leaves out the hidden monster and shows a Health Status for the visible one")
    void playerGetIsThePlayerView() {
        JsonNode encounter = getEncounter(player.accessToken());

        assertThat(participantIds(encounter)).containsExactly(characterId, goblinId);
        JsonNode goblin = participant(encounter, goblinId);
        assertThat(goblin.get("healthStatus").asText()).isEqualTo("UNHARMED");
        assertThat(goblin.get("hpCurrent").isNull()).isTrue();
        assertThat(goblin.get("hpMax").isNull()).isTrue();
        assertThat(goblin.get("monsterId").isNull()).isTrue();
        // The player's own character keeps its numbers, and its class gives its colour
        assertThat(participant(encounter, characterId).get("hpCurrent").isNull()).isFalse();
        assertThat(participant(encounter, characterId).get("healthStatus").isNull()).isTrue();
        assertThat(participant(encounter, characterId).get("characterClass").asText()).isEqualTo("Fighter");
        assertThat(goblin.get("characterClass").isNull()).isTrue();
        // The view's context stays on the server
        assertThat(encounter.has("dmUserId")).isFalse();
        assertThat(encounter.has("showExactMonsterHp")).isFalse();
    }

    @Test
    @DisplayName("The DM's GET is the DM View, with the hidden monster and exact HP")
    void dmGetIsTheDmView() {
        JsonNode encounter = getEncounter(dm.accessToken());

        assertThat(participantIds(encounter)).containsExactly(characterId, goblinId, bugbearId);
        assertThat(participant(encounter, bugbearId).get("isVisibleToPlayers").asBoolean()).isFalse();
        assertThat(participant(encounter, goblinId).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(participant(encounter, goblinId).get("healthStatus").isNull()).isTrue();
        assertThat(participant(encounter, characterId).get("characterClass").asText()).isEqualTo("Fighter");
        assertThat(participant(encounter, goblinId).get("characterClass").isNull()).isTrue();
    }

    @Test
    @DisplayName("A player's attack response is the Player View, with the goblin Bloodied")
    void playerAttackResponseIsThePlayerView() {
        // 15 hits the goblin's AC 12, and 4 flat damage leaves it on 3 of 7
        dice.script(15);
        JsonNode encounter = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/attack?actorId=" + characterId,
                Map.of("targetId", goblinId, "attackBonus", 0, "damageDice", "4"),
                player.accessToken()));

        assertThat(participantIds(encounter)).containsExactly(characterId, goblinId);
        assertThat(participant(encounter, goblinId).get("healthStatus").asText()).isEqualTo("BLOODIED");
        assertThat(participant(encounter, goblinId).get("hpCurrent").isNull()).isTrue();
        assertThat(participant(getEncounter(dm.accessToken()), goblinId).get("hpCurrent").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("A player forces a critical hit only while the campaign allows Manual Modifiers, and can't target "
            + "the hidden monster (D157, D164)")
    void playerAttackLimits() {
        expect(HttpStatus.OK, exchange(HttpMethod.PATCH, "/api/campaigns/" + campaignId + "/settings",
                Map.of("allowPlayerManualModifiers", false), dm.accessToken()));
        var forced = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + characterId,
                Map.of("targetId", goblinId, "weapon", "Unarmed Strike", "forceCrit", true), player.accessToken());
        var hidden = exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + characterId,
                Map.of("targetId", bugbearId, "weapon", "Unarmed Strike"), player.accessToken());

        assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(forced).get("error").asText())
                .isEqualTo("This campaign doesn't allow players to enter Manual Modifiers");
        // The same answer as for a participant that doesn't exist
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(hidden).get("error").asText()).isEqualTo("Participant not found");
        assertThat(participant(getEncounter(dm.accessToken()), goblinId).get("hpCurrent").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("A Fireball that hits the hidden bugbear leaves its name out of the players' log (D157)")
    void fireballOnAHiddenMonster() {
        spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB").effectTemplate("""
                {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
                 "halfOnSave": true, "concentration": false, "components": {"verbal": true},
                 "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire"}],
                 "conditionsInflicted": null}""").build());
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(characterId)).orElseThrow();
        aria.setSpellSlotsCurrent(Map.of("3", new SpellSlot(1, 0)));
        aria.setSpellsKnown(List.of(new KnownSpell(null, "Fireball", 3, "class:Sorcerer", null, null, null, null, null)));
        participantRepository.save(aria);

        // The DM casts it for Aria: 8 on 8d6, and both monsters fail their saves
        dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + characterId,
                Map.of("spellName", "Fireball", "slotLevel", 3, "targetIds", List.of(goblinId, bugbearId)),
                dm.accessToken()));

        JsonNode playerLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, player.accessToken()));
        JsonNode dmLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, dm.accessToken()));

        assertThat(playerLog.toString()).doesNotContain("Bugbear").doesNotContain(bugbearId);
        assertThat(descriptions(playerLog)).contains("Aria casts Fireball.")
                .anySatisfy(description -> assertThat(description).startsWith("Aria casts Fireball at Goblin:"));
        assertThat(descriptions(dmLog))
                .anySatisfy(description -> assertThat(description).startsWith("Aria casts Fireball at Bugbear:"));
        assertThat(participant(getEncounter(dm.accessToken()), bugbearId).get("hpCurrent").asInt()).isEqualTo(19);
    }

    @Test
    @DisplayName("Once the DM shows exact monster HP, players see the numbers and no Health Status")
    void exactMonsterHpSetting() {
        JsonNode settings = expect(HttpStatus.OK, updateSettings(true, dm.accessToken()));

        JsonNode encounter = getEncounter(player.accessToken());

        assertThat(settings.get("showExactMonsterHp").asBoolean()).isTrue();
        assertThat(participantIds(encounter)).containsExactly(characterId, goblinId);
        assertThat(participant(encounter, goblinId).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(participant(encounter, goblinId).get("hpMax").asInt()).isEqualTo(7);
        assertThat(participant(encounter, goblinId).get("healthStatus").isNull()).isTrue();
    }

    @Test
    @DisplayName("The Player View numbers the turns players can see, with no current turn while the hidden bugbear acts")
    void playerTurnOrderWhileTheHiddenMonsterActs() {
        nextTurn();
        JsonNode goblinsTurn = getEncounter(player.accessToken());
        assertThat(goblinsTurn.get("currentTurnIndex").asInt()).isEqualTo(1);
        assertThat(participant(goblinsTurn, goblinId).get("isCurrentTurn").asBoolean()).isTrue();

        nextTurn();
        JsonNode playerView = getEncounter(player.accessToken());
        JsonNode dmView = getEncounter(dm.accessToken());

        assertThat(turnNames(dmView)).containsExactly("Aria", "Goblin", "Bugbear");
        assertThat(dmView.get("currentTurnIndex").asInt()).isEqualTo(2);
        assertThat(participant(dmView, bugbearId).get("sortOrder").asInt()).isEqualTo(2);
        assertThat(turnNames(playerView)).containsExactly("Aria", "Goblin");
        assertThat(playerView.get("currentTurnIndex").isNull()).isTrue();
        assertThat(playerView.get("turnOrder").findValuesAsText("current")).containsOnly("false");
        assertThat(playerView.get("participants").findValuesAsText("isCurrentTurn")).containsOnly("false");
        assertThat(participant(playerView, characterId).get("sortOrder").asInt()).isZero();
        assertThat(participant(playerView, goblinId).get("sortOrder").asInt()).isEqualTo(1);
        // The players' log keeps the turns they can see, and never names the bugbear
        JsonNode playerLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, player.accessToken()));
        assertThat(descriptions(playerLog)).containsExactly("Turn passes to Goblin (Round 1)");
        assertThat(playerLog.toString()).doesNotContain("Bugbear").doesNotContain(bugbearId);

        nextTurn();
        assertThat(getEncounter(player.accessToken()).get("currentTurnIndex").asInt()).isZero();
    }

    @Test
    @DisplayName("Revealing the monster puts it back in the Player View")
    void revealingTheMonster() {
        expect(HttpStatus.OK, setVisibility(bugbearId, true, dm.accessToken()));

        assertThat(participantIds(getEncounter(player.accessToken()))).containsExactly(characterId, goblinId, bugbearId);
    }

    @Test
    @DisplayName("A player's combat log leaves out entries about the hidden monster")
    void playerLogLeavesOutTheHiddenMonster() {
        damage(goblinId, 2);
        damage(bugbearId, 5);

        JsonNode playerLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, player.accessToken()));
        JsonNode dmLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, dm.accessToken()));

        assertThat(descriptions(playerLog)).containsExactly("DM deals 2 damage to Goblin");
        assertThat(descriptions(dmLog)).containsExactly("DM deals 2 damage to Goblin", "DM deals 5 damage to Bugbear");
    }

    @Test
    @DisplayName("Only the DM can hide a monster or change the campaign's settings")
    void hidingAndSettingsAreDmOnly() {
        var hide = setVisibility(goblinId, false, player.accessToken());
        var settings = updateSettings(true, player.accessToken());

        assertThat(hide.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(hide).get("error").asText()).isEqualTo("Only the DM can perform this action");
        assertThat(settings.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(participantIds(getEncounter(player.accessToken()))).contains(goblinId);
    }

    @Test
    @DisplayName("A player character cannot be hidden, and a visibility request needs a value")
    void playerCharacterCannotBeHidden() {
        var hideCharacter = setVisibility(characterId, false, dm.accessToken());
        var noValue = exchange(HttpMethod.PATCH,
                "/api/encounters/" + encounterId + "/participants/" + goblinId + "/visibility", Map.of(),
                dm.accessToken());

        assertThat(hideCharacter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(hideCharacter).get("error").asText()).isEqualTo("Only monsters can be hidden from players");
        assertThat(noValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String addMonster(String name, int hitPoints) {
        Monster monster = monsterRepository.save(Monster.builder()
                .name(name).type("Humanoid").challengeRating("1").source("MM")
                .hitPoints(hitPoints).armourClass(12).dexterity(10)
                .build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", monster.getId(), "displayName", name)), name);
    }

    private ResponseEntity<String> setVisibility(String participantId, boolean visible, String token) {
        return exchange(HttpMethod.PATCH,
                "/api/encounters/" + encounterId + "/participants/" + participantId + "/visibility",
                Map.of("visible", visible), token);
    }

    private ResponseEntity<String> updateSettings(boolean showExactMonsterHp, String token) {
        return exchange(HttpMethod.PATCH, "/api/campaigns/" + campaignId + "/settings",
                Map.of("showExactMonsterHp", showExactMonsterHp), token);
    }

    private void damage(String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    private JsonNode getEncounter(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null, token));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private static List<String> turnNames(JsonNode encounter) {
        List<String> names = new ArrayList<>();
        encounter.get("turnOrder").forEach(turn -> names.add(turn.get("name").asText()));
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

    // In initiative order, as the response lists them
    private static List<String> participantIds(JsonNode encounter) {
        List<String> ids = new ArrayList<>();
        encounter.get("participants").forEach(participant -> ids.add(participant.get("id").asText()));
        return ids;
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }
}
