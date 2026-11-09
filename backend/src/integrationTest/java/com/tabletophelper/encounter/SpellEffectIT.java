package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.SpellEffectEntry;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spell Effects, end to end (M15). A table of a DM and one player, whose cleric Aria acts first on 20 with Spirit
 * Guardians, Spiritual Weapon and Moonbeam, against a 40 HP ogre on 10.
 */
class SpellEffectIT extends IntegrationTest {

    private static final String SPIRITUAL_WEAPON = """
            {"spellLevel": 2, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE", "concentration": false,
             "durationRounds": 10, "repeatEffect": true, "repeatActionType": "BONUS_ACTION",
             "persistsWithoutConcentration": true,
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "force"}]}""";

    private static final String SPIRIT_GUARDIANS = """
            {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "halfOnSave": true,
             "concentration": true, "durationRounds": 100, "effectTriggers": [{"when": ["ENTER", "START_OF_TURN"]}],
             "effects": [{"effectType": "DAMAGE", "damageDice": "3d8", "damageType": "radiant"}]}""";

    private static final String MOONBEAM = """
            {"spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
             "concentration": true, "durationRounds": 10, "repeatEffect": true, "repeatActionType": "ACTION",
             "directedResolves": false, "effectTriggers": [{"when": ["ENTER", "START_OF_TURN"]}],
             "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "radiant"}]}""";

