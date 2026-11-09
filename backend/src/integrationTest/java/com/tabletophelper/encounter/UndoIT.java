package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
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
 * The DM's undo, end to end (M14). A table of a DM and one player, whose wizard Aria acts first on 20 with a
 * Fireball, against a 60 HP cultist on 15 concentrating on Hold Person, which paralyzes a 7 HP goblin on 10, and a
 * 200 HP dragon on 5 with three uses of Legendary Resistance and a spent Fire Breath. Its recharge's d6 isn't one of
 * combat's scripted dice, so here it recharges on any roll.
 */
class UndoIT extends IntegrationTest {

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String cultistId;
    private String goblinId;
    private String dragonId;
    private int ariaHp;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Wizard", 6));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB").effectTemplate(SpellTemplate.parse("""
                {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
                 "halfOnSave": true, "concentration": false, "components": {"verbal": true},
                 "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire"}],
                 "conditionsInflicted": null}""")).build());

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        cultistId = addMonster("Cultist", 60);
        goblinId = addMonster("Goblin", 7);
        dragonId = addMonster("Dragon", 200);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", cultistId, "initiative", 15),
                        Map.of("participantId", goblinId, "initiative", 10),
                        Map.of("participantId", dragonId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));

        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("3", new SpellSlot(2, 0))));
        aria.setSpellSaveDc(15);
        aria.setSpellsKnown(List.of(new KnownSpell(null, "Fireball", 3, "class:Wizard", true, null, null, null, null)));
        ariaHp = participantRepository.save(aria).getHpCurrent();

        EncounterParticipant cultist = participantRepository.findById(UUID.fromString(cultistId)).orElseThrow();
        ConcentrationInstance holdPerson = ConcentrationInstance.start("Hold Person", 2, 1, 10);
        cultist.setConcentration(holdPerson);
        participantRepository.save(cultist);
        EncounterParticipant goblin = participantRepository.findById(UUID.fromString(goblinId)).orElseThrow();
        goblin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.fromEffect("paralyzed", null, 1,
                "Hold Person", cultist.getId(), holdPerson))));
        participantRepository.save(goblin);

        EncounterParticipant dragon = participantRepository.findById(UUID.fromString(dragonId)).orElseThrow();
        dragon.setResourcePoolsCurrent(new ArrayList<>(List.of(
                new ResourcePoolEntry("monster:legendary-resistance", "Legendary Resistance", "MONSTER", "Dragon",
                        3, "3", 3, "longRest", null, null, "FREE", "shield", null),
                new ResourcePoolEntry("monster:recharge:fire-breath", "Fire Breath", "MONSTER", "Dragon",
                        1, "1", 0, null, null, "1d6>=1", "ACTION", "flame", null))));
        participantRepository.save(dragon);
    }

    @Test
    @DisplayName("Undoing a Fireball restores the whole cascade: damage, the goblin's death, lost concentration and the "
            + "condition it held, and the slot")
    void fireballCascadeRestored() {
        // 8d6 rolls 32; every save and the concentration check roll 2 and fail
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 2, 2, 2, 2);
        castFireball(List.of(cultistId, goblinId));
        dice.clear();

        JsonNode after = dmState();
        assertThat(participant(after, cultistId).get("hpCurrent").asInt()).isEqualTo(28);
        assertThat(participant(after, cultistId).get("concentrationSpell").isNull()).isTrue();
        assertThat(participant(after, goblinId).get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(participant(after, ariaId).get("spellSlotsCurrent").get("3").get("used").asInt()).isEqualTo(1);
        JsonNode undo = after.get("undo");
        assertThat(undo.get("kind").asString()).isEqualTo("SPELL_CAST");
        assertThat(undo.get("summary").asString()).contains("Fireball");
        assertThat(undo.get("entryCount").asInt()).isGreaterThan(2);

        JsonNode restored = undo(undo.get("commandId").asString(), null);

        assertThat(participant(restored, cultistId).get("hpCurrent").asInt()).isEqualTo(60);
        assertThat(participant(restored, cultistId).get("concentrationSpell").asString()).isEqualTo("Hold Person");
        assertThat(participant(restored, goblinId).get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(participant(restored, goblinId).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(conditionNames(participant(restored, goblinId))).containsExactly("paralyzed");
        assertThat(participant(restored, ariaId).get("spellSlotsCurrent").get("3").get("used").asInt()).isEqualTo(0);
        assertThat(participant(restored, ariaId).get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(0);
        assertThat(restored.get("undo").isNull()).isTrue();

        // The DM's log keeps the Fireball's entries, undone; the players' log has only the UNDO entry
        JsonNode dmLog = log(dm.accessToken());
        String commandId = undo.get("commandId").asString();
        List<JsonNode> fireball = new ArrayList<>();
        dmLog.forEach(entry -> {
            if (commandId.equals(entry.path("commandId").asString(null))) {
                fireball.add(entry);
            }
        });
        assertThat(fireball).hasSize(undo.get("entryCount").asInt())
                .allSatisfy(entry -> assertThat(entry.get("undone").asBoolean()).isTrue());
        assertThat(descriptions(log(player.accessToken())))
                .containsExactly("The DM undoes: " + undo.get("summary").asString());
    }

    @Test
    @DisplayName("Undoing the damage that killed a player character brings it back alive with its hit points")
    void deathRestored() {
        damage(ariaId, 500);
        assertThat(participant(dmState(), ariaId).get("lifeState").asString()).isEqualTo("DEAD");

        JsonNode restored = undoNewest();

        assertThat(participant(restored, ariaId).get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(participant(restored, ariaId).get("hpCurrent").asInt()).isEqualTo(ariaHp);
        assertThat(conditionNames(participant(restored, ariaId))).isEmpty();
    }

    @Test
    @DisplayName("Undoing Next Turn gives back the turn, the round, the expired condition, the recharge and the budget")
    void nextTurnRestored() {
        turnTo(dragonId);
        EncounterParticipant dragon = participantRepository.findById(UUID.fromString(dragonId)).orElseThrow();
        dragon.setActiveConditions(new ArrayList<>(List.of(new ConditionEntry(UUID.randomUUID(), "dodging", null, 1,
                null, null, null, null, EndRule.startOfTurn(null), null))));
        TurnEconomy spent = new TurnEconomy(1, 1, true, 0, true, true, false, false, false, false, false, false);
        dragon.setTurnEconomy(spent);
        // Its turn just began, which rolled the recharge at random, so Fire Breath is spent again
        dragon.setResourcePoolsCurrent(new ArrayList<>(dragon.getResourcePoolsCurrent().stream()
                .map(pool -> pool.poolId().equals("monster:recharge:fire-breath") ? pool.withCurrentUses(0) : pool)
                .toList()));
        participantRepository.save(dragon);
        // Back to the goblin's turn, so the next turn is the dragon's
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/previous",
                null, dm.accessToken()));

        JsonNode advanced = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/turn/next", null, dm.accessToken()));
        assertThat(participant(advanced, dragonId).get("isCurrentTurn").asBoolean()).isTrue();
        assertThat(conditionNames(participant(advanced, dragonId))).doesNotContain("dodging");
        JsonNode recharge = null;
        for (JsonNode entry : log(dm.accessToken())) {
            if (entry.get("actionType").asString().startsWith("RECHARGE_")) {
                recharge = entry;
            }
        }
        assertThat(recharge).isNotNull();
        assertThat(pool(participant(advanced, dragonId), "monster:recharge:fire-breath").get("currentUses").asInt())
                .isEqualTo(1);
        assertThat(recharge.get("actionType").asString()).isEqualTo("RECHARGE_SUCCESS");
        assertThat(recharge.get("commandId").asString()).isEqualTo(advanced.get("undo").get("commandId").asString());
        assertThat(participant(advanced, dragonId).get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(0);
        assertThat(advanced.get("undo").get("kind").asString()).isEqualTo("TURN_ADVANCE");
        int round = advanced.get("roundNumber").asInt();

        JsonNode restored = undoNewest();

        assertThat(participant(restored, goblinId).get("isCurrentTurn").asBoolean()).isTrue();
        assertThat(restored.get("roundNumber").asInt()).isEqualTo(round);
        assertThat(conditionNames(participant(restored, dragonId))).containsExactly("dodging");
        assertThat(pool(participant(restored, dragonId), "monster:recharge:fire-breath").get("currentUses").asInt())
                .isEqualTo(0);
        assertThat(participant(restored, dragonId).get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        assertThat(participant(restored, dragonId).get("turnEconomy").get("reactionUsed").asBoolean()).isTrue();
        assertThat(log(dm.accessToken()).get(entryIndex(log(dm.accessToken()), recharge.get("id").asString()))
                .get("undone").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Undo to here takes several actions back, newest first, writing an UNDO entry for each")
    void multiStep() {
        damage(goblinId, 2);
        damage(cultistId, 3);
        damage(goblinId, 1);
        JsonNode undo = dmState().get("undo");
        assertThat(undo.get("stepsAvailable").asInt()).isEqualTo(3);
        assertThat(undo.get("recent")).hasSize(3);

        JsonNode restored = undo(undo.get("commandId").asString(), 3);

        assertThat(participant(restored, goblinId).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(participant(restored, cultistId).get("hpCurrent").asInt()).isEqualTo(60);
        assertThat(descriptions(log(dm.accessToken())).stream().filter(d -> d.startsWith("The DM undoes: ")))
                .containsExactly("The DM undoes: DM deals 1 damage to Goblin", "The DM undoes: DM deals 3 damage to Cultist",
                        "The DM undoes: DM deals 2 damage to Goblin");
        assertThat(restored.get("undo").isNull()).isTrue();
    }

    @Test
    @DisplayName("An undo naming an action that is no longer the newest is refused with 409 and changes nothing")
    void staleExpectedCommandIsRefused() {
        damage(goblinId, 2);
        String reviewed = dmState().get("undo").get("commandId").asString();
        damage(goblinId, 1);

        ResponseEntity<String> response = undoRequest(reviewed, null, dm.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("error").asString()).contains("review it again");
        assertThat(participant(dmState(), goblinId).get("hpCurrent").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("Players can't undo: 403")
    void playerIsForbidden() {
        damage(goblinId, 2);
        String newest = dmState().get("undo").get("commandId").asString();

        assertThat(undoRequest(newest, null, player.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(participant(dmState(), goblinId).get("hpCurrent").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("A paused or completed encounter can't undo (409), and completing it clears the before-states")
    void pausedAndCompletedAreRefused() {
        damage(goblinId, 2);
        String newest = dmState().get("undo").get("commandId").asString();

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/pause", null,
                dm.accessToken()));
        assertThat(dmState().get("undo").isNull()).isTrue();
        assertThat(undoRequest(newest, null, dm.accessToken()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", false), dm.accessToken()));
        assertThat(undoRequest(newest, null, dm.accessToken()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM combat_commands WHERE encounter_id = ? "
                + "AND before_state IS NOT NULL", Integer.class, encounterId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM combat_commands WHERE encounter_id = ?",
                Integer.class, encounterId)).isEqualTo(2);
        assertThat(participant(dmState(), goblinId).get("hpCurrent").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("Removing a participant mid-fight is a barrier: nothing before it can be undone")
    void barrierHolds() {
        damage(goblinId, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.DELETE,
                "/api/encounters/" + encounterId + "/participants/" + cultistId, null, dm.accessToken()));
        String earlier = jdbcTemplate.queryForObject("SELECT id::text FROM combat_commands WHERE encounter_id = ? "
                + "AND sequence = 2", String.class, encounterId);

        assertThat(dmState().get("undo").isNull()).isTrue();
        ResponseEntity<String> response = undoRequest(earlier, null, dm.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("error").asString()).isEqualTo("Nothing to undo");

        // What comes after the barrier can still be undone
        damage(goblinId, 1);
        assertThat(participant(undoNewest(), goblinId).get("hpCurrent").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("Undoing Legendary Resistance leaves its entry unresolved with the use back, and an undone save "
            + "can't take Legendary Resistance")
    void legendaryResistanceUndone() {
        // 8d6 rolls 32, and the dragon's DEX save of 3 fails against DC 15
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 3);
        castFireball(List.of(dragonId));
        String entryId = dragonFireballEntry().get("id").asString();
        JsonNode resisted = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entryId), dm.accessToken()));
        assertThat(participant(resisted, dragonId).get("hpCurrent").asInt()).isEqualTo(184);
        assertThat(dragonFireballEntry().get("lrResolved").asBoolean()).isTrue();
        assertThat(resisted.get("undo").get("kind").asString()).isEqualTo("LEGENDARY_RESISTANCE_USED");

        JsonNode restored = undoNewest();

        assertThat(participant(restored, dragonId).get("hpCurrent").asInt()).isEqualTo(168);
        assertThat(pool(participant(restored, dragonId), "monster:legendary-resistance").get("currentUses").asInt())
                .isEqualTo(3);
        assertThat(dragonFireballEntry().get("lrResolved").asBoolean()).isFalse();

        // The Fireball itself undone, Legendary Resistance can't be used on its entry
        undoNewest();
        ResponseEntity<String> refused = exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/legendary-resistance",
                Map.of("combatLogId", entryId), dm.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(participant(dmState(), dragonId).get("hpCurrent").asInt()).isEqualTo(200);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String addMonster(String name, int hitPoints) {
        Monster monster = monsterRepository.save(Monster.builder()
                .name(name).type("Humanoid").challengeRating("1").source("MM")
                .hitPoints(hitPoints).armourClass(12).dexterity(10).constitution(10)
                .build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", monster.getId(), "displayName", name)), name);
    }

    private void castFireball(List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", "Fireball", "slotLevel", 3, "targetIds", targetIds), player.accessToken()));
    }

    private void damage(String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    // Advances until it is the participant's turn
    private void turnTo(String participantId) {
        for (int i = 0; i < 8 && !participant(dmState(), participantId).get("isCurrentTurn").asBoolean(); i++) {
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                    null, dm.accessToken()));
        }
        assertThat(participant(dmState(), participantId).get("isCurrentTurn").asBoolean()).isTrue();
    }

    private ResponseEntity<String> undoRequest(String expectedCommandId, Integer steps, String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedCommandId", expectedCommandId);
        if (steps != null) {
            body.put("steps", steps);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo", body, token);
    }

    private JsonNode undo(String expectedCommandId, Integer steps) {
        return expect(HttpStatus.OK, undoRequest(expectedCommandId, steps, dm.accessToken()));
    }

    private JsonNode undoNewest() {
        return undo(dmState().get("undo").get("commandId").asString(), null);
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null, dm.accessToken()));
    }

    private JsonNode log(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                token));
    }

    // The dragon's entry for the Fireball
    private JsonNode dragonFireballEntry() {
        for (JsonNode entry : log(dm.accessToken())) {
            if (dragonId.equals(entry.path("targetId").asString(null))
                    && "SPELL_CAST".equals(entry.get("actionType").asString())) {
                return entry;
            }
        }
        throw new AssertionError("The dragon has no Fireball entry");
    }

    private static int entryIndex(JsonNode log, String id) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).get("id").asString().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("Entry " + id + " is not in the log");
    }

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId);
    }

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant.get("activeConditions");
        if (conditions != null && conditions.isArray()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
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
