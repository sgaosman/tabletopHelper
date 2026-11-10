package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lasting area spells end to end (D199), with their templates as the game data ships them. A DM and one player,
 * whose cleric Aria (spell attack +6, save DC 14, WIS spellcasting, 30 HP) acts first on 20, against an ogre (60 HP,
 * DEX 8, CON 16, INT and WIS 7) acting after her. There is no map: the DM picks the creatures in an area at the cast
 * and applies the area to a creature on its turn through the Spell Effect's triggers.
 */
class AreaSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Stinking Cloud", "Sleet Storm", "Hunger of Hadar",
            "Tasha's Caustic Brew", "Gust of Wind", "Spike Growth", "Healing Spirit", "Cordon of Arrows",
            "Nathair's Mischief", "Wind Wall", "Phantasmal Force", "Aura of Vitality", "Spirit Guardians", "Moonbeam");

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
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Cleric", 8));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("giant").challengeRating("2")
                .source("MM").hitPoints(60).armourClass(11).strength(19).dexterity(8).constitution(16)
                .intelligence(7).wisdom(7).charisma(7).build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        Map<String, Integer> initiatives = new LinkedHashMap<>(Map.of(ariaId, 20, ogreId, 10));
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(30);
        aria.setHpCurrent(30);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0), "4", new SpellSlot(3, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellcastingAbility("WIS");
        List<KnownSpell> known = new ArrayList<>();
        for (String spell : SHIPPED) {
            int level = spellRepository.findByNameIgnoreCase(spell).orElseThrow().getLevel();
            known.add(new KnownSpell(null, spell, level, "class:Cleric", true, null, null, null, null));
        }
        aria.setSpellsKnown(known);
        participantRepository.save(aria);
    }

    @Test
    @DisplayName("Stinking Cloud's cast rolls nothing; the DM applies it to the ogre starting its turn, whose failed CON "
            + "save spends its action retching (PHB p.278)")
    void stinkingCloud() {
        JsonNode cast = expect(HttpStatus.OK, castRequest("Stinking Cloud", 3, List.of(ogreId), null));
        assertThat(cast.get("resultSummary").asString()).contains("Nothing is rolled yet");
        assertThat(hp(ogreId)).isEqualTo(60);
        assertThat(participant(dmState(), ariaId).get("activeEffects").get(0).get("triggers").toString())
                .isEqualTo("[\"START_OF_TURN\"]");
        nextTurn();

        // The ogre's CON save of 5 + 3 fails against DC 14
        dice.script(5);
        expect(HttpStatus.OK, trigger(Map.of("targetIds", List.of(ogreId), "trigger", "START_OF_TURN")));

        JsonNode ogre = participant(dmState(), ogreId);
        assertThat(ogre.get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        assertThat(log()).anyMatch(line -> line.equals("Ogre spends its action this turn retching and reeling."));
    }

    @Test
    @DisplayName("Spike Growth's thorns, applied for 10 feet of movement, deal 4d4 piercing in one roll (PHB p.277)")
    void spikeGrowth() {
        expect(HttpStatus.OK, castRequest("Spike Growth", 2, List.of(), null));
        // Its ENTER trigger counts 5-foot moves, so the DM's trigger control asks how many (D200)
        JsonNode effect = participant(dmState(), ariaId).get("activeEffects").get(0);
        assertThat(effect.get("repeatableTriggers")).extracting(JsonNode::asString).containsExactly("ENTER");
        assertThat(effect.get("usableByIds")).isEmpty();
        dice.script(2, 2, 2, 2);
        expect(HttpStatus.OK, trigger(Map.of("targetIds", List.of(ogreId), "trigger", "ENTER", "uses", 2)));
        assertThat(hp(ogreId)).isEqualTo(52);
    }

    @Test
    @DisplayName("Tasha's Caustic Brew covers the ogre in acid on a failed DEX save, with no damage at the cast; its "
            + "next turn's start deals the upcast 4d4 acid by itself (TCE p.115)")
    void causticBrew() {
        // DEX save of 3 - 1 fails against DC 14
        dice.script(3);
        expect(HttpStatus.OK, castRequest("Tasha's Caustic Brew", 2, List.of(ogreId), null));
        assertThat(hp(ogreId)).isEqualTo(60);
        assertThat(conditionNames(ogreId)).contains("covered in acid");

        dice.script(1, 2, 3, 4);
        nextTurn();
        assertThat(hp(ogreId)).isEqualTo(50);
    }

    @Test
    @DisplayName("Hunger of Hadar blinds the ogre at the cast with no roll; Sleet Storm's failed DEX save knocks it "
            + "prone, and the prone stays when the storm's concentration ends (PHB p.251, p.276)")
    void hungerAndSleet() {
        expect(HttpStatus.OK, castRequest("Hunger of Hadar", 3, List.of(ogreId), null));
        assertThat(hp(ogreId)).isEqualTo(60);
        assertThat(conditionNames(ogreId)).contains("blinded");
        nextTurn();
        nextTurn();

        dice.script(2);
        expect(HttpStatus.OK, castRequest("Sleet Storm", 3, List.of(ogreId), null));
        // Sleet Storm ended Hunger of Hadar's concentration, and with it the blindness
        assertThat(conditionNames(ogreId)).contains("prone").doesNotContain("blinded");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/concentration",
                Map.of("participantId", ariaId), dm.accessToken()));
        assertThat(conditionNames(ogreId)).contains("prone");
    }

    @Test
    @DisplayName("Healing Spirit heals Aria on entering it, spending one of its heals (XGE p.157); Cordon of Arrows "
            + "starts with 6 pieces at 3rd level, spending one on the ogre (PHB p.228)")
    void usesBudgets() {
        aria(participant -> participant.setHpCurrent(10));
        expect(HttpStatus.OK, castRequest("Healing Spirit", 2, List.of(), null));
        int heals = participant(dmState(), ariaId).get("activeEffects").get(0).get("usesLeft").asInt();
        assertThat(heals).isGreaterThanOrEqualTo(2);
        dice.script(4);
        expect(HttpStatus.OK, trigger(Map.of("targetIds", List.of(ariaId), "trigger", "ENTER")));
        assertThat(hp(ariaId)).isEqualTo(14);
        assertThat(participant(dmState(), ariaId).get("activeEffects").get(0).get("usesLeft").asInt())
                .isEqualTo(heals - 1);
        nextTurn();
        nextTurn();

        expect(HttpStatus.OK, castRequest("Cordon of Arrows", 3, List.of(), null));
        JsonNode cordon = effect(participant(dmState(), ariaId), "Cordon of Arrows");
        assertThat(cordon.get("usesLeft").asInt()).isEqualTo(6);
        // 1d6 rolls 5; the ogre's DEX save of 4 - 1 fails
        dice.script(5, 4);
        expect(HttpStatus.OK, trigger(Map.of("targetIds", List.of(ogreId), "trigger", "ENTER",
                "effectId", cordon.get("id").asString())));
        assertThat(hp(ogreId)).isEqualTo(55);
        assertThat(effect(participant(dmState(), ariaId), "Cordon of Arrows").get("usesLeft").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("Phantasmal Force gives no damage at the cast; the ogre's INT (Investigation) check with its action "
            + "ends the spell on a success (PHB p.264)")
    void phantasmalForce() {
        // INT save of 3 - 2 fails against DC 14
        dice.script(3);
        expect(HttpStatus.OK, castRequest("Phantasmal Force", 2, List.of(ogreId), null));
        assertThat(hp(ogreId)).isEqualTo(60);
        String phantasm = null;
        for (JsonNode condition : participant(dmState(), ogreId).get("activeConditions")) {
            if (condition.get("name").asString().equals("phantasm")) {
                phantasm = condition.get("id").asString();
            }
        }
        assertThat(phantasm).isNotNull();
        // The response names the INT (Investigation) check its action is spent on (D200)
        for (JsonNode condition : participant(dmState(), ogreId).get("activeConditions")) {
            if (condition.get("name").asString().equals("phantasm")) {
                assertThat(condition.get("repeatSave").get("check").asBoolean()).isTrue();
                assertThat(condition.get("repeatSave").get("skill").asString()).isEqualTo("Investigation");
                assertThat(condition.get("repeatSave").get("ability").asString()).isEqualTo("INT");
            }
        }
        nextTurn();

        // 18 - 2 = 16 beats DC 14
        dice.script(18);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/action-save",
                Map.of("participantId", ogreId, "conditionId", phantasm), dm.accessToken()));
        assertThat(conditionNames(ogreId)).doesNotContain("phantasm");
        assertThat(participant(dmState(), ariaId).get("concentrationSpell").isNull()).isTrue();
        assertThat(log()).anyMatch(line -> line.contains("INT (Investigation) check against phantasm: d20(18) - 2 = 16"));
    }

    @Test
    @DisplayName("Spirit Guardians needs its radiant or necrotic option; the necrotic cast halves the ogre's speed as "
            + "well as damaging it (PHB p.278)")
    void spiritGuardians() {
        expect(HttpStatus.BAD_REQUEST, castRequest("Spirit Guardians", 3, List.of(ogreId), null));
        // 3d8 rolls 9; the ogre's WIS save of 3 - 2 fails
        dice.script(3, 3, 3, 3);
        expect(HttpStatus.OK, castRequest("Spirit Guardians", 3, List.of(ogreId), "necrotic"));
        assertThat(hp(ogreId)).isEqualTo(51);
        JsonNode guardians = effect(participant(dmState(), ariaId), "Spirit Guardians");
        assertThat(guardians.get("option").asString()).isEqualTo("necrotic");
        assertThat(log()).anyMatch(line -> line.contains("9 necrotic damage"));
        assertThat(log()).anyMatch(line -> line.contains("speed halved"));
    }

    @Test
    @DisplayName("Nathair's Mischief rolls one d4 for the cube: the apple pie charms the ogre on a failed WIS save "
            + "(FTD p.20); Wind Wall's STR save against 3d8 bludgeoning happens as it appears (PHB p.288)")
    void mischiefAndWall() {
        dice.script(1, 2);
        expect(HttpStatus.OK, castRequest("Nathair's Mischief", 2, List.of(ogreId), null));
        assertThat(conditionNames(ogreId)).contains("charmed");
        nextTurn();
        nextTurn();

        // 3d8 rolls 12; the ogre's STR save of 20 + 4 halves it
        dice.script(4, 4, 4, 20);
        expect(HttpStatus.OK, castRequest("Wind Wall", 3, List.of(ogreId), null));
        assertThat(hp(ogreId)).isEqualTo(54);
    }

    // ── Helpers ──────────────────────────────────────────────────

    // A spell with its template as the game data ships it
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    String castingTime = "BONUS_ACTION".equals(definition.path("castingTime").asString(null))
                            ? "1 bonus action" : "1 action";
                    spellRepository.save(Spell.builder().name(name).level(definition.path("spellLevel").asInt())
                            .source("PHB").castingTime(castingTime)
                            .concentration(definition.path("concentration").asBoolean(false))
                            .effectTemplate(SpellTemplate.of(definition)).build());
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private void aria(java.util.function.Consumer<EncounterParticipant> change) {
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        change.accept(aria);
        participantRepository.save(aria);
    }

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds, String option) {
        Map<String, Object> body = new HashMap<>(Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds));
        if (option != null) {
            body.put("option", option);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                body, dm.accessToken());
    }

    private ResponseEntity<String> trigger(Map<String, Object> body) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/repeat-spell-effect?actorId="
                + ariaId, body, dm.accessToken());
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
    }

    private List<String> conditionNames(String participantId) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant(dmState(), participantId).get("activeConditions");
        if (conditions != null && !conditions.isNull()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
    }

    private List<String> log() {
        JsonNode entries = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get("description").asString()));
        return lines;
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private static JsonNode effect(JsonNode participant, String spell) {
        for (JsonNode effect : participant.get("activeEffects")) {
            if (effect.get("spell").asString().equals(spell)) {
                return effect;
            }
        }
        throw new AssertionError(spell + " has no Spell Effect");
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
