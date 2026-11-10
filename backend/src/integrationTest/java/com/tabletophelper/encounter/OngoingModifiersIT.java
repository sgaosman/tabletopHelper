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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ongoing Modifiers end to end (D184), with the spell templates as the game data ships them. A table of a DM and one
 * player, whose cleric Aria (DEX 14, CON 12, AC 12, speed 30, spell attack +6, save DC 14) acts first on 20 against a
 * 40 HP ogre (AC 11, DEX 8) on 10.
 */
class OngoingModifiersIT extends IntegrationTest {

    private static final String FIRE_BOLT = """
            {"spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED", "concentration": false,
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}]}""";

    private static final List<String> SHIPPED = List.of("Bless", "Shield of Faith", "Faerie Fire", "Hunter's Mark",
            "Hex", "Haste", "Mage Armor", "Shield", "Resistance");

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
        spellRepository.save(Spell.builder().name("Fire Bolt").level(0).source("PHB").castingTime("1 action")
                .effectTemplate(SpellTemplate.parse(FIRE_BOLT)).build());
        // A concentration spell the templates don't cover (PHB p.251)
        spellRepository.save(Spell.builder().name("Hold Monster").level(5).source("PHB").castingTime("1 action")
                .concentration(true).duration("Concentration, up to 1 minute").build());

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        Monster ogre = monsterRepository.save(Monster.builder()
                .name("Ogre").type("Giant").challengeRating("2").source("MM")
                .hitPoints(40).armourClass(11).dexterity(8).constitution(16).wisdom(7)
                .build());
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
    }

    // Starts the fight, then gives Aria her spells, which the start would otherwise copy from her sheet again (D166)
    private void start() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(30);
        aria.setHpCurrent(30);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0), "5", new SpellSlot(1, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellcastingAbility("WIS");
        List<KnownSpell> known = new ArrayList<>();
        for (String spell : List.of("Bless", "Shield of Faith", "Faerie Fire", "Hunter's Mark", "Hex", "Haste",
                "Mage Armor", "Fire Bolt", "Hold Monster", "Shield", "Resistance")) {
            int level = spellRepository.findByNameIgnoreCase(spell).orElseThrow().getLevel();
            known.add(new KnownSpell(null, spell, level, "class:Cleric", true, null, null, null, null));
        }
        aria.setSpellsKnown(known);
        participantRepository.save(aria);
    }

    @Test
    @DisplayName("Bless adds its d4 to Aria's attack roll, turning a miss into a hit, and to her concentration check")
    void blessAttackAndSave() {
        start();
        cast("Bless", 1, List.of(ariaId), null);

        // d20 2 + 6 = 8 misses the ogre's AC 11 without Bless; its d4 of 3 makes 11, a hit for d10 4
        dice.script(2, 3, 4);
        cast("Fire Bolt", 0, List.of(ogreId), null);
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(36);
        assertThat(log()).anyMatch(line -> line.contains("d20(2) + 6 +1d4 (3) Bless = 11 vs AC 11"));

        // 10 damage: a DC 10 Constitution save, d20 6 + 1 + Bless 4 = 11
        dice.script(6, 4);
        damageAria(10);
        assertThat(log()).anyMatch(line -> line.equals(
                "Aria makes a concentration check: d20(6) + 1 +1d4 (4) Bless = 11 vs DC 10, and maintains Bless"));
        JsonNode chip = participant(dmState(), ariaId).get("spellModifiers").get(0);
        assertThat(chip.get("label").asString()).isEqualTo("Bless");
        assertThat(chip.get("hinders").asBoolean()).isFalse();
        assertThat(chip.get("modifiers").toString()).contains("+1d4 to attack rolls", "+1d4 to saving throws");
    }

    @Test
    @DisplayName("Shield of Faith raises Aria's AC by 2 on her row, and the players see its chip")
    void shieldOfFaith() {
        start();
        int before = participant(dmState(), ariaId).get("armourClass").asInt();
        cast("Shield of Faith", 1, List.of(ariaId), null);

        assertThat(participant(dmState(), ariaId).get("armourClass").asInt()).isEqualTo(before + 2);
        JsonNode playerView = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken()));
        assertThat(participant(playerView, ariaId).get("armourClass").asInt()).isEqualTo(before + 2);
        assertThat(participant(playerView, ariaId).get("spellModifiers").get(0).get("label").asString())
                .isEqualTo("Shield of Faith");
    }

    @Test
    @DisplayName("Faerie Fire on the ogre that fails its DEX save gives Aria's attack advantage, named in the log and the preview")
    void faerieFire() {
        start();
        // The ogre's DEX save of 2 - 1 fails against DC 14
        dice.script(2);
        cast("Faerie Fire", 1, List.of(ogreId), null);

        JsonNode preview = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-roll-preview?attackerId=" + ariaId + "&targetId=" + ogreId + "&withinFiveFeet=false",
                null, dm.accessToken()));
        assertThat(preview.get("rollMode").asString()).isEqualTo("ADVANTAGE");
        assertThat(preview.toString()).contains("Faerie Fire");

        dice.script(3, 15, 5);
        cast("Fire Bolt", 0, List.of(ogreId), null);
        assertThat(log()).anyMatch(line -> line.contains("(advantage: Faerie Fire): 2d20(3, 15) with advantage → 15"));
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(35);
        JsonNode chip = participant(dmState(), ogreId).get("spellModifiers").get(0);
        assertThat(chip.get("label").asString()).isEqualTo("Faerie Fire");
        assertThat(chip.get("hinders").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Hunter's Mark adds its d6 to Aria's weapon hits on the ogre only, not to her spell attack")
    void huntersMark() {
        start();
        cast("Hunter's Mark", 1, List.of(ogreId), null);
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(40);

        // An attack entered by hand counts as a weapon attack: d20 15 hits for 1d8 4 + Hunter's Mark 1d6 3
        dice.script(15, 4, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", ogreId, "attackBonus", 5, "damageDice", "1d8", "damageType", "slashing"),
                dm.accessToken()));
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(33);
        assertThat(log()).anyMatch(line -> line.contains("7 slashing damage (with Hunter's Mark 1d6 (3))"));

        dice.script(15, 5);
        cast("Fire Bolt", 0, List.of(ogreId), null);
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(28);
    }

    @Test
    @DisplayName("Hex needs its ability, and adds 1d6 necrotic to any of Aria's hits on the ogre, a spell attack too")
    void hex() {
        start();
        expect(HttpStatus.BAD_REQUEST, castRequest("Hex", 1, List.of(ogreId), null));
        cast("Hex", 1, List.of(ogreId), "STR");

        dice.script(15, 5, 2);
        cast("Fire Bolt", 0, List.of(ogreId), null);
        assertThat(participant(dmState(), ogreId).get("hpCurrent").asInt()).isEqualTo(33);
        assertThat(log()).anyMatch(line -> line.contains("7 damage (5 fire, 2 necrotic) (with Hex 1d6 (2))"));
    }

    @Test
    @DisplayName("Haste doubles Aria's speed and adds 2 AC; losing concentration ends both and logs the lethargy")
    void hasteThenConcentrationLost() {
        start();
        JsonNode before = participant(dmState(), ariaId);
        int armourClass = before.get("armourClass").asInt();
        cast("Haste", 3, List.of(ariaId), null);

        JsonNode hasted = participant(dmState(), ariaId);
        assertThat(hasted.get("speed").get("walk").asInt()).isEqualTo(60);
        assertThat(hasted.get("armourClass").asInt()).isEqualTo(armourClass + 2);

        // 10 damage and a concentration check of 1 + 1 against DC 10
        dice.script(1);
        damageAria(10);
        JsonNode after = participant(dmState(), ariaId);
        assertThat(after.get("speed").get("walk").asInt()).isEqualTo(30);
        assertThat(after.get("armourClass").asInt()).isEqualTo(armourClass);
        assertThat(after.get("spellModifiers")).isEmpty();
        assertThat(after.get("activeEffects")).isEmpty();
        assertThat(log()).contains("Aria can't move or take actions until after its next turn, as Haste ends.");
    }

    @Test
    @DisplayName("Undoing Bless takes away its concentration, its Spell Effect and its chip")
    void undoBless() {
        start();
        cast("Bless", 1, List.of(ariaId), null);
        assertThat(participant(dmState(), ariaId).get("spellModifiers")).hasSize(1);

        String newest = dmState().get("undo").get("commandId").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));

        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("concentrationSpell").isNull()).isTrue();
        assertThat(aria.get("activeEffects")).isEmpty();
        assertThat(aria.get("spellModifiers")).isEmpty();
    }

    @Test
    @DisplayName("Hold Monster, which has no template, starts concentration for its minute and ends Bless's")
    void untemplatedConcentration() {
        start();
        cast("Bless", 1, List.of(ariaId), null);
        cast("Hold Monster", 5, List.of(ogreId), null);

        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("concentrationSpell").asString()).isEqualTo("Hold Monster");
        assertThat(aria.get("concentrationSlotLevel").asInt()).isEqualTo(5);
        assertThat(aria.get("activeEffects")).isEmpty();
        assertThat(aria.get("spellModifiers")).isEmpty();
        assertThat(log()).anyMatch(line -> line.startsWith("Aria loses concentration on Bless"));
    }

    @Test
    @DisplayName("The DM records Mage Armor and Bless as already active while preparing: no slot spent, AC 15, concentration, and only the DM may")
    void alreadyActive() {
        expect(HttpStatus.FORBIDDEN, active(Map.of("casterId", ariaId, "spellName", "Bless",
                "targetIds", List.of(ariaId)), player.accessToken()));
        expect(HttpStatus.BAD_REQUEST, active(Map.of("casterId", ariaId, "spellName", "Bless"), dm.accessToken()));
        expect(HttpStatus.OK, active(Map.of("casterId", ariaId, "spellName", "Mage Armor",
                "targetIds", List.of(ariaId)), dm.accessToken()));
        expect(HttpStatus.OK, active(Map.of("casterId", ariaId, "spellName", "bless",
                "targetIds", List.of(ariaId, ogreId)), dm.accessToken()));
        start();

        JsonNode aria = participant(dmState(), ariaId);
        // Mage Armor's 13 + DEX 2 (PHB p.256)
        assertThat(aria.get("armourClass").asInt()).isEqualTo(15);
        assertThat(aria.get("concentrationSpell").asString()).isEqualTo("Bless");
        assertThat(aria.get("spellModifiers").toString()).contains("Mage Armor", "Bless");
        assertThat(participant(dmState(), ogreId).get("spellModifiers").get(0).get("label").asString())
                .isEqualTo("Bless");
        assertThat(aria.get("spellSlotsCurrent").get("1").get("used").asInt()).isZero();
        assertThat(log()).contains("DM: Aria's Bless is already active at level 1, and Aria is concentrating on it.");

        // Under way too, and undoable
        expect(HttpStatus.OK, active(Map.of("casterId", ariaId, "spellName", "Shield of Faith",
                "targetIds", List.of(ariaId)), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("armourClass").asInt()).isEqualTo(17);
        String newest = dmState().get("undo").get("commandId").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("armourClass").asInt()).isEqualTo(15);
        assertThat(participant(dmState(), ariaId).get("concentrationSpell").asString()).isEqualTo("Bless");
    }

    @Test
    @DisplayName("Shield's +5 lasts through the ogre's turn and ends at the start of Aria's next (PHB p.275)")
    void shieldUntilNextTurn() {
        start();
        int armourClass = participant(dmState(), ariaId).get("armourClass").asInt();
        cast("Shield", 1, List.of(), null);
        assertThat(participant(dmState(), ariaId).get("armourClass").asInt()).isEqualTo(armourClass + 5);
        nextTurn();
        assertThat(participant(dmState(), ariaId).get("armourClass").asInt()).isEqualTo(armourClass + 5);
        nextTurn();
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("armourClass").asInt()).isEqualTo(armourClass);
        assertThat(aria.get("activeEffects")).isEmpty();
    }

    @Test
    @DisplayName("Resistance's d4 goes on Aria's next save, her concentration check, and the spell then ends (PHB p.272)")
    void resistanceSpentOnce() {
        start();
        cast("Resistance", 0, List.of(ariaId), null);
        dice.script(9, 2);
        damageAria(4);

        assertThat(log()).anyMatch(line -> line.equals(
                "Aria makes a concentration check: d20(9) + 1 +1d4 (2) Resistance = 12 vs DC 10, and maintains Resistance"));
        assertThat(log()).anyMatch(line -> line.startsWith("Aria's Resistance is used: +1d4 to its next saving throw"));
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("concentrationSpell").isNull()).isTrue();
        assertThat(aria.get("spellModifiers")).isEmpty();
    }

    @Test
    @DisplayName("Hunter's Mark moves to a new creature with a bonus action only once its creature has dropped to 0 HP")
    void huntersMarkMoves() {
        Monster wolf = monsterRepository.save(Monster.builder().name("Wolf").type("Beast").challengeRating("1/4")
                .source("MM").hitPoints(11).armourClass(13).dexterity(15).build());
        String wolfId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", wolf.getId(), "displayName", "Wolf")), "Wolf");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(Map.of("participantId", wolfId, "initiative", 5))), dm.accessToken()));
        start();
        cast("Hunter's Mark", 1, List.of(ogreId), null);
        String effectId = participant(dmState(), ariaId).get("activeEffects").get(0).get("id").asString();
        Map<String, Object> move = Map.of("participantId", ariaId, "effectId", effectId, "targetId", wolfId);

        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/spell-effect/move", move, player.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ogreId, "amount", 40), dm.accessToken()));
        JsonNode moved = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/spell-effect/move", move, player.accessToken()));

        assertThat(participant(moved, wolfId).get("spellModifiers").get(0).get("label").asString())
                .isEqualTo("Hunter's Mark");
        assertThat(participant(moved, ariaId).get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    // A spell with its template as the game data ships it
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    spellRepository.save(Spell.builder().name(name).level(definition.path("spellLevel").asInt())
                            .source("PHB").castingTime("1 action")
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

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds, String option) {
        Map<String, Object> body = new HashMap<>(Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds));
        if (option != null) {
            body.put("option", option);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                body, dm.accessToken());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds, String option) {
        expect(HttpStatus.OK, castRequest(spell, slotLevel, targetIds, option));
    }

    private ResponseEntity<String> active(Map<String, Object> body, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/active-spell", body, token);
    }

    private void damageAria(int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ariaId, "amount", amount), dm.accessToken()));
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