    // Searing Smite as the templates have it: the burning creature's CON save at the start of each of its turns,
    // which ends the spell on a success (PHB p.274)
    private static final String SEARING_SMITE = """
            {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "concentration": true,
             "durationRounds": 10, "requiresManualResolution": true,
             "manualResolutionReason": "Smite-style delivery on next weapon hit.",
             "effectTriggers": [{"when": ["START_OF_TURN"], "deliveryMethod": "SAVING_THROW", "saveAbility": "CON",
               "halfOnSave": false, "damageDice": "1d6", "damageType": "fire", "usesUpcastScaling": false,
               "endsSpellOnSave": true}],
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "fire"}]}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Dwarf"), insertClass("Cleric", 8));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        spell("Spiritual Weapon", 2, SPIRITUAL_WEAPON, "1 bonus action");
        spell("Spirit Guardians", 3, SPIRIT_GUARDIANS, "1 action");
        spell("Moonbeam", 2, MOONBEAM, "1 action");
        spell("Searing Smite", 1, SEARING_SMITE, "1 bonus action");

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder()
                .name("Ogre").type("Giant").challengeRating("2").source("MM")
                .hitPoints(40).armourClass(11).dexterity(8).constitution(16).wisdom(7)
                .build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));

        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(30);
        aria.setHpCurrent(30);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(2, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellsKnown(List.of(
                new KnownSpell(null, "Spiritual Weapon", 2, "class:Cleric", true, null, null, null, null),
                new KnownSpell(null, "Spirit Guardians", 3, "class:Cleric", true, null, null, null, null),
                new KnownSpell(null, "Moonbeam", 2, "class:Cleric", true, null, null, null, null),
                new KnownSpell(null, "Searing Smite", 1, "class:Cleric", true, null, null, null, null)));
        participantRepository.save(aria);
    }

    @Test
    @DisplayName("A cleric keeping up Spirit Guardians attacks with Spiritual Weapon on a later turn without naming it, and the Player View carries both effects")
    void clericCombo() {
        // Spirit Guardians' area damage is rolled once though nobody is in it yet; the weapon hits for 5
        dice.script(1, 1, 1);
        cast("Spirit Guardians", 3, List.of());
        dice.script(15, 5);
        cast("Spiritual Weapon", 2, List.of(ogreId));
        nextTurn();
        nextTurn();

        dice.script(14, 6);
        JsonNode used = expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogreId)), player.accessToken()));

        assertThat(used.get("spellName").asString()).isEqualTo("Spiritual Weapon");
        JsonNode aria = participant(used.get("encounterState"), ariaId);
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(29);
        assertThat(aria.get("concentrationSpell").asString()).isEqualTo("Spirit Guardians");
        JsonNode effects = aria.get("activeEffects");
        assertThat(effects).hasSize(2);
        JsonNode guardians = effects.get(0);
        assertThat(guardians.get("spell").asString()).isEqualTo("Spirit Guardians");
        assertThat(guardians.get("concentration").asBoolean()).isTrue();
        assertThat(guardians.get("directedCost").isNull()).isTrue();
        assertThat(guardians.get("triggers").toString()).isEqualTo("[\"ENTER\",\"START_OF_TURN\"]");
        JsonNode weapon = effects.get(1);
        assertThat(weapon.get("spell").asString()).isEqualTo("Spiritual Weapon");
        assertThat(weapon.get("concentration").asBoolean()).isFalse();
        assertThat(weapon.get("directedCost").asString()).isEqualTo("BONUS_ACTION");
        assertThat(weapon.get("slotLevel").asInt()).isEqualTo(2);
        assertThat(weapon.get("durationRounds").asInt()).isEqualTo(10);
        assertThat(aria.get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Moonbeam's action moves the beam at no damage; the DM applies it to the ogre starting its turn there, which the player may not")
    void moonbeam() {
        dice.script(1, 1);
        cast("Moonbeam", 2, List.of());
        nextTurn();
        nextTurn();

        JsonNode moved = expect(HttpStatus.OK, use(Map.of("targetIds", List.of()), player.accessToken()));
        expect(HttpStatus.BAD_REQUEST, use(Map.of("targetIds", List.of(ogreId)), player.accessToken()));

        assertThat(moved.get("resultSummary").asString()).isEqualTo("Aria moves Moonbeam.");
        assertThat(participant(moved.get("encounterState"), ariaId).get("turnEconomy").get("actionsUsed").asInt())
                .isEqualTo(1);
        nextTurn();
        Map<String, Object> startOfTurn = Map.of("targetIds", List.of(ogreId), "trigger", "START_OF_TURN");
        expect(HttpStatus.FORBIDDEN, use(startOfTurn, player.accessToken()));
        // 2d10 rolls 12, and the ogre's CON save of 5 + 3 fails against DC 14
        dice.script(6, 6, 5);
        expect(HttpStatus.OK, use(startOfTurn, dm.accessToken()));

        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("Ending Spiritual Weapon by hand is undone with the effect it ended, through the stored before-state")
    void endAndUndo() {
        dice.script(15, 5);
        cast("Spiritual Weapon", 2, List.of(ogreId));
        String weaponId = participant(dmState(), ariaId).get("activeEffects").get(0).get("id").asString();

        JsonNode ended = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/spell-effect/end",
                Map.of("participantId", ariaId, "effectId", weaponId), player.accessToken()));
        assertThat(participant(ended, ariaId).get("activeEffects")).isEmpty();

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", dmState().get("undo").get("commandId").asString()), dm.accessToken()));

        JsonNode restored = participant(dmState(), ariaId).get("activeEffects");
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).get("id").asString()).isEqualTo(weaponId);
    }

    @Test
    @DisplayName("The ogre's successful CON save against Searing Smite's burning ends the spell and Aria's "
            + "concentration (PHB p.274); undo brings both back")
    void searingSmiteEndsOnSave() {
        cast("Searing Smite", 1, List.of(ogreId));
        JsonNode cast = participant(dmState(), ariaId);
        assertThat(cast.get("concentrationSpell").asString()).isEqualTo("Searing Smite");
        assertThat(cast.get("activeEffects")).hasSize(1);
        nextTurn();

        // 1d6 rolls 4; the ogre's CON save of 12 + 3 beats DC 14
        dice.script(4, 12);
        JsonNode burned = expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogreId), "trigger", "START_OF_TURN"),
                dm.accessToken()));

        JsonNode aria = participant(burned.get("encounterState"), ariaId);
        assertThat(aria.get("activeEffects")).isEmpty();
        assertThat(aria.get("concentrationSpell").isNull()).isTrue();
        assertThat(participant(burned.get("encounterState"), ogreId).get("hpCurrent").asInt()).isEqualTo(40);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", dmState().get("undo").get("commandId").asString()), dm.accessToken()));
        JsonNode restored = participant(dmState(), ariaId);
        assertThat(restored.get("concentrationSpell").asString()).isEqualTo("Searing Smite");
        assertThat(restored.get("activeEffects")).hasSize(1);
    }

    @Test
    @DisplayName("A dead ogre's Spiritual Weapon runs out at the top of the round its duration passes, though its "
            + "turns are skipped; undoing the turn brings it back")
    void deadCastersEffectExpires() {
        EncounterParticipant ogre = participantRepository.findById(UUID.fromString(ogreId)).orElseThrow();
        ogre.setActiveEffects(List.of(SpellEffectEntry.start("Spiritual Weapon", 2, null, 1, 1, null)));
        participantRepository.save(ogre);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ogreId, "amount", 40), dm.accessToken()));
        assertThat(participant(dmState(), ogreId).get("lifeState").asString()).isEqualTo("DEAD");

        // Aria's turn ends, the ogre's is skipped, and round 2 starts
        nextTurn();

        JsonNode state = dmState();
        assertThat(state.get("roundNumber").asInt()).isEqualTo(2);
        assertThat(participant(state, ogreId).get("activeEffects")).isEmpty();

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", state.get("undo").get("commandId").asString()), dm.accessToken()));
        assertThat(participant(dmState(), ogreId).get("activeEffects")).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void spell(String name, int level, String template, String castingTime) {
        spellRepository.save(Spell.builder().name(name).level(level).source("PHB").castingTime(castingTime)
                .effectTemplate(SpellTemplate.parse(template)).build());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", spell, "slotLevel", slotLevel, "targetIds", targetIds), dm.accessToken()));
    }

    private ResponseEntity<String> use(Map<String, Object> body, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/repeat-spell-effect?actorId="
                + ariaId, body, token);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
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
}
