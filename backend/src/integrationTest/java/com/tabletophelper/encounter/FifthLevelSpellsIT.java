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
 * The 5th-level spells end to end (D187), with their templates as the game data ships them. A table of a DM and one
 * player, whose cleric Aria (spell attack +6, save DC 14, 30 HP) acts first on 20; each test adds the creatures it
 * needs, acting after her.
 */
class FifthLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Cone of Cold", "Flame Strike", "Hold Monster",
            "Greater Restoration", "Lesser Restoration", "Dominate Person", "Enervation", "Holy Weapon", "Contagion",
            "Telekinesis", "Vampiric Touch", "Banishing Smite");

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
    @DisplayName("Cone of Cold: 8d8 cold, a CON save halving it, and 1d8 more for each slot above 5th")
    void coneOfCold() {
        String ogre = monster("Ogre", "giant", 80, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 80, builder -> builder.constitution(10));
        start();
        // 9d8 of 2s = 18 at 6th level; the ogre's CON save of 2 + 3 fails, the troll's 19 succeeds (half, 9)
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 19);
        cast("Cone of Cold", 6, List.of(ogre, troll), null);

        assertThat(hp(ogre)).isEqualTo(80 - 18);
        assertThat(hp(troll)).isEqualTo(80 - 9);
    }

    @Test
    @DisplayName("Flame Strike rolls 4d6 fire and 4d6 radiant together, and a fire-resistant creature halves only the "
            + "fire")
    void flameStrikeTwoTypes() {
        String imp = monster("Fire Imp", "fiend", 60, builder -> builder.damageResistances("[\"fire\"]"));
        start();
        // 4d6 (2, 2, 2, 2) = 8 fire, 4d6 (3, 3, 3, 3) = 12 radiant, and the imp's DEX save of 2 - 1 fails
        dice.script(2, 2, 2, 2, 3, 3, 3, 3, 2);
        cast("Flame Strike", 5, List.of(imp), null);

        assertThat(hp(imp)).isEqualTo(60 - 4 - 12);
        assertThat(log()).anyMatch(line -> line.contains("4 fire") && line.contains("12 radiant"));
    }

    @Test
    @DisplayName("Hold Monster paralyzes any creature but an undead, and a WIS save at the end of its turn ends it")
    void holdMonster() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String zombie = monster("Zombie", "undead", 22, UnaryOperator.identity());
        start();
        // The ogre's WIS save of 3 - 2 fails against DC 14; the zombie isn't affected, so it rolls none
        dice.script(3);
        cast("Hold Monster", 6, List.of(ogre, zombie), null);
        assertThat(condition(ogre, "paralyzed")).isNotNull();
        assertThat(condition(zombie, "paralyzed")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("Hold Monster has no effect on Zombie (undead)"));

        nextTurn();
        // At the end of the ogre's turn: 18 - 2 succeeds
        dice.script(18);
        nextTurn();
        assertThat(condition(ogre, "paralyzed")).isNull();
    }

    @Test
    @DisplayName("A monster casts Hold Monster and Cone of Cold at Aria")
    void monsterCasts() {
        String mage = monster("Archmage", "humanoid", 99, builder -> builder.actionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 17,
                 "atWillSpells": ["hold monster", "cone of cold"]}}""")));
        start();
        // Aria's WIS save of 2 fails against DC 17
        dice.script(2);
        castBy(mage, "Hold Monster", List.of(ariaId));
        assertThat(condition(ariaId, "paralyzed")).isNotNull();

        // 8d8 of 1s = 8; Aria's CON save of 1 fails
        dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1);
        castBy(mage, "Cone of Cold", List.of(ariaId));
        assertThat(hp(ariaId)).isEqualTo(30 - 8);
    }

    @Test
    @DisplayName("Greater Restoration lowers exhaustion by one level, or ends charmed; an option is required")
    void greaterRestoration() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/encounters/" + encounterId + "/combat/exhaustion",
                Map.of("participantId", ogre, "level", 2), dm.accessToken()));
        cast("Greater Restoration", 5, List.of(ogre), "exhaustion");
        assertThat(participant(dmState(), ogre).get("exhaustionLevel").asInt()).isEqualTo(1);

        nextTurn();
        nextTurn();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/add",
                Map.of("targetId", ogre, "condition", "charmed"), dm.accessToken()));
        cast("Greater Restoration", 5, List.of(ogre), "charmed");
        assertThat(condition(ogre, "charmed")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("Greater Restoration ends charmed on Ogre"));

        ResponseEntity<String> noOption = castRequest("Greater Restoration", 5, List.of(ogre), null);
        assertThat(noOption.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Lesser Restoration ends the chosen condition, poisoned, and leaves another")
    void lesserRestoration() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        for (String condition : List.of("poisoned", "blinded")) {
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                    + "/combat/condition/add", Map.of("targetId", ogre, "condition", condition), dm.accessToken()));
        }
        cast("Lesser Restoration", 2, List.of(ogre), "poisoned");
        assertThat(condition(ogre, "poisoned")).isNull();
        assertThat(condition(ogre, "blinded")).isNotNull();
    }

    @Test
    @DisplayName("Dominate Person refuses a non-humanoid, puts a humanoid under Aria's player's control, and gives it "
            + "back when damage lets it save")
    void dominatePerson() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String bandit = monster("Bandit", "humanoid", 30, builder -> builder.wisdom(10));
        start();
        ResponseEntity<String> refused = castRequest("Dominate Person", 5, List.of(ogre), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The bandit's WIS save of 3 fails against DC 14
        dice.script(3);
        cast("Dominate Person", 5, List.of(bandit), null);
        assertThat(condition(bandit, "charmed")).isNotNull();
        assertThat(participant(dmState(), bandit).get("controlledByUserId").asString())
                .isEqualTo(player.id().toString());

        // Damage: its WIS save of 19 succeeds, and the spell ends, so the DM has it back
        dice.script(19);
        damage(bandit, 3, null);
        assertThat(condition(bandit, "charmed")).isNull();
        JsonNode freed = participant(dmState(), bandit).get("controlledByUserId");
        assertThat(freed == null || freed.isNull()).isTrue();
        assertThat(log()).anyMatch(line -> line.contains("Bandit is no longer under Aria's control"));
    }

    @Test
    @DisplayName("Enervation: a failed save deals 4d8 and Aria regains half; her action drains again; a successful "
            + "first save ends the spell")
    void enervation() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        String troll = monster("Troll", "giant", 60, builder -> builder.dexterity(10));
        start();
        hurtAria(10);
        // 4d8 (3, 3, 3, 3) = 12, and the ogre's DEX save of 2 - 1 fails: Aria regains 6
        dice.script(3, 3, 3, 3, 2);
        cast("Enervation", 5, List.of(ogre), null);
        assertThat(hp(ogre)).isEqualTo(48);
        assertThat(hp(ariaId)).isEqualTo(26);

        nextTurn();
        nextTurn();
        nextTurn();
        // Her action: 4d8 (2, 2, 2, 2) = 8 with no save, and she regains 4
        dice.script(2, 2, 2, 2);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre))));
        assertThat(hp(ogre)).isEqualTo(40);
        assertThat(hp(ariaId)).isEqualTo(30);

        nextTurn();
        nextTurn();
        nextTurn();
        // The troll's DEX save of 19 succeeds: half of 4d8 (4, 4, 4, 4) = 8, and the spell ends
        dice.script(4, 4, 4, 4, 19);
        cast("Enervation", 5, List.of(troll), null);
        assertThat(hp(troll)).isEqualTo(52);
        JsonNode concentration = participant(dmState(), ariaId).path("concentration");
        assertThat(concentration.isNull() || concentration.isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("Vampiric Touch heals Aria half the necrotic damage of its hit")
    void vampiricTouch() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        hurtAria(10);
        // Attack 15 + 6 hits; 3d6 (4, 4, 4) = 12, so she regains 6
        dice.script(15, 4, 4, 4);
        cast("Vampiric Touch", 3, List.of(ogre), null);
        assertThat(hp(ogre)).isEqualTo(48);
        assertThat(hp(ariaId)).isEqualTo(26);
    }

    @Test
    @DisplayName("Holy Weapon's burst deals 4d8 radiant and blinds on a failed save, then ends the spell; the blindness "
            + "outlasts it")
    void holyWeaponBurst() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        cast("Holy Weapon", 5, List.of(ariaId), null);
        assertThat(participant(dmState(), ariaId).get("spellModifiers").toString()).contains("Holy Weapon");

        nextTurn();
        nextTurn();
        // 4d8 (2, 2, 2, 2) = 8 radiant; the ogre's CON save of 2 + 3 fails
        dice.script(2, 2, 2, 2, 2);
        expect(HttpStatus.OK, use(Map.of("targetIds", List.of(ogre))));
        assertThat(hp(ogre)).isEqualTo(52);
        JsonNode blinded = condition(ogre, "blinded");
        assertThat(blinded).isNotNull();
        JsonNode instance = blinded.get("concentrationInstanceId");
        assertThat(instance == null || instance.isNull()).isTrue();
        JsonNode concentration = participant(dmState(), ariaId).path("concentration");
        assertThat(concentration.isNull() || concentration.isMissingNode()).isTrue();
        assertThat(participant(dmState(), ariaId).path("spellModifiers").toString()).doesNotContain("Holy Weapon");
    }

    @Test
    @DisplayName("Contagion poisons on a hit and counts the target's CON saves: three successes end the spell and the "
            + "poisoning")
    void contagionTally() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        // Attack 15 + 6 hits
        dice.script(15);
        cast("Contagion", 5, List.of(ogre), null);
        assertThat(condition(ogre, "poisoned")).isNotNull();

        for (int save = 1; save <= 3; save++) {
            nextTurn();
            // At the end of the ogre's turn: CON save of 18 + 3 succeeds
            dice.script(18);
            nextTurn();
        }
        assertThat(condition(ogre, "poisoned")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("(1/3 successes, 0/3 failures)"));
        assertThat(log()).anyMatch(line -> line.contains("Contagion ends for Ogre"));
        assertThat(participant(dmState(), ariaId).path("activeEffects").toString()).doesNotContain("Contagion");
    }

    @Test
    @DisplayName("Contagion's three failures end the poisoning and remind the DM of the disease")
    void contagionFailures() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        dice.script(15);
        cast("Contagion", 5, List.of(ogre), null);
        for (int save = 1; save <= 3; save++) {
            nextTurn();
            dice.script(2);
            nextTurn();
        }
        assertThat(condition(ogre, "poisoned")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("3 failures") && line.contains("disease"));
    }

    @Test
    @DisplayName("Telekinesis rolls Aria's spellcasting check against the ogre's Strength check; a win restrains it")
    void telekinesis() {
        String ogre = monster("Ogre", "giant", 60, builder -> builder.strength(10));
        start();
        // Aria 15 + her modifier against the ogre's 2 + 0
        dice.script(15, 2);
        cast("Telekinesis", 5, List.of(ogre), null);
        assertThat(condition(ogre, "restrained")).isNotNull();
        assertThat(log()).anyMatch(line -> line.contains("Contest") && line.contains("Aria wins"));
    }

    @Test
    @DisplayName("Banishing Smite's 5d10 force leaves a creature above 50 HP where it is")
    void banishingSmiteAboveThreshold() {
        String ogre = monster("Ogre", "giant", 70, UnaryOperator.identity());
        start();
        cast("Banishing Smite", 5, List.of(), null);
        // Attack 15 + 6 hits: 1d8 (4) + 5d10 (3, 3, 3, 3, 3) = 19, leaving 51
        dice.script(15, 4, 3, 3, 3, 3, 3);
        attack(ariaId, ogre, "slashing");
        assertThat(hp(ogre)).isEqualTo(51);
        assertThat(condition(ogre, "banished")).isNull();
    }

    @Test
    @DisplayName("Banishing Smite banishes a creature its hit leaves at 50 HP or fewer, until the spell ends")
    void banishingSmiteBanishes() {
        String ogre = monster("Ogre", "giant", 60, UnaryOperator.identity());
        start();
        cast("Banishing Smite", 5, List.of(), null);
        dice.script(15, 4, 3, 3, 3, 3, 3);
        attack(ariaId, ogre, "slashing");
        assertThat(hp(ogre)).isEqualTo(41);
        assertThat(condition(ogre, "banished")).isNotNull();
        dropConcentration();
        assertThat(condition(ogre, "banished")).isNull();
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
                "4", new SpellSlot(3, 0), "5", new SpellSlot(4, 0), "6", new SpellSlot(2, 0))));
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
