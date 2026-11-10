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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 4th-level spells end to end (D185), with their templates as the game data ships them. A table of a DM and one
 * player, whose cleric Aria (CON 12, spell attack +6, save DC 14, 30 HP) acts first on 20; each test adds the creatures
 * it needs, acting after her.
 */
class FourthLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Ice Storm", "Evard's Black Tentacles", "Sickening Radiance",
            "Vitriolic Sphere", "Guardian of Faith", "Fire Shield", "Banishment", "Death Ward", "Stoneskin", "Blight",
            "Greater Invisibility", "Dominate Beast", "Hypnotic Pattern");

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
    @DisplayName("Ice Storm rolls 2d8 bludgeoning and 4d6 cold together, and a cold-resistant ogre halves only the cold")
    void iceStormTwoTypes() {
        String ogre = monster("Frost Ogre", "giant", 40, builder -> builder.damageResistances("[\"cold\"]"));
        start();
        // 2d8 (3, 4) = 7 bludgeoning, 4d6 (1, 2, 3, 4) = 10 cold, and the ogre's DEX save of 2 - 1 fails against DC 14
        dice.script(3, 4, 1, 2, 3, 4, 2);
        cast("Ice Storm", 4, List.of(ogre), null);

        assertThat(hp(ogre)).isEqualTo(40 - 7 - 5);
        assertThat(log()).anyMatch(line -> line.contains("7 bludgeoning") && line.contains("5 cold"));
    }

    @Test
    @DisplayName("Evard's Black Tentacles restrains on a failed save from its trigger, damages an already restrained "
            + "creature with no save, and the restraint ends with the spell")
    void evardsTentacles() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        cast("Evard's Black Tentacles", 4, List.of(), null);

        // 3d6 (2, 2, 2) and the ogre's DEX save of 1 - 1 fails against DC 14
        dice.script(2, 2, 2, 1);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre), "trigger", "ENTER")));
        assertThat(hp(ogre)).isEqualTo(54);
        JsonNode restrained = condition(ogre, "restrained");
        assertThat(restrained).isNotNull();
        assertThat(restrained.get("concentrationInstanceId").isNull()).isFalse();

        // Already restrained at the start of its turn: 3d6 (1, 1, 1) and no save is rolled
        dice.script(1, 1, 1);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre), "trigger", "START_OF_TURN")));
        assertThat(hp(ogre)).isEqualTo(51);
        assertThat(log().getLast()).doesNotContain("save");

        endConcentration();
        assertThat(condition(ogre, "restrained")).isNull();
    }

    @Test
    @DisplayName("Sickening Radiance's failed save gives 4d10 radiant, a level of exhaustion and its light; the "
            + "exhaustion goes when the spell ends")
    void sickeningRadiance() {
        String ogre = monster("Ogre", "giant", 60, builder -> builder.constitution(10));
        start();
        cast("Sickening Radiance", 4, List.of(), null);

        // 4d10 (1, 1, 1, 1) and the ogre's CON save of 3 fails against DC 14
        dice.script(1, 1, 1, 1, 3);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre), "trigger", "START_OF_TURN")));
        JsonNode state = participant(dmState(), ogre);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(56);
        assertThat(state.get("exhaustionLevel").asInt()).isEqualTo(1);
        assertThat(state.get("spellModifiers").toString()).contains("Sickening Radiance",
                "can't benefit from being invisible");

        endConcentration();
        JsonNode after = participant(dmState(), ogre);
        assertThat(after.get("exhaustionLevel").asInt()).isZero();
        assertThat(after.path("spellModifiers").toString()).doesNotContain("Sickening Radiance");
    }

    @Test
    @DisplayName("Vitriolic Sphere: a failed save takes 10d4 at once and 5d4 more at the end of its next turn; a "
            + "successful one takes half and nothing later")
    void vitriolicSphere() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 60, builder -> builder.dexterity(10));
        start();
        // 10d4 of 2s = 20; the ogre's DEX save of 2 - 1 fails, the troll's 19 succeeds
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 19);
        cast("Vitriolic Sphere", 4, List.of(ogre, troll), null);
        assertThat(hp(ogre)).isEqualTo(40);
        assertThat(hp(troll)).isEqualTo(50);
        assertThat(participant(dmState(), ariaId).get("activeEffects").get(0).get("delayed")).hasSize(1);

        nextTurn();
        // The end of the ogre's turn: 5d4 of 3s = 15 acid
        dice.script(3, 3, 3, 3, 3);
        nextTurn();
        assertThat(hp(ogre)).isEqualTo(25);
        assertThat(hp(troll)).isEqualTo(50);
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
    }

    @Test
    @DisplayName("Guardian of Faith vanishes once it has dealt 60 damage in all")
    void guardianOfFaithBudget() {
        String giant = monster("Hill Giant", "giant", 200, UnaryOperator.identity());
        start();
        cast("Guardian of Faith", 4, List.of(), null);
        Map<String, Object> enters = Map.of("targetIds", List.of(giant), "trigger", "ENTER");

        // Fails twice for 20 each, succeeds once for 10: 50 dealt, 10 left
        for (int save : new int[]{2, 2, 20}) {
            dice.script(save);
            expect(HttpStatus.OK, use(enters));
        }
        assertThat(hp(giant)).isEqualTo(150);
        assertThat(participant(dmState(), ariaId).get("activeEffects").get(0).get("damageLeft").asInt())
                .isEqualTo(10);

        dice.script(2);
        expect(HttpStatus.OK, use(enters));
        assertThat(hp(giant)).isEqualTo(130);
        assertThat(participant(dmState(), ariaId).get("activeEffects")).isEmpty();
        assertThat(log()).anyMatch(line -> line.contains("Guardian of Faith ends (has dealt all its damage)"));
    }

    @Test
    @DisplayName("Fire Shield's warm shield resists cold and burns a creature that hits Aria in melee for 2d8 fire")
    void fireShield() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        expect(HttpStatus.BAD_REQUEST, castRequest("Fire Shield", 4, List.of(), null));
        cast("Fire Shield", 4, List.of(), "warm");

        // The ogre hits Aria in melee: d20 18, 1d8 (4) bludgeoning; the shield deals 2d8 (5, 6) fire back
        dice.script(18, 4, 5, 6);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8", "damageType", "bludgeoning"),
                dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(26);
        assertThat(hp(ogre)).isEqualTo(49);
        assertThat(log()).anyMatch(line -> line.startsWith("Aria's Fire Shield strikes back at Ogre"));

        // A ranged hit isn't answered
        dice.script(18, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8", "damageType", "piercing",
                "within5Ft", false), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(49);

        // Cold damage is halved
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ariaId, "amount", 10, "damageType", "cold"), dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(17);
    }

    @Test
    @DisplayName("Banishment removes the ogre from the turn order and from reach until the spell ends")
    void banishment() {
        String ogre = monster("Ogre", "giant", 60, builder -> builder.charisma(7));
        start();
        // The ogre's CHA save of 2 - 2 fails against DC 14
        dice.script(2);
        cast("Banishment", 4, List.of(ogre), null);
        assertThat(condition(ogre, "banished")).isNotNull();

        nextTurn();
        assertThat(log()).anyMatch(line -> line.equals("Turn passes to Aria (Round 2)"));
        ResponseEntity<String> attack = exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/attack?actorId=" + ariaId, Map.of("targetId", ogre, "attackBonus", 5, "damageDice", "1d8"),
                dm.accessToken());
        assertThat(attack.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(attack).get("error").asString()).isEqualTo("Ogre is banished");

        dropConcentration();
        assertThat(condition(ogre, "banished")).isNull();
        nextTurn();
        assertThat(log()).anyMatch(line -> line.equals("Turn passes to Ogre (Round 2)"));
    }

    @Test
    @DisplayName("Death Ward drops Aria to 1 hit point instead of 0, once, and ends")
    void deathWard() {
        start();
        cast("Death Ward", 4, List.of(ariaId), null);
        damage(ariaId, 50, null);

        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(1);
        assertThat(aria.get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(aria.get("activeEffects")).isEmpty();
        assertThat(log()).anyMatch(line -> line.contains("drops to 1 hit point instead of 0 (Death Ward)"));

        damage(ariaId, 5, null);
        assertThat(hp(ariaId)).isZero();
    }

    @Test
    @DisplayName("Stoneskin halves a nonmagical slashing hit, but not one from a monster whose weapons are magical")
    void stoneskin() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String knight = monster("Death Knight", "undead", 60, builder -> builder.traits(
                "[{\"name\": \"Magic Weapons\", \"entries\": [\"Its weapon attacks are magical.\"]}]"));
        start();
        cast("Stoneskin", 4, List.of(ariaId), null);

        // d20 18 hits for 8 slashing, halved; Aria's concentration check of 15 + 1 holds
        dice.script(18, 8, 15);
        attack(ogre, ariaId, "slashing");
        assertThat(hp(ariaId)).isEqualTo(26);
        dice.script(18, 8, 15);
        attack(knight, ariaId, "slashing");
        assertThat(hp(ariaId)).isEqualTo(18);
        assertThat(participant(dmState(), ariaId).get("spellModifiers").toString())
                .contains("resistance to nonmagical bludgeoning, piercing, slashing damage");
    }

    @Test
    @DisplayName("A monster casts Blight: no effect on an undead, maximum damage and a save with disadvantage for a "
            + "plant; and Greater Invisibility, which its attack doesn't end")
    void monsterBlightAndGreaterInvisibility() {
        String hag = monster("Green Hag", "fey", 82, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "CHA", "saveDC": 15,
                 "atWillSpells": ["blight", "greater invisibility"]}}""")));
        String zombie = monster("Zombie", "undead", 22, UnaryOperator.identity());
        String mound = monster("Shambling Mound", "plant", 136, builder -> builder.constitution(16));
        start();

        castBy(hag, "Blight", List.of(zombie));
        assertThat(hp(zombie)).isEqualTo(22);
        assertThat(log()).anyMatch(line -> line.contains("Blight has no effect on Zombie (undead)"));

        // Disadvantage: d20s 18 and 2 keep 2, + 3 fails against DC 15; 8d8 at its most is 64
        dice.script(18, 2);
        castBy(hag, "Blight", List.of(mound));
        assertThat(hp(mound)).isEqualTo(136 - 64);
        assertThat(log()).anyMatch(line -> line.contains("saves with disadvantage and takes maximum damage"));

        castBy(hag, "Greater Invisibility", List.of(hag));
        assertThat(condition(hag, "invisible")).isNotNull();
        // Invisible, the hag attacks with advantage: d20s 15 and 15, 1d8 (3)
        dice.script(15, 15, 3);
        attack(hag, ariaId, "slashing");
        assertThat(condition(hag, "invisible")).isNotNull();
    }

    @Test
    @DisplayName("Dominate Beast refuses a creature that isn't a beast, and lasts 10 minutes with a 5th-level slot")
    void dominateBeast() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String wolf = monster("Wolf", "beast", 11, builder -> builder.wisdom(12));
        start();
        ResponseEntity<String> refused = castRequest("Dominate Beast", 4, List.of(ogre), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(refused).get("error").asString()).contains("can only target beast creatures");

        // The wolf's WIS save of 2 + 1 fails against DC 14
        dice.script(2);
        cast("Dominate Beast", 5, List.of(wolf), null);
        assertThat(condition(wolf, "charmed").get("duration").asInt()).isEqualTo(100);

        // Damage makes it save again: 19 + 1 succeeds, and the spell ends
        dice.script(19);
        damage(wolf, 2, null);
        assertThat(condition(wolf, "charmed")).isNull();
        assertThat(participant(dmState(), ariaId).path("concentration").isNull()
                || participant(dmState(), ariaId).path("concentration").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("Hypnotic Pattern's charm ends on the creature that takes damage, and stays on the other")
    void hypnoticPatternEndsOnDamage() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 60, UnaryOperator.identity());
        start();
        dice.script(2, 2);
        cast("Hypnotic Pattern", 3, List.of(ogre, troll), null);
        assertThat(condition(ogre, "charmed")).isNotNull();

        damage(ogre, 5, null);
        assertThat(condition(ogre, "charmed")).isNull();
        assertThat(condition(ogre, "incapacitated")).isNull();
        assertThat(condition(troll, "charmed")).isNotNull();
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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("3", new SpellSlot(3, 0), "4", new SpellSlot(3, 0),
                "5", new SpellSlot(2, 0))));
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
