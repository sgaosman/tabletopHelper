package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 7th-level spells end to end (D189), with their templates as the game data ships them. A table of a DM and one
 * player, whose cleric Aria (spell attack +6, save DC 14, 30 HP) acts first on 20; each test adds the creatures it
 * needs, acting after her. Monsters have DEX 8, CON 16, WIS 7 and CHA 7 unless a test says otherwise.
 */
class SeventhLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Finger of Death", "Prismatic Spray", "Delayed Blast Fireball",
            "Divine Word", "Forcecage", "Plane Shift", "Regenerate", "Resurrection");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private com.tabletophelper.campaign.CampaignRepository campaignRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private UUID characterId;
    private UUID campaignId;
    private String ariaId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        characterId = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Cleric", 8));
        campaignId = UUID.fromString(campaign.get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);
    }

    @Test
    @DisplayName("Finger of Death: 7d8 + 30 necrotic, and the humanoid it kills rises as Aria's zombie at the start of "
            + "her next turn (PHB p.241)")
    void fingerOfDeathRaisesAZombie() {
        monsterRepository.save(Monster.builder().name("Zombie").type("undead").challengeRating("1/4").source("MM")
                .hitPoints(22).armourClass(8).dexterity(6).constitution(16).wisdom(6).charisma(5).build());
        String bandit = monster("Bandit", "humanoid", 30, UnaryOperator.identity());
        monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // 7d8 of 1s + 30 = 37; the bandit's CON save of 2 + 3 fails against DC 14
        dice.script(1, 1, 1, 1, 1, 1, 1, 2);
        cast("Finger of Death", 7, List.of(bandit), null);
        assertThat(participant(dmState(), bandit).get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(log()).anyMatch(line -> line.contains("Bandit will rise as a zombie under Aria's command"));
        assertThat(names()).doesNotContain("Zombie (Bandit)");

        nextTurn();
        nextTurn();
        JsonNode zombie = byName(dmState(), "Zombie (Bandit)");
        assertThat(zombie.get("hpCurrent").asInt()).isEqualTo(22);
        assertThat(zombie.get("controlledByUserId").asString()).isEqualTo(player.id().toString());
        participant(dmState(), ariaId).get("activeEffects")
                .forEach(effect -> assertThat(effect.get("risingIds").isEmpty()).isTrue());
    }

    @Test
    @DisplayName("A monster casts Finger of Death at Aria: she drops to 0 and is dying, not killed, so nothing rises")
    void monsterCastsFingerOfDeath() {
        String lich = monster("Lich", "undead", 135, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 20,
                 "atWillSpells": ["finger of death"]}}""")));
        start();
        // 7d8 of 1s + 30 = 37; Aria's CON save of 1 fails
        dice.script(1, 1, 1, 1, 1, 1, 1, 1);
        castBy(lich, "Finger of Death", List.of(ariaId), null);
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isZero();
        assertThat(aria.get("lifeState").asString()).isEqualTo("DYING");
        assertThat(participant(dmState(), lich).get("activeEffects").size()).isZero();
    }

    @Test
    @DisplayName("Prismatic Spray: each creature's d8 picks its ray; yellow deals lightning, and indigo restrains until "
            + "three failed CON saves petrify it (PHB p.267)")
    void prismaticSpray() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 60, builder -> builder.constitution(10));
        start();
        // Ogre: d8 3 (yellow), 10d6 of 2s = 20, DEX save 2 - 1 fails. Troll: d8 6 (indigo), DEX save 1 - 1 fails
        dice.script(3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 6, 1);
        cast("Prismatic Spray", 7, List.of(ogre, troll), null);
        assertThat(hp(ogre)).isEqualTo(40);
        assertThat(hp(troll)).isEqualTo(60);
        assertThat(condition(troll, "restrained")).isNotNull();
        assertThat(log()).anyMatch(line -> line.contains("Ogre is struck by the yellow ray"));
        assertThat(log()).anyMatch(line -> line.contains("Troll is struck by the indigo ray"));

        for (int round = 0; round < 3; round++) {
            nextTurn();
            nextTurn();
            // The end of the troll's turn: its CON save of 2 fails
            dice.script(2);
            nextTurn();
        }
        assertThat(condition(troll, "petrified")).isNotNull();
        assertThat(condition(troll, "restrained")).isNull();
    }

    @Test
    @DisplayName("Delayed Blast Fireball: 12d6 grows 1d6 each time Aria ends her turn, and explodes over the ogre when "
            + "her concentration ends (PHB p.230)")
    void delayedBlastFireball() {
        String ogre = monster("Ogre", "giant", 100, UnaryOperator.identity());
        start();
        cast("Delayed Blast Fireball", 7, List.of(ogre), null);
        assertThat(detonation().get("dice").asString()).isEqualTo("12d6");
        assertThat(hp(ogre)).isEqualTo(100);

        nextTurn();
        nextTurn();
        nextTurn();
        assertThat(detonation().get("dice").asString()).isEqualTo("14d6");

        // 14d6 of 2s = 28, and the ogre's DEX save of 2 - 1 fails
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        dropConcentration();
        assertThat(hp(ogre)).isEqualTo(72);
        assertThat(log()).anyMatch(line -> line.contains("Delayed Blast Fireball explodes: 14d6 fire"));
    }

    @Test
    @DisplayName("Divine Word by current hit points: 15 dies, 28 is blinded, deafened and stunned, a 45-HP fiend is "
            + "deafened and sent home, and 80 is unharmed (PHB p.234)")
    void divineWord() {
        String goblin = monster("Goblin", "humanoid", 15, UnaryOperator.identity());
        String ogre = monster("Ogre", "giant", 28, UnaryOperator.identity());
        String imp = monster("Imp", "fiend", 45, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 80, UnaryOperator.identity());
        start();
        dice.script(2, 2, 2, 2);
        cast("Divine Word", 7, List.of(goblin, ogre, imp, troll), null);

        assertThat(participant(dmState(), goblin).get("lifeState").asString()).isEqualTo("DEAD");
        for (String name : List.of("blinded", "deafened", "stunned")) {
            assertThat(condition(ogre, name)).isNotNull();
        }
        assertThat(condition(imp, "deafened")).isNotNull();
        assertThat(condition(imp, "banished")).isNotNull();
        assertThat(participant(dmState(), troll).get("activeConditions").isEmpty()).isTrue();
        assertThat(log()).anyMatch(line -> line.contains("Goblin is killed outright by Aria's Divine Word"));
    }

    @Test
    @DisplayName("Forcecage: the box encloses with no save and blocks Aria's attack; the cage only traps; neither needs "
            + "concentration (PHB p.243)")
    void forcecage() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 60, UnaryOperator.identity());
        start();
        cast("Forcecage", 7, List.of(ogre), "box");
        assertThat(condition(ogre, "enclosed")).isNotNull();
        assertThat(condition(ogre, "enclosed").get("duration").asInt()).isEqualTo(600);
        JsonNode concentration = participant(dmState(), ariaId).path("concentrationSpell");
        assertThat(concentration.isMissingNode() || concentration.isNull()).isTrue();
        ResponseEntity<String> blocked = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/attack?actorId=" + ariaId, Map.of("targetId", ogre, "attackBonus", 6, "damageDice", "1d8",
                "damageType", "slashing"), dm.accessToken());
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        nextTurn();
        nextTurn();
        nextTurn();
        cast("Forcecage", 7, List.of(troll), "cage");
        assertThat(condition(troll, "caged")).isNotNull();
        assertThat(condition(ogre, "enclosed")).isNotNull();
    }

    @Test
    @DisplayName("Plane Shift: a hit, then a failed CHA save, banishes the ogre; a mage plane shifts itself out of the "
            + "fight (PHB p.266)")
    void planeShift() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String mage = monster("Mage", "humanoid", 40, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 14,
                 "atWillSpells": ["plane shift (self only)"]}}""")));
        start();
        // A hit of 15 + 6, then the ogre's CHA save of 3 - 2 fails
        dice.script(15, 3);
        cast("Plane Shift", 7, List.of(ogre), "banish");
        assertThat(condition(ogre, "banished")).isNotNull();

        castBy(mage, "Plane Shift", List.of(mage), "travel");
        assertThat(condition(mage, "plane shifted")).isNotNull();
        // Both are gone from the fight: the turn comes straight back to Aria
        nextTurn();
        assertThat(log()).contains("Turn passes to Aria (Round 2)");
    }

    @Test
    @DisplayName("Regenerate, recorded as already active (a 1-minute cast), gives 1 HP at the start of each of Aria's "
            + "turns (PHB p.271)")
    void regenerate() {
        monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/active-spell",
                Map.of("casterId", ariaId, "spellName", "Regenerate", "targetIds", List.of(ariaId)),
                dm.accessToken()));
        hurtAria(10);
        nextTurn();
        nextTurn();
        assertThat(hp(ariaId)).isEqualTo(21);
        nextTurn();
        nextTurn();
        assertThat(hp(ariaId)).isEqualTo(22);
    }

    @Test
    @DisplayName("Resurrection: the DM revives a dead character with all its hit points; without the option, with 1 "
            + "(PHB p.272)")
    void resurrection() {
        kill();
        JsonNode revived = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId
                + "/revive?fullHitPoints=true", null, dm.accessToken()));
        assertThat(revived.get("hpCurrent").asInt()).isEqualTo(revived.get("hpMax").asInt());
        assertThat(revived.get("hpCurrent").asInt()).isGreaterThan(1);

        kill();
        JsonNode once = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId + "/revive",
                null, dm.accessToken()));
        assertThat(once.get("hpCurrent").asInt()).isEqualTo(1);
        expect(HttpStatus.FORBIDDEN, exchange(HttpMethod.POST, "/api/characters/" + characterId
                + "/revive?fullHitPoints=true", null, player.accessToken()));
    }

    @Test
    @DisplayName("A 7th-level Mystic Arcanum casts Finger of Death at its own level with no slot, once (PHB p.108, D186)")
    void mysticArcanumAtSeventh() {
        String ogre = monster("Ogre", "giant", 80, UnaryOperator.identity());
        start();
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(new HashMap<>());
        aria.setSpellsKnown(List.of(new KnownSpell(null, "Finger of Death", 7, KnownSpell.MYSTIC_ARCANUM_SOURCE
                + "Warlock", true, null, null, null, null)));
        aria.setResourcePoolsCurrent(new ArrayList<>(List.of(
                com.tabletophelper.character.state.ResourcePoolEntry.mysticArcanum(7, "Finger of Death"))));
        participantRepository.save(aria);
        Map<String, Object> body = Map.of("spellName", "Finger of Death", "spellSource", "MYSTIC_ARCANUM",
                "slotLevel", 0, "targetIds", List.of(ogre));
        // 7d8 of 1s + 30 = 37; the ogre's CON save of 2 + 3 fails
        dice.script(1, 1, 1, 1, 1, 1, 1, 2);
        JsonNode result = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, body, dm.accessToken()));
        assertThat(result.get("slotLevelUsed").asInt()).isEqualTo(7);
        assertThat(hp(ogre)).isEqualTo(80 - 37);
        assertThat(exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + ariaId, body, dm.accessToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void kill() {
        PlayerCharacter character = characterRepository.findById(characterId).orElseThrow();
        character.setCampaign(campaignRepository.findById(campaignId).orElseThrow());
        character.setDead(true);
        character.setHpCurrent(0);
        characterRepository.save(character);
    }

    private void hurtAria(int amount) {
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpCurrent(aria.getHpCurrent() - amount);
        participantRepository.save(aria);
    }

    private JsonNode detonation() {
        return participant(dmState(), ariaId).get("activeEffects").get(0).get("detonation");
    }

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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("7", new SpellSlot(4, 0), "8", new SpellSlot(2, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellcastingAbility("WIS");
        List<KnownSpell> known = new ArrayList<>();
        for (String spell : SHIPPED) {
            known.add(new KnownSpell(null, spell, 7, "class:Cleric", true, null, null, null, null));
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
    private void castBy(String monsterId, String spell, List<String> targetIds, String option) {
        Map<String, Object> body = new HashMap<>(Map.of("spellName", spell, "slotLevel", 0, "targetIds", targetIds));
        if (option != null) {
            body.put("option", option);
        }
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + monsterId, body, dm.accessToken()));
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

    private List<String> names() {
        List<String> names = new ArrayList<>();
        dmState().get("participants").forEach(p -> names.add(p.get("displayName").asString()));
        return names;
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        return byName(encounter, displayName).get("id").asString();
    }

    private static JsonNode byName(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asString().equals(displayName)) {
                return participant;
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
