package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.ConcentrationInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The high-level spell gaps closed in D195 end to end, with their templates as the game data ships them. A table of a DM
 * and one player, whose cleric Aria (spell attack +6, save DC 14, 30 HP, slots up to 9th level) acts first on 20; each
 * test adds the creatures it needs, acting after her.
 */
class HighLevelGapsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Time Stop", "Feeblemind", "Greater Restoration", "Earthquake",
            "Prismatic Wall", "Holy Aura");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

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
        initiatives.clear();
        initiatives.put(ariaId, 20);
    }

    @Test
    @DisplayName("Time Stop: Aria takes 1d4 + 1 turns in a row in the same round, then the turn passes on (PHB p.283)")
    void timeStop() {
        monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // 1d4 (2) + 1 = 3 turns after this one
        dice.script(2);
        cast("Time Stop", 9, List.of(), null);
        for (int i = 0; i < 3; i++) {
            nextTurn();
        }
        List<String> lines = log();
        assertThat(lines).anyMatch(line -> line.contains("Aria takes another turn (Time Stop, 0 more after this; Round 1)"));
        assertThat(lines).noneMatch(line -> line.contains("Turn passes to Ogre"));
        assertThat(dmState().get("roundNumber").asInt()).isEqualTo(1);

        nextTurn();
        assertThat(log()).anyMatch(line -> line.contains("Turn passes to Ogre (Round 1)"));
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
    }

    @Test
    @DisplayName("Feeblemind makes INT 1, so the next INT save fails where it would have passed; Greater Restoration's "
            + "feebleminded option ends it (PHB p.239, p.246)")
    void feeblemindAndGreaterRestoration() {
        String mage = monster("Mage", "humanoid", 100, builder -> builder.intelligence(18));
        start();
        // 4d6 of 1s = 4; the INT save of 2 + 4 fails
        dice.script(1, 1, 1, 1, 2);
        cast("Feeblemind", 8, List.of(mage), null);
        assertThat(condition(mage, "feebleminded")).isNotNull();

        cast("Greater Restoration", 5, List.of(mage), "feebleminded");
        assertThat(condition(mage, "feebleminded")).isNull();
        // Its INT is 18 again: 15 + 4 = 19 succeeds against DC 14
        dice.script(1, 1, 1, 1, 15);
        cast("Feeblemind", 8, List.of(mage), null);
        assertThat(condition(mage, "feebleminded")).isNull();

        // Feebleminded again (2 + 4 fails); the same 15 is now 15 - 5 = 10
        dice.script(1, 1, 1, 1, 2);
        cast("Feeblemind", 8, List.of(mage), null);
        dice.script(1, 1, 1, 1, 15);
        JsonNode result = expect(HttpStatus.OK, castRequest("Feeblemind", 8, List.of(mage), null));
        assertThat(result.get("targets").get(0).get("saveRoll").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("Earthquake: a concentrating creature it reaches fails its CON save and loses its concentration (PHB "
            + "p.236)")
    void earthquake() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        EncounterParticipant concentrating = participantRepository.findById(UUID.fromString(ogre)).orElseThrow();
        concentrating.setConcentration(ConcentrationInstance.start("Hold Person", 2, 1, 10));
        participantRepository.save(concentrating);
        assertThat(participant(dmState(), ogre).get("concentrationSpell").asString()).isEqualTo("Hold Person");

        // The DEX save of 20 - 1 keeps it standing; the CON save of 2 + 3 fails against DC 14
        dice.script(20, 2);
        cast("Earthquake", 8, List.of(ogre), null);
        JsonNode spell = participant(dmState(), ogre).get("concentrationSpell");
        assertThat(spell == null || spell.isNull()).isTrue();
        assertThat(condition(ogre, "prone")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("Ogre makes a DC 14 CON save to keep concentrating on Hold "
                + "Person: 5, a failure"));
    }

    @Test
    @DisplayName("Prismatic Wall: a creature passing through saves against each layer in order, red to violet (PHB "
            + "p.267)")
    void prismaticWall() {
        String giant = monster("Hill Giant", "giant", 200, UnaryOperator.identity());
        start();
        cast("Prismatic Wall", 9, List.of(), null);
        // Five damage layers of 10d6 of 1s, each saved with 20 - 1 for half (5 each); indigo failed with 1; violet
        // failed with 1 and 1, the restrained giant rolling with disadvantage
        int[] rolls = new int[5 * 11 + 3];
        int at = 0;
        for (int layer = 0; layer < 5; layer++) {
            for (int die = 0; die < 10; die++) {
                rolls[at++] = 1;
            }
            rolls[at++] = 20;
        }
        rolls[at++] = 1;
        rolls[at++] = 1;
        rolls[at] = 1;
        dice.script(rolls);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(giant))));
        assertThat(hp(giant)).isEqualTo(200 - 25);
        assertThat(condition(giant, "restrained")).isNotNull();
        assertThat(condition(giant, "blinded")).isNotNull();
        assertThat(log()).anyMatch(line -> line.contains("Hill Giant passes through Prismatic Wall"));
    }

    @Test
    @DisplayName("Holy Aura: an undead that hits Aria in melee fails its CON save and is blinded until the spell ends; "
            + "an ogre isn't (PHB p.250)")
    void holyAura() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String wight = monster("Wight", "undead", 60, UnaryOperator.identity());
        start();
        cast("Holy Aura", 8, List.of(ariaId), null);

        // Attacks against Aria have disadvantage: 15 and 15 + 6 hit, 1d8 of 3, and her concentration save, with Holy
        // Aura's advantage, of 20 and 20 holds
        dice.script(15, 15, 3, 20, 20);
        meleeAttack(ogre, ariaId);
        assertThat(condition(ogre, "blinded")).isNull();

        // The wight hits the same way, Aria keeps concentrating, and its CON save of 2 + 3 fails against DC 14
        dice.script(15, 15, 3, 20, 20, 2);
        meleeAttack(wight, ariaId);
        assertThat(condition(wight, "blinded")).isNotNull();
        assertThat(log()).anyMatch(line -> line.contains("Aria's Holy Aura flares at Wight: CON save d20(2) + 3 = 5"));

        dropConcentration();
        assertThat(condition(wight, "blinded")).isNull();
    }

    private void meleeAttack(String attackerId, String targetId) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + attackerId, Map.of("targetId", targetId, "attackBonus", 6, "damageDice", "1d8", "damageType",
                "slashing", "within5Ft", true), dm.accessToken()));
    }

    // count rolls of the face, then the rest
    private static int[] ones(int count, int face, int[] rest) {
        int[] rolls = new int[count + rest.length];
        java.util.Arrays.fill(rolls, 0, count, face);
        System.arraycopy(rest, 0, rolls, count, rest.length);
        return rolls;
    }

    // the first rolls, then count rolls of the face
    private static int[] ones(int count, int face, int[] first, boolean firstFirst) {
        int[] rolls = new int[count + first.length];
        System.arraycopy(first, 0, rolls, 0, first.length);
        java.util.Arrays.fill(rolls, first.length, rolls.length, face);
        return rolls;
    }

    private void hurt(String participantId, int amount) {
        EncounterParticipant creature = participantRepository.findById(UUID.fromString(participantId)).orElseThrow();
        creature.setHpCurrent(creature.getHpCurrent() - amount);
        participantRepository.save(creature);
    }

    private void hurtAria(int amount) {
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpCurrent(aria.getHpCurrent() - amount);
        participantRepository.save(aria);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String monster(String name, String type, int hp, UnaryOperator<Monster.MonsterBuilder> more) {
        Monster saved = monsterRepository.save(more.apply(Monster.builder().name(name).type(type)
                .challengeRating("5").source("MM").hitPoints(hp).armourClass(11).dexterity(8).constitution(16)
                .wisdom(7).charisma(7)).build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
        initiatives.put(id, 19 - initiatives.size());
        return id;
    }

    // Starts the fight, then gives Aria her spells, which the start would otherwise copy from her sheet again (D166)
    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(30);
        aria.setHpCurrent(30);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("2", new SpellSlot(3, 0), "3", new SpellSlot(3, 0),
                "4", new SpellSlot(3, 0), "5", new SpellSlot(4, 0), "6", new SpellSlot(2, 0), "7", new SpellSlot(2, 0),
                "8", new SpellSlot(6, 0), "9", new SpellSlot(2, 0))));
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

    // A monster's innate spell, cast by the DM
    private void castBy(String monsterId, String spell, List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + monsterId, Map.of("spellName", spell, "slotLevel", 0, "targetIds", targetIds), dm.accessToken()));
    }

    private ResponseEntity<String> use(Map<String, Object> body) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/repeat-spell-effect?actorId="
                + ariaId, body, dm.accessToken());
    }

    private void attack(String attackerId, String targetId, String type) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + attackerId, Map.of("targetId", targetId, "attackBonus", 6, "damageDice", "1d8", "damageType", type),
                dm.accessToken()));
    }

    private void damage(String targetId, int amount, String type) {
        Map<String, Object> body = new HashMap<>(Map.of("targetId", targetId, "amount", amount));
        if (type != null) {
            body.put("damageType", type);
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage", body,
                dm.accessToken()));
    }

    private void endConcentration() {
        String effectId = participant(dmState(), ariaId).get("activeEffects").get(0).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/spell-effect/end",
                Map.of("participantId", ariaId, "effectId", effectId), dm.accessToken()));
    }

    // The DM drops Aria's concentration by hand
    private void dropConcentration() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/concentration",
                Map.of("participantId", ariaId), dm.accessToken()));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
    }

    // The participant's condition of the name, or null
    private JsonNode condition(String participantId, String name) {
        JsonNode conditions = participant(dmState(), participantId).get("activeConditions");
        if (conditions == null || conditions.isNull()) {
            return null;
        }
        for (JsonNode condition : conditions) {
            if (name.equals(condition.get("name").asString())) {
                return condition;
            }
        }
        return null;
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
