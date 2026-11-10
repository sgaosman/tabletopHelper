package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * The weapon riders with a follow-up, the weapon-attack cantrips and the spells of several or split rolls end to end
 * (D199), with their templates as the game data ships them. A DM and one player, whose cleric Aria (WIS 16, so spell
 * save DC 13 and spell attack +5, 30 HP, carrying a longsword) acts first on 20; each test adds an ogre (AC 11, 60 HP)
 * and a goblin (AC 11, 20 HP), all of whose abilities are 10, acting after her.
 */
class RiderAndMissileSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Searing Smite", "Wrathful Smite", "Ensnaring Strike",
            "Hail of Thorns", "Lightning Arrow", "Booming Blade", "Green-Flame Blade", "Magic Missile", "Chaos Bolt",
            "Ice Knife", "Life Transference");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String ariaId;
    private String ogre;
    private String goblin;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        RegisteredUser player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        UUID character = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters",
                Map.of("name", "Aria", "raceId", insertRace("Human"),
                        "classId", insertCaster("Cleric", 8, "WIS", false), "strength", 10, "dexterity", 10,
                        "constitution", 10, "intelligence", 10, "wisdom", 16, "charisma", 10),
                player.accessToken())).get("id").asString());
        Item longsword = itemRepository.save(Item.builder().name("Longsword").type("Melee Weapon").subtype("martial")
                .damageDice("1d8").damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                .source("PHB").build());
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Longsword", "quantity", 1, "itemId", longsword.getId())),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken()));
        encounterId = createEncounter(dm.accessToken(), campaignId);
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);
        ogre = monster("Ogre", 60);
        goblin = monster("Goblin", 20);
    }

    @Test
    @DisplayName("Wrathful Smite: the hit adds 1d6 psychic, the failed WIS save frightens the ogre, and its action's "
            + "WIS check ends the spell (PHB p.289)")
    void wrathfulSmite() {
        start();
        cast("Wrathful Smite", 1, List.of());
        // 15 + 6 hits AC 11; 1d8 4, 1d6 3; the WIS save rolls 5 against DC 13
        dice.script(15, 4, 3, 5);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(53);
        String frightened = conditionId(ogre, "frightened");

        nextTurn();
        // frightened, so disadvantage: 15 and 14 keep 14, + 0 beats DC 13
        dice.script(15, 14);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/action-save",
                Map.of("participantId", ogre, "conditionId", frightened), dm.accessToken()));
        assertThat(conditionNames(ogre)).doesNotContain("frightened");
        assertThat(participant(dmState(), ariaId).path("concentrationSpell").isNull()
                || participant(dmState(), ariaId).path("concentrationSpell").isMissingNode()).isTrue();
        assertThat(log()).anyMatch(line -> line.contains("WIS check against frightened"));
    }

    @Test
    @DisplayName("Searing Smite: the hit ignites the ogre, which burns for 1d6 fire on a failed CON save at the start "
            + "of its turn (PHB p.274)")
    void searingSmite() {
        start();
        cast("Searing Smite", 1, List.of());
        dice.script(15, 4, 5);
        enteredAttack(ogre, true);
        assertThat(conditionNames(ogre)).contains("ignited");
        // the ogre's turn: 1d6 3 and its CON save 2
        dice.script(3, 2);
        nextTurn();
        assertThat(hp(ogre)).isEqualTo(60 - 9 - 3);
    }

    @Test
    @DisplayName("Ensnaring Strike: a failed STR save restrains, and the thorns deal 1d6 piercing at the start of the "
            + "target's turn (PHB p.237)")
    void ensnaringStrike() {
        start();
        cast("Ensnaring Strike", 1, List.of());
        dice.script(15, 4, 3);
        enteredAttack(ogre, true);
        assertThat(conditionNames(ogre)).contains("restrained");
        dice.script(5);
        nextTurn();
        assertThat(hp(ogre)).isEqualTo(60 - 4 - 5);
    }

    @Test
    @DisplayName("Hail of Thorns: the ranged hit makes the ogre and the goblin picked at the cast save on DEX against "
            + "1d10 piercing (PHB p.249)")
    void hailOfThorns() {
        start();
        cast("Hail of Thorns", 1, List.of(goblin));
        // 1d8 4; the thorns' 1d10 8; the ogre's save 5 fails, the goblin's 18 halves
        dice.script(15, 4, 8, 5, 18);
        enteredAttack(ogre, false);
        assertThat(hp(ogre)).isEqualTo(60 - 4 - 8);
        assertThat(hp(goblin)).isEqualTo(20 - 4);
    }

    @Test
    @DisplayName("Lightning Arrow: the ranged hit deals 4d8 lightning instead of the weapon's damage, and the goblin "
            + "picked saves on DEX against 2d8 (PHB p.255)")
    void lightningArrow() {
        start();
        cast("Lightning Arrow", 3, List.of(goblin));
        dice.script(15, 7, 2, 2, 2, 2, 3, 3, 2);
        enteredAttack(ogre, false);
        assertThat(hp(ogre)).isEqualTo(60 - 8);
        assertThat(hp(goblin)).isEqualTo(20 - 6);
    }

    @Test
    @DisplayName("Green-Flame Blade: Aria's longsword hit, then the fire leaps to the goblin for her WIS modifier "
            + "(TCE p.107)")
    void greenFlameBlade() {
        start();
        // 15 hits AC 11; the longsword's 1d8 4; the leap is WIS +3 with no dice at 1st level
        dice.script(15, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", "Green-Flame Blade", "slotLevel", 0,
                "targetIds", List.of(ogre, goblin), "option", "Longsword"), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(56);
        assertThat(hp(goblin)).isEqualTo(17);
        assertThat(log()).anyMatch(line -> line.contains("Longsword (Green-Flame Blade)"));
    }

    @Test
    @DisplayName("Booming Blade: the hit sheathes the ogre, and the DM's use of the effect when it moves deals 1d8 "
            + "thunder and ends the spell (TCE p.106)")
    void boomingBlade() {
        start();
        dice.script(15, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", "Booming Blade", "slotLevel", 0,
                "targetIds", List.of(ogre), "option", "Longsword"), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(56);
        JsonNode effects = participant(dmState(), ariaId).get("activeEffects");
        assertThat(effects).hasSize(1);
        String effectId = effects.get(0).get("id").asString();

        dice.script(6);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/repeat-spell-effect?actorId=" + ariaId,
                Map.of("effectId", effectId, "targetIds", List.of(ogre)), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(50);
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
    }

    @Test
    @DisplayName("Magic Missile: three darts at the one creature named, each its own 1d4 + 1 (PHB p.257)")
    void magicMissile() {
        start();
        dice.script(1, 2, 3);
        cast("Magic Missile", 1, List.of(ogre));
        assertThat(hp(ogre)).isEqualTo(60 - 9);
        assertThat(log().stream().filter(line -> line.startsWith("Aria casts Magic Missile at Ogre"))).hasSize(3);
    }

    @Test
    @DisplayName("Chaos Bolt: matching d8s leap to the goblin listed after the ogre (XGE p.151)")
    void chaosBolt() {
        start();
        dice.script(15, 2, 2, 3, 15, 1, 6, 2);
        cast("Chaos Bolt", 1, List.of(ogre, goblin));
        assertThat(hp(ogre)).isEqualTo(53);
        assertThat(hp(goblin)).isEqualTo(11);
    }

    @Test
    @DisplayName("Ice Knife: the shard's 1d10, then the burst over the ogre and the goblin listed after it, 2d6 cold "
            + "on a failed DEX save (XGE p.157)")
    void iceKnife() {
        start();
        dice.script(15, 6, 3, 3, 5, 18);
        cast("Ice Knife", 1, List.of(ogre, goblin));
        assertThat(hp(ogre)).isEqualTo(60 - 12);
        assertThat(hp(goblin)).isEqualTo(20);
    }

    @Test
    @DisplayName("Life Transference: Aria takes 4d8 necrotic and the goblin regains twice that, up to its maximum "
            + "(XGE p.160)")
    void lifeTransference() {
        start();
        EncounterParticipant wounded = participantRepository.findById(UUID.fromString(goblin)).orElseThrow();
        wounded.setHpCurrent(5);
        participantRepository.save(wounded);
        dice.script(2, 3, 4, 5);
        cast("Life Transference", 3, List.of(goblin));
        assertThat(hp(ariaId)).isEqualTo(30 - 14);
        assertThat(hp(goblin)).isEqualTo(20);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String monster(String name, int hp) {
        Monster saved = monsterRepository.save(Monster.builder().name(name).type("humanoid").challengeRating("1")
                .source("MM").hitPoints(hp).armourClass(11).strength(10).dexterity(10).constitution(10)
                .intelligence(10).wisdom(10).charisma(10).build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
        initiatives.put(id, 19 - initiatives.size());
        return id;
    }

    // Starts the fight, then gives Aria her slots and spells, which the start would otherwise copy from her sheet (D166)
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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0))));
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

    private void cast(String spell, int slotLevel, List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds), dm.accessToken()));
    }

    // Aria's attack entered by hand, +6 with 1d8, from within 5 feet or from range
    private void enteredAttack(String targetId, boolean withinFiveFeet) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", targetId, "attackBonus", 6, "damageDice", "1d8", "damageType",
                withinFiveFeet ? "slashing" : "piercing", "within5Ft", withinFiveFeet), dm.accessToken()));
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

    private String conditionId(String participantId, String name) {
        for (JsonNode condition : participant(dmState(), participantId).get("activeConditions")) {
            if (condition.get("name").asString().equals(name)) {
                return condition.get("id").asString();
            }
        }
        throw new AssertionError(name + " is not on " + participantId);
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
