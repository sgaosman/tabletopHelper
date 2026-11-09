package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
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
 * The action economy charged by the combat commands, end to end (D174). A player controls Bram, a Fighter 5 with
 * Extra Attack, and Cora, a wizard with Healing Word, Fireball, Fire Bolt and Identify; they go first and second,
 * then a goblin with plenty of hit points.
 */
class ActionEconomyIT extends IntegrationTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}]}""";

    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire"}]}""";

    private static final String HEALING_WORD = """
            {"spellName": "Healing Word", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
             "healing": {"healingDice": "1d4"}}""";

    // A bonus action and a reaction that recharge on a d6, and a reaction the DM applies by hand
    private static final String GOBLIN_TEMPLATES = """
            {"actions": [],
             "bonusActions": [{"name": "Smoke Burst", "recharge": "5-6", "automatable": false,
                               "description": "The goblin vanishes in a puff of smoke."}],
             "reactions": [{"name": "Parry", "automatable": false,
                            "description": "The goblin adds 2 to its AC against one melee attack that would hit it.",
                            "dmNote": "+2 AC against one melee hit: if it now misses, the DM restores its damage by hand."},
                           {"name": "Shock Ward", "recharge": "6", "automatable": false,
                            "description": "The goblin sparks at a creature that hits it."}]}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String bramId;
    private String coraId;
    private String goblinId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("bram");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID race = insertRace("Human");
        UUID bram = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Bram", "raceId", race, "classId", insertClass("Fighter", 10), "level", 5,
                "strength", 16, "dexterity", 12, "constitution", 14, "intelligence", 8, "wisdom", 10,
                "charisma", 10), player.accessToken())).get("id").asString());
        UUID cora = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Cora", "raceId", race, "classId", insertClass("Wizard", 6), "strength", 8, "dexterity", 14,
                "constitution", 12, "intelligence", 16, "wisdom", 10, "charisma", 10), player.accessToken()))
                .get("id").asString());
        spell("Fire Bolt", 0, "1 action", FIRE_BOLT);
        spell("Fireball", 3, "1 action", FIREBALL);
        spell("Healing Word", 1, "1 bonus action", HEALING_WORD);
        spell("Identify", 1, "1 minute", null);

        encounterId = createEncounter(dm.accessToken(), campaignId);
        bramId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", bram, "displayName", "Bram")), "Bram");
        coraId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", cora, "displayName", "Cora")), "Cora");
        Monster goblin = monsterRepository.save(Monster.builder().name("Goblin").type("Humanoid")
                .challengeRating("1/4").source("MM").hitPoints(200).armourClass(15).dexterity(14)
                .actionTemplates(MonsterTemplates.parse(GOBLIN_TEMPLATES)).build());
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")),
                "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", bramId, "initiative", 20),
                        Map.of("participantId", coraId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant wizard = participantRepository.findById(UUID.fromString(coraId)).orElseThrow();
        wizard.setHpMax(30);
        wizard.setHpCurrent(20);
        wizard.setSpellSaveDc(13);
        wizard.setSpellAttackBonus(5);
        wizard.setSpellSlotsCurrent(Map.of("1", new SpellSlot(4, 0), "3", new SpellSlot(2, 0)));
        wizard.setSpellsKnown(List.of(known("Fire Bolt", 0), known("Fireball", 3), known("Healing Word", 1),
                known("Identify", 1)));
        participantRepository.save(wizard);
    }

    @Test
    @DisplayName("A Fighter 5 attacks twice in one Attack action, and a third attack is a 409 that rolls nothing")
    void extraAttack() {
        assertThat(participant(dmView(), bramId).get("attacksPerAction").asInt()).isEqualTo(2);

        for (int i = 0; i < 2; i++) {
            dice.script(15, 3);
            expect(HttpStatus.OK, attack(bramId, Map.of("targetId", goblinId, "attackBonus", 5, "damageDice", "1d6"),
                    player.accessToken()));
        }
        ResponseEntity<String> third = attack(bramId,
                Map.of("targetId", goblinId, "attackBonus", 5, "damageDice", "1d6"), player.accessToken());

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(third).get("error").asString())
                .isEqualTo("It has no attacks left in its Attack action and no action left this turn");
        JsonNode bram = participant(dmView(), bramId);
        assertThat(participant(dmView(), goblinId).get("hpCurrent").asInt()).isEqualTo(200 - 6);
        assertThat(bram.get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        assertThat(bram.get("turnEconomy").get("attackActionTaken").asBoolean()).isTrue();
        assertThat(bram.get("turnEconomy").get("attacksLeft").asInt()).isZero();
    }

    @Test
    @DisplayName("After Healing Word, Fireball is a 409 that spends no slot and Fire Bolt is allowed; a spell longer "
            + "than a turn is a 409")
    void bonusActionSpell() {
        nextTurn();
        ResponseEntity<String> identify = castSpell(coraId, Map.of("spellName", "Identify", "slotLevel", 1,
                "targetIds", List.of(coraId)), player.accessToken());
        assertThat(identify.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(identify).get("error").asString())
                .isEqualTo("Identify takes longer than a turn to cast, so it can't be cast in combat");

        dice.script(3);
        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Healing Word", "slotLevel", 1,
                "targetIds", List.of(coraId)), player.accessToken()));
        ResponseEntity<String> fireball = castSpell(coraId, Map.of("spellName", "Fireball", "slotLevel", 3,
                "targetIds", List.of(goblinId)), player.accessToken());
        dice.script(15, 6);
        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Fire Bolt", "slotLevel", 0,
                "targetIds", List.of(goblinId)), player.accessToken()));

        assertThat(fireball.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(fireball).get("error").asString()).isEqualTo("It cast a spell as a bonus action this turn, "
                + "so the only other spell it can cast is a cantrip with a casting time of 1 action");
        JsonNode cora = participant(dmView(), coraId);
        assertThat(cora.get("spellSlotsCurrent").get("1").get("used").asInt()).isEqualTo(1);
        assertThat(cora.get("spellSlotsCurrent").get("3").get("used").asInt()).isZero();
        assertThat(cora.get("hpCurrent").asInt()).isEqualTo(23);
        assertThat(participant(dmView(), goblinId).get("hpCurrent").asInt()).isEqualTo(200 - 6);
        assertThat(descriptions(dmLog())).contains("Cora casts Healing Word (bonus action).");
    }

    @Test
    @DisplayName("On the goblin's turn the player's opportunity attack is allowed, a second is a 409, and an ordinary "
            + "attack a 403; the DM's goes over and is logged")
    void opportunityAttack() {
        nextTurn();
        nextTurn();
        Map<String, Object> request = Map.of("targetId", goblinId, "attackBonus", 5, "damageDice", "1d6",
                "cost", "REACTION");

        dice.script(15, 3);
        expect(HttpStatus.OK, attack(bramId, request, player.accessToken()));
        ResponseEntity<String> second = attack(bramId, request, player.accessToken());
        ResponseEntity<String> ordinary = attack(bramId,
                Map.of("targetId", goblinId, "attackBonus", 5, "damageDice", "1d6"), player.accessToken());
        dice.script(15, 3);
        expect(HttpStatus.OK, attack(bramId, request, dm.accessToken()));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(second).get("error").asString())
                .isEqualTo("It has already taken its reaction; it comes back at the start of its turn");
        assertThat(ordinary.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(participant(dmView(), goblinId).get("hpCurrent").asInt()).isEqualTo(200 - 6);
        assertThat(participant(dmView(), bramId).get("turnEconomy").get("reactionUsed").asBoolean()).isTrue();
        assertThat(descriptions(dmLog())).contains(
                "Bram attacks (reaction) Goblin: d20(15) + 5 = 20 vs AC 15 — hit, 3 damage.",
                "Bram's attack (reaction) is over the action economy: it has already taken its reaction; it comes "
                        + "back at the start of its turn");
    }

    @Test
    @DisplayName("A monster's reaction on Bram's turn is logged as REACTION_TAKEN; its d6-recharge bonus action is "
            + "a 409 once spent; players read its reaction pool's use without what it has left (D157, D174)")
    void monsterReactionAndRechargeBonusAction() {
        expect(HttpStatus.OK, monsterAction("Parry", "REACTION"));
        nextTurn();
        nextTurn();
        expect(HttpStatus.OK, monsterAction("Smoke Burst", "BONUS"));
        ResponseEntity<String> again = monsterAction("Smoke Burst", "BONUS");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool",
                Map.of("participantId", goblinId, "poolId", "monster:recharge:shock-ward"), dm.accessToken()));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).get("error").asString()).isEqualTo("Recharge action 'Smoke Burst' is not yet recharged");
        JsonNode dmLog = dmLog();
        JsonNode parry = entry(dmLog, "Goblin uses Parry (reaction)");
        assertThat(parry.get("actionType").asString()).isEqualTo("REACTION_TAKEN");
        assertThat(entry(dmLog, "Goblin uses Smoke Burst (bonus action) —").get("actionType").asString())
                .isEqualTo("MONSTER_ACTION");
        JsonNode dmPoolUse = entry(dmLog, "Goblin uses Shock Ward (reaction)");
        assertThat(dmPoolUse.get("description").asString()).isEqualTo("Goblin uses Shock Ward (reaction); 0/1 left");
        assertThat(dmPoolUse.get("actionType").asString()).isEqualTo("POOL_USE");
        assertThat(participant(dmView(), goblinId).get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();
        JsonNode playerLog = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId + "/combat/log", null, player.accessToken()));
        assertThat(descriptions(playerLog)).contains("Goblin uses Shock Ward (reaction)")
                .noneMatch(description -> description.contains(" left"));
        assertThat(entry(playerLog, "Goblin uses Parry (reaction)").get("actionType").asString())
                .isEqualTo("REACTION_TAKEN");
    }

    private ResponseEntity<String> monsterAction(String name, String source) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", goblinId, "actionName", name, "actionSource", source,
                        "targetParticipantIds", List.of()), dm.accessToken());
    }

    // The first entry whose description starts with the text
    private static JsonNode entry(JsonNode log, String start) {
        for (JsonNode entry : log) {
            if (entry.get("description").asString().startsWith(start)) {
                return entry;
            }
        }
        throw new AssertionError("No log entry starts with " + start + ": " + log);
    }

    private void spell(String name, int level, String castingTime, String template) {
        spellRepository.save(Spell.builder().name(name).level(level).source("PHB").castingTime(castingTime)
                .effectTemplate(SpellTemplate.parse(template)).build());
    }

    private static KnownSpell known(String name, int level) {
        return new KnownSpell(null, name, level, "class:Wizard", true, null, null, null, null);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private ResponseEntity<String> attack(String actorId, Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + actorId,
                new HashMap<>(request), token);
    }

    private ResponseEntity<String> castSpell(String casterId, Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + casterId,
                new HashMap<>(request), token);
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

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
