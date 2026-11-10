package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 6th-level spells end to end (D188), with their templates as the game data ships them. A table of a DM and one
 * player, whose cleric Aria (spell attack +6, save DC 14, 30 hit points) acts first on 20; each test adds the creatures
 * it needs, acting after her.
 */
class SixthLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Chain Lightning", "Disintegrate", "Harm", "Heal", "Sunbeam",
            "Globe of Invulnerability", "True Seeing", "Otto's Irresistible Dance", "Ice Storm", "Flesh to Stone");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();
    private final Map<String, Integer> hitPoints = new LinkedHashMap<>();

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
        hitPoints.clear();
        initiatives.put(ariaId, 20);
        hitPoints.put(ariaId, 30);
    }

    @Test
    @DisplayName("Chain Lightning strikes four creatures at 6th level, each saving on its own, and refuses a fifth")
    void chainLightning() {
        List<String> ogres = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ogres.add(monster("Ogre " + i, "giant", 60, UnaryOperator.identity()));
        }
        start();
        assertThat(castRequest("Chain Lightning", 6, ogres, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 10d8 of 3s = 30; three DEX saves of 2 - 1 fail, the fourth of 20 succeeds for 15
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 20);
        cast("Chain Lightning", 6, ogres.subList(0, 4), null);
        assertThat(ogres.subList(0, 4).stream().map(this::hp).toList()).containsExactly(30, 30, 30, 45);
        assertThat(hp(ogres.get(4))).isEqualTo(60);
    }

    @Test
    @DisplayName("Disintegrate leaves a player character it drops to 0 hit points dead, as dust, with no death saves")
    void disintegrateDust() {
        String brom = companion("Brom", "Fighter", 5, 30);
        start();
        // 10d6 of 1s + 40 = 50; Brom's DEX save of 2 + 2 fails
        dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2);
        cast("Disintegrate", 6, List.of(brom), null);

        JsonNode state = participant(dmState(), brom);
        assertThat(state.get("hpCurrent").asInt()).isZero();
        assertThat(state.get("lifeState").asString()).isEqualTo("DEAD");
        assertThat(log()).anyMatch(line -> line.contains("Brom is disintegrated, reduced to fine gray dust"));
    }

    @Test
    @DisplayName("Harm leaves the ogre at 1 hit point at least, and its failed save lowers its maximum by the damage "
            + "taken until the Spell Effect ends")
    void harmFloorAndMaximum() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // 14d6 of 6s = 84, held to 59; the ogre's CON save of 2 + 3 fails
        int[] rolls = new int[15];
        Arrays.fill(rolls, 6);
        rolls[14] = 2;
        dice.script(rolls);
        cast("Harm", 6, List.of(ogre), null);

        JsonNode state = participant(dmState(), ogre);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(1);
        assertThat(state.get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(state.get("hpMax").asInt()).isEqualTo(1);
        assertThat(log()).anyMatch(line -> line.contains("Harm can't reduce Ogre below 1 hit point: it takes 59"));
        assertThat(log()).anyMatch(line -> line.contains("Ogre's hit point maximum is reduced by 59 for 1 hour"));

        endEffect("Harm");
        assertThat(participant(dmState(), ogre).get("hpMax").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("Heal at 7th level restores 80 hit points and ends blinded and deafened, and does nothing to a zombie")
    void heal() {
        String brom = companion("Brom", "Fighter", 5, 100);
        String zombie = monster("Zombie", "undead", 22, UnaryOperator.identity());
        start();
        setHp(brom, 10);
        addCondition(brom, "blinded");
        addCondition(brom, "deafened");
        cast("Heal", 7, List.of(brom), null);
        assertThat(hp(brom)).isEqualTo(90);
        assertThat(condition(brom, "blinded")).isNull();
        assertThat(condition(brom, "deafened")).isNull();

        setHp(zombie, 5);
        cast("Heal", 6, List.of(zombie), null);
        assertThat(hp(zombie)).isEqualTo(5);
        assertThat(log()).anyMatch(line -> line.contains("Heal has no effect on Zombie (undead)"));
    }

    @Test
    @DisplayName("Sunbeam blinds on a failed save, a zombie saves with disadvantage, and an action makes a new line")
    void sunbeamRepeat() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String zombie = monster("Zombie", "undead", 60, UnaryOperator.identity());
        start();
        // 6d8 of 2s = 12; the ogre's CON 2 + 3 fails; the zombie's d20s 18 and 3 keep 3 + 3, which fails
        dice.script(2, 2, 2, 2, 2, 2, 2, 18, 3);
        cast("Sunbeam", 6, List.of(ogre, zombie), null);
        assertThat(hp(ogre)).isEqualTo(48);
        assertThat(hp(zombie)).isEqualTo(48);
        assertThat(condition(ogre, "blinded")).isNotNull();
        assertThat(condition(zombie, "blinded")).isNotNull();

        // A new line on the ogre: 6d8 of 1s, a save of 20 succeeds for half
        dice.script(1, 1, 1, 1, 1, 1, 20);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre))));
        assertThat(hp(ogre)).isEqualTo(45);
    }

    @Test
    @DisplayName("A mage's Globe of Invulnerability keeps Aria's 4th-level Ice Storm out, even upcast, but not her "
            + "6th-level Chain Lightning; the mage's own Chain Lightning reaches Aria")
    void monsterGlobeAndChainLightning() {
        String mage = monster("Mage", "humanoid", 40, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 15,
                 "atWillSpells": ["globe of invulnerability", "chain lightning"]}}""")));
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        castBy(mage, "Globe of Invulnerability", List.of(mage, ogre));
        assertThat(participant(dmState(), ogre).get("spellModifiers").toString())
                .contains("Globe of Invulnerability", "spells of level 5 or lower cast from outside have no effect");

        cast("Ice Storm", 5, List.of(ogre), null);
        assertThat(hp(ogre)).isEqualTo(60);
        assertThat(log()).anyMatch(line -> line.contains("Ice Storm has no effect on Ogre, inside Globe of "
                + "Invulnerability"));

        // 10d8 of 2s = 20; the ogre's DEX save of 2 - 1 fails
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        cast("Chain Lightning", 6, List.of(ogre), null);
        assertThat(hp(ogre)).isEqualTo(40);

        // The mage's bolt: 10d8 of 1s = 10; Aria's DEX save of 2 + 2 fails against DC 15
        dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2);
        castBy(mage, "Chain Lightning", List.of(ariaId));
        assertThat(hp(ariaId)).isEqualTo(20);
    }

    @Test
    @DisplayName("True Seeing's truesight lets a monster attack invisible Aria without disadvantage")
    void trueSeeingAgainstInvisible() {
        String mage = monster("Mage", "humanoid", 40, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 15,
                 "atWillSpells": ["true seeing"]}}""")));
        start();
        addCondition(ariaId, "invisible");

        // Without truesight: disadvantage, d20s 15 and 2 keep 2, which misses
        dice.script(15, 2);
        attack(mage, ariaId);
        assertThat(log().getLast()).contains("disadvantage");

        castBy(mage, "True Seeing", List.of(mage));
        // With it: one d20 of 15 hits for 1d8 (4)
        dice.script(15, 4);
        attack(mage, ariaId);
        assertThat(log().getLast()).doesNotContain("disadvantage");
        assertThat(hp(ariaId)).isEqualTo(26);
    }

    @Test
    @DisplayName("Evasion: a 7th-level rogue that saves against Chain Lightning takes nothing, a 7th-level monk that "
            + "fails takes half")
    void evasion() {
        String rogue = companion("Vex", "Rogue", 7, 60);
        String monk = companion("Kai", "Monk", 7, 60);
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // 10d8 of 4s = 40; the rogue's DEX save of 20 succeeds, the monk's 1 and the ogre's 2 fail
        dice.script(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 20, 1, 2);
        cast("Chain Lightning", 6, List.of(rogue, monk, ogre), null);
        assertThat(hp(rogue)).isEqualTo(60);
        assertThat(hp(monk)).isEqualTo(40);
        assertThat(hp(ogre)).isEqualTo(20);
    }

    @Test
    @DisplayName("Otto's Irresistible Dance: a creature that can't be charmed is immune; the dancer's WIS save is "
            + "made with its action, not at the end of its turn, and a success ends the spell")
    void ottosActionSave() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String golem = monster("Golem", "construct", 60, builder -> builder.conditionImmunities("[\"charmed\"]"));
        start();
        cast("Otto's Irresistible Dance", 6, List.of(golem), null);
        assertThat(condition(golem, "dancing")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("Golem is immune to Otto's Irresistible Dance (can't be "
                + "charmed)"));

        cast("Otto's Irresistible Dance", 6, List.of(ogre), null);
        JsonNode dancing = condition(ogre, "dancing");
        assertThat(dancing).isNotNull();
        assertThat(dancing.get("repeatSave").get("byAction").asBoolean()).isTrue();
        assertThat(participant(dmState(), ogre).get("spellModifiers").toString()).contains("advantage on attack rolls "
                + "against it");

        nextTurn();
        // The ogre's turn: it spends its action on a WIS save of 2 - 2, which fails
        dice.script(2);
        expect(HttpStatus.OK, actionSave(ogre, dancing.get("id").asString()));
        assertThat(condition(ogre, "dancing")).isNotNull();
        // The end of its turn rolls nothing: a 20 queued would end the dance
        dice.script(20);
        nextTurn();
        assertThat(condition(ogre, "dancing")).isNotNull();
        dice.clear();

        nextTurn();
        nextTurn();
        // Its next turn: 19 - 2 succeeds, the dance and the spell end
        dice.script(19);
        expect(HttpStatus.OK, actionSave(ogre, dancing.get("id").asString()));
        assertThat(condition(ogre, "dancing")).isNull();
        assertThat(participant(dmState(), ogre).path("spellModifiers").toString())
                .doesNotContain("Otto's Irresistible Dance");
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
    }

    @Test
    @DisplayName("Flesh to Stone restrains on a failed save, counts CON saves at the end of the ogre's turns, and three "
            + "failures, not in a row, leave it petrified after the spell")
    void fleshToStone() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // The ogre's CON save of 2 + 3 fails against DC 14
        dice.script(2);
        cast("Flesh to Stone", 6, List.of(ogre), null);
        assertThat(condition(ogre, "restrained")).isNotNull();

        // The ends of its turns: 2 fails, 19 succeeds, 3 fails, 4 fails
        for (int roll : new int[]{2, 19, 3, 4}) {
            nextTurn();
            dice.script(roll);
            nextTurn();
        }
        assertThat(condition(ogre, "restrained")).isNull();
        JsonNode petrified = condition(ogre, "petrified");
        assertThat(petrified).isNotNull();
        assertThat(petrified.path("concentrationInstanceId").isNull()
                || petrified.path("concentrationInstanceId").isMissingNode()).isTrue();
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
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

    // A second player character of the class and level, with the hit points given
    private String companion(String name, String className, int level, int hp) {
        UUID classId = insertClass(className, 8);
        UUID characterId = createCharacter(player.accessToken(), insertRace("Elf " + name), classId);
        PlayerCharacter character = characterRepository.findById(characterId).orElseThrow();
        character.setName(name);
        character.setLevel(level);
        character.setCharacterClass(className);
        character.setMulticlassEntries(List.of(new ClassEntry(classId, className, null, null, level)));
        characterRepository.save(character);
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", name)), name);
        initiatives.put(id, 19 - initiatives.size());
        hitPoints.put(id, hp);
        return id;
    }

    // Starts the fight, then gives Aria her spells and the player characters their hit points
    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        hitPoints.forEach((id, hp) -> {
            EncounterParticipant pc = participantRepository.findById(UUID.fromString(id)).orElseThrow();
            pc.setHpMax(hp);
            pc.setHpCurrent(hp);
            participantRepository.save(pc);
        });
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("4", new SpellSlot(3, 0), "5", new SpellSlot(3, 0),
                "6", new SpellSlot(4, 0), "7", new SpellSlot(2, 0))));
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

    private ResponseEntity<String> actionSave(String participantId, String conditionId) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/action-save",
                Map.of("participantId", participantId, "conditionId", conditionId), dm.accessToken());
    }

    private void attack(String attackerId, String targetId) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + attackerId, Map.of("targetId", targetId, "attackBonus", 6, "damageDice", "1d8", "damageType",
                "slashing"), dm.accessToken()));
    }

    private void addCondition(String targetId, String condition) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/add",
                Map.of("targetId", targetId, "condition", condition), dm.accessToken()));
    }

    private void setHp(String participantId, int hp) {
        EncounterParticipant participant = participantRepository.findById(UUID.fromString(participantId))
                .orElseThrow();
        participant.setHpCurrent(hp);
        participantRepository.save(participant);
    }

    private void endEffect(String spell) {
        for (JsonNode effect : participant(dmState(), ariaId).get("activeEffects")) {
            if (spell.equals(effect.get("spell").asString())) {
                expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                                + "/combat/spell-effect/end",
                        Map.of("participantId", ariaId, "effectId", effect.get("id").asString()), dm.accessToken()));
                return;
            }
        }
        throw new AssertionError("Aria has no " + spell + " Spell Effect");
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
