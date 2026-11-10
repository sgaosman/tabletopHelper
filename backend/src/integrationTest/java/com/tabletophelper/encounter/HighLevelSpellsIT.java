package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.ConditionEntry;
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
 * The 8th- and 9th-level spells end to end (D190), with their templates as the game data ships them. A table of a DM
 * and one player, whose cleric Aria (spell attack +6, save DC 14, 30 HP, slots up to 9th level) acts first on 20; each
 * test adds the creatures it needs, acting after her.
 */
class HighLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Meteor Swarm", "Sunburst", "Dominate Monster", "Feeblemind",
            "Maze", "Mind Blank", "Power Word Heal", "Wish", "Fireball", "Mass Heal", "Blade of Disaster",
            "Power Word Kill", "Power Word Stun");

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
    @DisplayName("Meteor Swarm rolls 20d6 fire and 20d6 bludgeoning together, and a fire-resistant creature halves "
            + "only the fire (PHB p.259)")
    void meteorSwarm() {
        String imp = monster("Fire Giant", "giant", 200, builder -> builder.damageResistances("[\"fire\"]"));
        String ogre = monster("Ogre", "giant", 200, UnaryOperator.identity());
        start();
        // 20d6 of 2s = 40 fire, 20d6 of 3s = 60 bludgeoning; the giant's DEX save of 2 - 1 fails, the ogre's 20 succeeds
        dice.script(ones(20, 2, ones(20, 3, new int[]{2, 20})));
        cast("Meteor Swarm", 9, List.of(imp, ogre), null);

        assertThat(hp(imp)).isEqualTo(200 - 20 - 60);
        assertThat(hp(ogre)).isEqualTo(200 - 20 - 30);
        assertThat(log()).anyMatch(line -> line.contains("20 fire, halved by resistance, 60 bludgeoning"));
    }

    @Test
    @DisplayName("Sunburst: 12d6 radiant and blinded on a failed CON save; an undead saves with disadvantage, and a "
            + "blinded creature's save at the end of its turn ends it (PHB p.279)")
    void sunburst() {
        String zombie = monster("Zombie", "undead", 100, UnaryOperator.identity());
        start();
        // 12d6 of 2s = 24; the zombie rolls 20 and 3 with disadvantage, keeping 3 + 3 = 6, which fails
        dice.script(ones(12, 2, new int[]{20, 3}));
        cast("Sunburst", 8, List.of(zombie), null);
        assertThat(hp(zombie)).isEqualTo(100 - 24);
        assertThat(condition(zombie, "blinded")).isNotNull();

        nextTurn();
        // At the end of the zombie's turn: 18 + 3 succeeds
        dice.script(18);
        nextTurn();
        assertThat(condition(zombie, "blinded")).isNull();
    }

    @Test
    @DisplayName("Dominate Monster charms any creature for 8 hours with a 9th-level slot, under Aria's player's "
            + "control, until damage lets it save (PHB p.235)")
    void dominateMonster() {
        String ogre = monster("Ogre", "giant", 60, builder -> builder.wisdom(10));
        start();
        dice.script(3);
        cast("Dominate Monster", 9, List.of(ogre), null);
        JsonNode charmed = condition(ogre, "charmed");
        assertThat(charmed).isNotNull();
        assertThat(charmed.get("duration").asInt()).isEqualTo(4800);
        assertThat(participant(dmState(), ogre).get("controlledByUserId").asString())
                .isEqualTo(player.id().toString());

        dice.script(19);
        damage(ogre, 3, null);
        assertThat(condition(ogre, "charmed")).isNull();
        JsonNode freed = participant(dmState(), ogre).get("controlledByUserId");
        assertThat(freed == null || freed.isNull()).isTrue();
    }

    @Test
    @DisplayName("Feeblemind deals 4d6 psychic even on a successful save; on a failure the creature is feebleminded "
            + "and can't cast spells (PHB p.239)")
    void feeblemind() {
        String mage = monster("Mage", "humanoid", 60, builder -> builder.intelligence(10)
                .actionTemplates(MonsterTemplates.parse("""
                        {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 14,
                         "atWillSpells": ["fireball"]}}""")));
        start();
        // 4d6 of 3s = 12, and the mage's INT save of 20 succeeds: it still takes the damage
        dice.script(3, 3, 3, 3, 20);
        cast("Feeblemind", 8, List.of(mage), null);
        assertThat(hp(mage)).isEqualTo(48);
        assertThat(condition(mage, "feebleminded")).isNull();

        dice.script(3, 3, 3, 3, 2);
        cast("Feeblemind", 8, List.of(mage), null);
        assertThat(hp(mage)).isEqualTo(36);
        assertThat(condition(mage, "feebleminded")).isNotNull();

        ResponseEntity<String> refused = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + mage, Map.of("spellName", "Fireball", "slotLevel", 0,
                "targetIds", List.of(ariaId)), dm.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("can't cast spells while feebleminded");
    }

    @Test
    @DisplayName("Maze banishes with no save; the creature's DC 20 INT check at the start of Aria's turn escapes "
            + "it and ends the spell (PHB p.258)")
    void maze() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        cast("Maze", 8, List.of(ogre), null);
        assertThat(condition(ogre, "banished")).isNotNull();
        ResponseEntity<String> unreachable = castRequest("Sunburst", 8, List.of(ogre), null);
        assertThat(unreachable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The ogre's turn is skipped; at the start of Aria's next turn it rolls 12 + 0, failing
        dice.script(12);
        nextTurn();
        assertThat(condition(ogre, "banished")).isNotNull();
        assertThat(log()).anyMatch(line -> line.contains("Ogre tries to escape Aria's Maze")
                && line.contains("and fails"));

        dice.script(20);
        nextTurn();
        assertThat(condition(ogre, "banished")).isNull();
        JsonNode concentration = participant(dmState(), ariaId).get("concentrationSpell");
        assertThat(concentration == null || concentration.isNull()).isTrue();
    }

    @Test
    @DisplayName("Mind Blank makes its creature immune to psychic damage and to being charmed for 24 hours (PHB "
            + "p.259)")
    void mindBlank() {
        String mage = monster("Mage", "humanoid", 60, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 17,
                 "atWillSpells": ["dominate monster"]}}""")));
        start();
        cast("Mind Blank", 8, List.of(ariaId), null);
        damage(ariaId, 10, "psychic");
        assertThat(hp(ariaId)).isEqualTo(30);
        damage(ariaId, 5, "fire");
        assertThat(hp(ariaId)).isEqualTo(25);

        // Aria's WIS save of 2 fails, but she can't be charmed
        dice.script(2);
        castBy(mage, "Dominate Monster", List.of(ariaId));
        assertThat(condition(ariaId, "charmed")).isNull();
    }

    @Test
    @DisplayName("Power Word Heal restores all hit points and ends stunned, leaving other conditions (PHB p.266)")
    void powerWordHeal() {
        start();
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpCurrent(4);
        aria.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("stunned", null, 1),
                ConditionEntry.manual("poisoned", null, 1))));
        participantRepository.save(aria);

        cast("Power Word Heal", 9, List.of(ariaId), null);
        assertThat(hp(ariaId)).isEqualTo(30);
        assertThat(condition(ariaId, "stunned")).isNull();
        assertThat(condition(ariaId, "poisoned")).isNotNull();
    }

    @Test
    @DisplayName("Wish duplicates Fireball at its own level, paid with a 9th-level slot; it can't duplicate a "
            + "9th-level spell (PHB p.288)")
    void wishDuplicates() {
        String ogre = monster("Ogre", "giant", 100, UnaryOperator.identity());
        start();
        ResponseEntity<String> tooHigh = castRequest("Wish", 9, List.of(ogre), "Meteor Swarm");
        assertThat(tooHigh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Fireball's 8d6 at 3rd level, all 2s = 16; the ogre's DEX save of 2 - 1 fails
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2);
        cast("Wish", 9, List.of(ogre), "fireball");
        assertThat(hp(ogre)).isEqualTo(100 - 16);
        assertThat(log()).anyMatch(line -> line.contains("Aria casts Fireball through Wish"));
        JsonNode slots = participant(dmState(), ariaId).get("spellSlotsCurrent");
        assertThat(slots.get("9").get("used").asInt()).isEqualTo(1);
        assertThat(slots.get("3").get("used").asInt()).isZero();
    }

    @Test
    @DisplayName("Mass Heal divides 700 hit points, ends blindness, and has no effect on an undead (PHB p.258)")
    void massHeal() {
        String ogre = monster("Ogre", "giant", 100, UnaryOperator.identity());
        String zombie = monster("Zombie", "undead", 50, UnaryOperator.identity());
        start();
        hurt(ogre, 90);
        hurt(zombie, 40);
        hurtAria(20);
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("blinded", null, 1))));
        participantRepository.save(aria);

        cast("Mass Heal", 9, List.of(ariaId, ogre, zombie), null);
        assertThat(hp(ariaId)).isEqualTo(30);
        assertThat(hp(ogre)).isEqualTo(100);
        assertThat(hp(zombie)).isEqualTo(10);
        assertThat(condition(ariaId, "blinded")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("heals Ogre for 90 HP (Mass Heal: 90 of 700)"));
    }

    @Test
    @DisplayName("Blade of Disaster scores a critical hit on 18 with 12d12 force, and a later bonus action attacks "
            + "twice (TCE p.106)")
    void bladeOfDisaster() {
        String golem = monster("Golem", "construct", 300, builder -> builder.armourClass(20));
        start();
        // A d20 of 18 is a critical hit: 12d12 of 1s = 12
        dice.script(ones(12, 1, new int[]{18}, true));
        cast("Blade of Disaster", 9, List.of(golem), null);
        assertThat(hp(golem)).isEqualTo(300 - 12);

        nextTurn();
        nextTurn();
        // Two attacks: 15 + 6 = 21 hits for 4d12 of 2s = 8; then a natural 1 misses
        dice.script(15, 2, 2, 2, 2, 1);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(golem), "uses", 2)));
        assertThat(hp(golem)).isEqualTo(300 - 12 - 8);
    }

    @Test
    @DisplayName("Power Word Kill kills a creature of 100 HP or fewer outright and spares one with more; Power Word "
            + "Stun stuns one of 150 or fewer, which repeats a CON save at the end of its turns (PHB p.266-267)")
    void powerWords() {
        String ogre = monster("Ogre", "giant", 100, UnaryOperator.identity());
        String giant = monster("Hill Giant", "giant", 101, UnaryOperator.identity());
        start();
        cast("Power Word Kill", 9, List.of(giant), null);
        assertThat(participant(dmState(), giant).get("lifeState").asString()).isEqualTo("ALIVE");
        cast("Power Word Kill", 9, List.of(ogre), null);
        assertThat(participant(dmState(), ogre).get("lifeState").asString()).isEqualTo("DEAD");

        cast("Power Word Stun", 8, List.of(giant), null);
        assertThat(condition(giant, "stunned")).isNotNull();
        // The dead ogre's turn is skipped; at the end of the giant's turn its CON save of 15 + 3 succeeds
        nextTurn();
        dice.script(15);
        nextTurn();
        assertThat(condition(giant, "stunned")).isNull();
    }

    @Test
    @DisplayName("A monster casts Meteor Swarm and Dominate Monster at Aria")
    void monsterCasts() {
        String lich = monster("Lich", "undead", 135, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 20,
                 "atWillSpells": ["meteor swarm", "dominate monster"]}}""")));
        start();
        // Aria's WIS save of 2 fails: she is charmed, and the DM controls her
        dice.script(2);
        castBy(lich, "Dominate Monster", List.of(ariaId));
        assertThat(condition(ariaId, "charmed")).isNotNull();
        JsonNode controller = participant(dmState(), ariaId).get("controlledByUserId");
        assertThat(controller == null || controller.isNull()).isTrue();

        // 20d6 fire and 20d6 bludgeoning of 1s = 40; Aria's DEX save of 20 halves each to 10
        dice.script(ones(40, 1, new int[]{20}));
        castBy(lich, "Meteor Swarm", List.of(ariaId));
        assertThat(hp(ariaId)).isEqualTo(30 - 20);
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
                "8", new SpellSlot(2, 0), "9", new SpellSlot(2, 0))));
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
