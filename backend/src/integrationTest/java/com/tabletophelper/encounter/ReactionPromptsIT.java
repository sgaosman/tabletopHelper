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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaction prompts end to end (D216). A goblin acts first, then the player's wizard Aria (20 of 30 HP, AC 12, Shield,
 * Fire Bolt and two level 1 slots), then an archmage who knows Counterspell. The spells use the templates the game
 * data ships.
 */
class ReactionPromptsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Shield", "Fire Bolt", "Counterspell");

    // A Multiattack whose claw +5 AC would stop and whose bite it wouldn't
    private static final String GOBLIN = """
            {"actions": [
               {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                "effects": [{"effectType": "DAMAGE", "damageDice": "3", "damageType": "slashing"}]},
               {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 10,
                "effects": [{"effectType": "DAMAGE", "damageDice": "30", "damageType": "piercing"}]},
               {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": ["Claw", "Bite"]}]}""";

    private static final String ARCHMAGE = """
            {"spellcasting": {"ability": "INT", "attackBonus": 9, "saveDC": 17, "slots": {"3": 3},
             "spellsByLevel": {"3": ["counterspell"]}}}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String goblinId;
    private String coraId;
    private String archmageId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID cora = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Wizard", 6));
        SHIPPED.forEach(this::shipped);

        encounterId = createEncounter(dm.accessToken(), campaignId);
        goblinId = monster("Goblin", MonsterTemplates.parse(GOBLIN));
        coraId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", cora, "displayName", "Aria")), "Aria");
        archmageId = monster("Archmage", MonsterTemplates.parse(ARCHMAGE));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", goblinId, "initiative", 20),
                        Map.of("participantId", coraId, "initiative", 15),
                        Map.of("participantId", archmageId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies the sheet into the participant again (D166)
        EncounterParticipant wizard = participantRepository.findById(UUID.fromString(coraId)).orElseThrow();
        wizard.setHpMax(30);
        wizard.setHpCurrent(20);
        wizard.setArmourClass(12);
        wizard.setSpellSaveDc(14);
        wizard.setSpellAttackBonus(6);
        wizard.setSpellcastingAbility("INT");
        wizard.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0))));
        wizard.setSpellsKnown(List.of(known("Shield", 1), known("Fire Bolt", 0)));
        participantRepository.save(wizard);
    }

    @Test
    @DisplayName("A goblin's hit on Aria offers her Shield in the player's view; casting it from the offer takes the "
            + "hit back, and undoing the Shield brings the hit back")
    void shieldTakesTheHitBack() {
        // d20 11 + 4 = 15 against AC 12, then 3 slashing
        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        assertThat(hp(coraId)).isEqualTo(17);

        JsonNode offer = onlyOffer(playerView());
        assertThat(offer.get("reactorId").asString()).isEqualTo(coraId);
        assertThat(offer.get("trigger").asString()).isEqualTo("ATTACK_HIT");
        assertThat(offer.get("triggerActorName").asString()).isEqualTo("Goblin");
        assertThat(offer.get("attackTotal").asInt()).isEqualTo(15);
        JsonNode shield = offer.get("options").get(0);
        assertThat(shield.get("name").asString()).isEqualTo("Shield");
        assertThat(shield.get("kind").asString()).isEqualTo("SPELL");
        assertThat(shield.get("resolves").asBoolean()).isTrue();
        assertThat(shield.get("slotLevels").get(0).asInt()).isEqualTo(1);

        // The player answers off Aria's turn, as a reaction may be
        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Shield", "slotLevel", 1,
                "targetIds", List.of(coraId), "reactionOfferId", offer.get("id").asString()), player.accessToken()));

        assertThat(hp(coraId)).isEqualTo(20);
        assertThat(playerView().get("reactionOffers")).isEmpty();
        assertThat(participant(dmView(), coraId).get("spellSlotsCurrent").get("1").get("used").asInt()).isEqualTo(1);
        assertThat(participant(dmView(), coraId).get("turnEconomy").get("reactionUsed").asBoolean()).isTrue();
        assertThat(log()).anyMatch(line -> line.equals("Shield raises Aria's AC to 17 against Goblin's attack roll "
                + "of 15, which misses: the hit is taken back."));
        // The goblin still made its attack
        assertThat(participant(dmView(), goblinId).get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);

        undoNewest();
        assertThat(hp(coraId)).isEqualTo(17);
    }

    @Test
    @DisplayName("A Shield cast from an offer the roll still beats leaves the hit, and the log says the DM adjusts "
            + "nothing")
    void shieldStillHit() {
        // d20 15 + 4 = 19 against AC 12 + 5
        dice.script(15, 3);
        expect(HttpStatus.OK, goblinAttack());
        JsonNode offer = onlyOffer(playerView());
        assertThat(offer.get("options").get(0).get("resolves").asBoolean()).isFalse();

        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Shield", "slotLevel", 1,
                "targetIds", List.of(coraId), "reactionOfferId", offer.get("id").asString()), player.accessToken()));
        assertThat(hp(coraId)).isEqualTo(17);
        assertThat(log()).anyMatch(line -> line.equals("Shield raises Aria's AC to 17 against Goblin's attack roll "
                + "of 19, which still hits."));
    }

    @Test
    @DisplayName("Aria's Fire Bolt offers the archmage Counterspell in the DM's view only; the DM's Counterspell "
            + "from the offer counters it")
    void counterspellOffered() {
        nextTurn();
        // d20 15 + 6 hits the archmage's AC 15, then 1d10 of 5
        dice.script(15, 5);
        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Fire Bolt", "slotLevel", 0,
                "targetIds", List.of(archmageId)), player.accessToken()));

        JsonNode offer = onlyOffer(dmView());
        assertThat(offer.get("reactorId").asString()).isEqualTo(archmageId);
        assertThat(offer.get("trigger").asString()).isEqualTo("SPELL_CAST");
        assertThat(offer.get("spellName").asString()).isEqualTo("Fire Bolt");
        JsonNode counter = offer.get("options").get(0);
        assertThat(counter.get("spellName").asString()).isEqualTo("Counterspell");
        assertThat(counter.get("targetIds").get(0).asString()).isEqualTo(coraId);
        // A monster's reactions are the DM's
        assertThat(playerView().get("reactionOffers")).isEmpty();

        expect(HttpStatus.OK, castSpell(archmageId, Map.of("spellName", "Counterspell",
                "slotLevel", counter.get("slotLevels").get(0).asInt(), "targetIds", List.of(coraId),
                "reactionOfferId", offer.get("id").asString()), dm.accessToken()));
        assertThat(log()).anyMatch(line -> line.startsWith("Archmage counters Aria's Fire Bolt"));
        assertThat(dmView().get("reactionOffers")).isEmpty();
    }

    @Test
    @DisplayName("A command that changes nothing keeps the Counterspell offer, and its own response shows the offer as "
            + "stored (D228)")
    void unchangedCommandResponseKeepsCounterspellOffer() {
        nextTurn();
        dice.script(15, 5);
        JsonNode cast = expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Fire Bolt", "slotLevel", 0,
                "targetIds", List.of(archmageId)), dm.accessToken()));
        JsonNode offer = onlyOffer(dmView());
        assertThat(cast.get("encounterState").get("reactionOffers")).isEqualTo(dmView().get("reactionOffers"));

        // No monster was in its lair, and none is now: the command changes nothing
        JsonNode unchanged = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/lair-status", Map.of("monsterIds", List.of()), dm.accessToken()));

        JsonNode stored = dmView().get("reactionOffers");
        assertThat(onlyOffer(dmView()).get("id")).isEqualTo(offer.get("id"));
        assertThat(unchanged.get("reactionOffers")).isEqualTo(stored);
        assertThat(unchanged.get("undo")).isEqualTo(dmView().get("undo"));
    }

    @Test
    @DisplayName("A Multiattack's response shows its Shield offer as stored, settled once the bite has done more to "
            + "Aria (D228)")
    void multiattackResponseShowsSettledOffer() {
        dice.script(10, 10);
        JsonNode response = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/monster-action", Map.of("monsterParticipantId", goblinId, "actionName", "Multiattack",
                "actionSource", "ACTION", "targetParticipantIds", List.of(coraId)), dm.accessToken()));

        JsonNode stored = dmView().get("reactionOffers");
        assertThat(stored).hasSize(1);
        assertThat(response.get("encounterState").get("reactionOffers")).isEqualTo(stored);
        assertThat(response.get("encounterState").get("undo")).isEqualTo(dmView().get("undo"));
    }

    @Test
    @DisplayName("An offer lasts past other commands until the turn changes")
    void lastsUntilTheTurnChanges() {
        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        assertThat(dmView().get("reactionOffers")).hasSize(1);

        // The goblin's next attack misses (a natural 1): the first offer stands, and the miss makes none
        dice.script(1);
        expect(HttpStatus.OK, goblinAttack());
        assertThat(dmView().get("reactionOffers")).hasSize(1);

        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        assertThat(dmView().get("reactionOffers")).hasSize(2);
        nextTurn();
        assertThat(dmView().get("reactionOffers")).isEmpty();

        // Undoing the turn change doesn't bring the offer back
        undoNewest();
        assertThat(dmView().get("reactionOffers")).isEmpty();
    }

    @Test
    @DisplayName("Undoing the hit clears its offer, and the player may let an offer pass")
    void undoAndDismiss() {
        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        assertThat(dmView().get("reactionOffers")).hasSize(1);
        undoNewest();
        assertThat(dmView().get("reactionOffers")).isEmpty();
        assertThat(hp(coraId)).isEqualTo(20);

        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        String offerId = onlyOffer(playerView()).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/dismiss-reaction", Map.of("offerId", offerId), player.accessToken()));
        assertThat(dmView().get("reactionOffers")).isEmpty();
        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/dismiss-reaction", Map.of("offerId", offerId), player.accessToken()));
    }

    @Test
    @DisplayName("An offer stands through the goblin's next attack at someone else, as an Extra Attack, and Shield "
            + "then still takes the first hit back")
    void standsThroughAnotherAttack() {
        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());
        String offerId = onlyOffer(playerView()).get("id").asString();

        // 14 + 4 hits the archmage's AC 15 for 5
        dice.script(14, 5);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + goblinId, new HashMap<>(Map.of("targetId", archmageId, "attackBonus", 4, "damageDice", "1d6")),
                dm.accessToken()));
        assertThat(hp(archmageId)).isEqualTo(94);
        assertThat(onlyOffer(playerView()).get("id").asString()).isEqualTo(offerId);

        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Shield", "slotLevel", 1,
                "targetIds", List.of(coraId), "reactionOfferId", offerId), player.accessToken()));
        assertThat(hp(coraId)).isEqualTo(20);
        assertThat(hp(archmageId)).isEqualTo(94);
    }

    @Test
    @DisplayName("Shield from the claw of a Multiattack whose bite still hit and dropped Aria takes nothing back")
    void multiattackNotTakenBack() {
        // Claw 10 + 4 = 14, a miss against 17; Bite 10 + 10 = 20 still hits, for 30
        dice.script(10, 10);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", goblinId, "actionName", "Multiattack", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(coraId)), dm.accessToken()));
        assertThat(hp(coraId)).isZero();
        JsonNode offer = onlyOffer(dmView());
        assertThat(offer.get("attackTotal").asInt()).isEqualTo(14);
        assertThat(offer.get("options").get(0).get("resolves").asBoolean()).isFalse();

        expect(HttpStatus.OK, castSpell(coraId, Map.of("spellName", "Shield", "slotLevel", 1,
                "targetIds", List.of(coraId), "reactionOfferId", offer.get("id").asString()), dm.accessToken()));
        assertThat(hp(coraId)).isZero();
        assertThat(log()).anyMatch(line -> line.contains("which misses, but the hit can't be taken back here"));
    }

    @Test
    @DisplayName("A cast naming an offer that has gone or never was is a 409 that spends nothing")
    void goneOffer() {
        dice.script(11, 3);
        expect(HttpStatus.OK, goblinAttack());

        expect(HttpStatus.CONFLICT, castSpell(coraId, Map.of("spellName", "Shield", "slotLevel", 1,
                "targetIds", List.of(coraId), "reactionOfferId", UUID.randomUUID().toString()),
                player.accessToken()));

        assertThat(hp(coraId)).isEqualTo(17);
        JsonNode aria = participant(dmView(), coraId);
        assertThat(aria.get("spellSlotsCurrent").get("1").get("used").asInt()).isZero();
        assertThat(aria.get("turnEconomy").get("reactionUsed").asBoolean()).isFalse();
        assertThat(dmView().get("reactionOffers")).hasSize(1);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private ResponseEntity<String> goblinAttack() {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId=" + goblinId,
                new HashMap<>(Map.of("targetId", coraId, "attackBonus", 4, "damageDice", "1d6")), dm.accessToken());
    }

    private ResponseEntity<String> castSpell(String casterId, Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + casterId,
                new HashMap<>(request), token);
    }

    private String monster(String name, MonsterTemplates templates) {
        Monster.MonsterBuilder builder = Monster.builder().name(name).type("humanoid").challengeRating("1")
                .source("MM").hitPoints(99).armourClass(15).dexterity(10);
        if (templates != null) {
            builder.actionTemplates(templates);
        }
        Monster saved = monsterRepository.save(builder.build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
    }

    // A spell with its template as the game data ships it
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    String castingTime = "REACTION".equals(definition.path("castingTime").asString(null))
                            ? "1 reaction, which you take when you are hit by an attack" : "1 action";
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

    private static KnownSpell known(String name, int level) {
        return new KnownSpell(null, name, level, "class:Wizard", true, null, null, null, null);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private void undoNewest() {
        String newest = dmView().get("undo").get("commandId").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmView(), participantId).get("hpCurrent").asInt();
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode playerView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken()));
    }

    private static JsonNode onlyOffer(JsonNode view) {
        JsonNode offers = view.get("reactionOffers");
        assertThat(offers).as("reaction offers").hasSize(1);
        return offers.get(0);
    }

    private List<String> log() {
        JsonNode entries = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get("description").asString()));
        return lines;
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
