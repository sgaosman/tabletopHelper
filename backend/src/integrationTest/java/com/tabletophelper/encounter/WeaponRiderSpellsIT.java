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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zephyr Strike, Flame Arrows, Ray of Enfeeblement, Ashardalon's Stride and Heat Metal end to end (D197), with their
 * templates as the game data ships them. A DM and one player, whose cleric Aria (spell attack +6, save DC 14, WIS
 * spellcasting, 30 HP) acts first on 20; each test adds an ogre (AC 11, 60 HP, CON 16) acting after her.
 */
class WeaponRiderSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Zephyr Strike", "Flame Arrows", "Ray of Enfeeblement",
            "Ashardalon's Stride", "Heat Metal", "Shadow Blade", "Silvery Barbs", "Web");

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
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);
    }

    @Test
    @DisplayName("Zephyr Strike gives Aria's next weapon attack advantage and 1d8 force on its hit, once (XGE p.171)")
    void zephyrStrikeHit() {
        String ogre = ogre();
        start();
        cast("Zephyr Strike", 1, List.of());

        // advantage: 3 and 15 keep 15, + 6 hits AC 11; the 1d8 slashing rolls 4 and the force 1d8 5
        dice.script(3, 15, 4, 5);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 9);

        // spent: the next attack rolls one d20 and deals its own 1d8 only
        dice.script(15, 6);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 9 - 6);
    }

    @Test
    @DisplayName("Zephyr Strike's force damage is spent by a miss too (XGE p.171)")
    void zephyrStrikeMiss() {
        String ogre = ogre();
        start();
        cast("Zephyr Strike", 1, List.of());

        // advantage: 2 and 3 keep 3, + 6 misses AC 11
        dice.script(2, 3);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60);

        dice.script(15, 6);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 6);
    }

    @Test
    @DisplayName("Flame Arrows: each weapon attack from beyond 5 feet draws one of 12 pieces and a hit adds 1d6 fire; "
            + "a melee attack draws none (XGE p.156)")
    void flameArrows() {
        String ogre = ogre();
        start();
        cast("Flame Arrows", 3, List.of(ariaId));

        // 15 + 6 hits; the 1d8 piercing rolls 4 and the fire 1d6 5
        dice.script(15, 4, 5);
        enteredAttack(ogre, false);
        assertThat(hp(ogre)).isEqualTo(60 - 9);
        assertThat(log()).anyMatch(line -> line.contains("Flame Arrows") && line.contains("11 left"));

        // a miss from range draws a piece too
        dice.script(2);
        enteredAttack(ogre, false);
        assertThat(log()).anyMatch(line -> line.contains("Flame Arrows") && line.contains("10 left"));

        // a melee attack adds no fire
        dice.script(15, 4);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 9 - 4);
    }

    @Test
    @DisplayName("Ray of Enfeeblement: on a hit the ogre's melee attacks deal half damage, and it repeats the CON save "
            + "at the end of its turn (PHB p.271)")
    void rayOfEnfeeblement() {
        String ogre = ogre();
        start();
        // 15 + 6 hits AC 11
        dice.script(15);
        cast("Ray of Enfeeblement", 2, List.of(ogre));
        nextTurn();

        // 15 + 6 hits Aria; 2d8 of 6s = 12, halved to 6; her concentration check rolls 11
        dice.script(15, 6, 6, 11);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "2d8", "damageType",
                "bludgeoning"), dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(30 - 6);
        assertThat(log()).anyMatch(line -> line.contains("Ogre attacks Aria") && line.contains("6 bludgeoning damage"));

        // the end of the ogre's turn: 2 + 3 = 5 fails DC 14
        dice.script(2);
        nextTurn();
        assertThat(log()).anyMatch(line -> line.contains("stays under Ray of Enfeeblement"));
    }

    @Test
    @DisplayName("Ashardalon's Stride at 4th level adds 25 feet of speed (FTD p.19)")
    void ashardalonsStride() {
        ogre();
        start();
        cast("Ashardalon's Stride", 4, List.of());
        assertThat(log()).anyMatch(line -> line.contains("speed +25 ft.") && line.contains("1d6 fire damage"));
    }

    @Test
    @DisplayName("Heat Metal on worn armour burns the ogre and reminds the DM of the Constitution save a held object "
            + "makes (PHB p.250, D199)")
    void heatMetal() {
        String ogre = ogre();
        start();
        dice.script(4, 4);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + ariaId, Map.of("spellName", "Heat Metal", "slotLevel", 2, "targetIds", List.of(ogre),
                "option", "worn"), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(60 - 8);
        assertThat(log()).anyMatch(line -> line.contains("Constitution saving throw"));
        assertThat(log()).anyMatch(line -> line.contains("can't drop the armour it wears"));
    }

    @Test
    @DisplayName("Shadow Blade at 3rd level: Aria attacks with the blade, proficient, with her DEX of 14, for 3d8 + 2 "
            + "psychic (XGE p.164)")
    void shadowBlade() {
        String ogre = ogre();
        start();
        cast("Shadow Blade", 3, List.of());
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-options?participantId=" + ariaId, null, dm.accessToken()));
        JsonNode blade = options.get(0);
        assertThat(blade.get("weapon").asString()).isEqualTo("Shadow Blade");
        assertThat(blade.get("ability").asString()).isEqualTo("DEX");
        assertThat(blade.get("proficient").asBoolean()).isTrue();
        assertThat(blade.get("attackBonus").asInt()).isEqualTo(4);

        // 15 + 4 hits AC 11; 3d8 of 3s + 2 = 11 psychic
        dice.script(15, 3, 3, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", ogre, "weapon", "Shadow Blade"), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(60 - 11);
        assertThat(log()).anyMatch(line -> line.contains("psychic"));
    }

    @Test
    @DisplayName("Silvery Barbs gives the creature it is cast at advantage on its next attack roll, once, and reminds "
            + "the DM of the reroll (SCC p.38)")
    void silveryBarbs() {
        String ogre = ogre();
        start();
        cast("Silvery Barbs", 1, List.of(ariaId));
        assertThat(log()).anyMatch(line -> line.contains("uses the lower roll"));

        // advantage: 3 and 15 keep 15, + 6 hits; the 1d8 rolls 4
        dice.script(3, 15, 4);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 4);

        // spent: one d20 of 3 misses
        dice.script(3);
        enteredAttack(ogre, true);
        assertThat(hp(ogre)).isEqualTo(60 - 4);
    }

    @Test
    @DisplayName("Web: the ogre fails its DEX save and is restrained, the cast reminding the DM of breaking free and "
            + "fire (PHB p.287)")
    void web() {
        String ogre = ogre();
        start();
        // 2 - 1 = 1 fails DC 14
        dice.script(2);
        cast("Web", 2, List.of(ogre));
        assertThat(conditionNames(ogre)).contains("restrained");
        assertThat(log()).anyMatch(line -> line.contains("Strength check against your spell save DC"));
    }

    @Test
    @DisplayName("Web: an ogre that saves at the cast is restrained when it fails the save starting its turn in the webs "
            + "(PHB p.287, D199)")
    void webTrigger() {
        String ogre = ogre();
        start();
        // 20 - 1 = 19 succeeds against DC 14
        dice.script(20);
        cast("Web", 2, List.of(ogre));
        assertThat(conditionNames(ogre)).doesNotContain("restrained");

        // 2 - 1 = 1 fails
        dice.script(2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/repeat-spell-effect?actorId=" + ariaId,
                Map.of("targetIds", List.of(ogre), "trigger", "START_OF_TURN"), dm.accessToken()));
        assertThat(conditionNames(ogre)).contains("restrained");
    }

    private List<String> conditionNames(String participantId) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant(dmState(), participantId).get("activeConditions");
        if (conditions != null && !conditions.isNull()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
    }

    private void enteredAttack(String targetId, boolean withinFiveFeet) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", targetId, "attackBonus", 6, "damageDice", "1d8", "damageType",
                withinFiveFeet ? "slashing" : "piercing", "within5Ft", withinFiveFeet), dm.accessToken()));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String ogre() {
        return monster("Ogre", "humanoid", 60, null);
    }

    private String monster(String name, String type, int hp, MonsterTemplates actions) {
        Monster.MonsterBuilder builder = Monster.builder().name(name).type(type).challengeRating("5").source("MM")
                .hitPoints(hp).armourClass(11).dexterity(8).constitution(16).wisdom(7).charisma(7);
        if (actions != null) {
            builder.actionTemplates(actions);
        }
        Monster saved = monsterRepository.save(builder.build());
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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "2", new SpellSlot(3, 0), "3", new SpellSlot(3, 0),
                "4", new SpellSlot(3, 0), "5", new SpellSlot(3, 0))));
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
                    String castingTime = "REACTION".equals(definition.path("castingTime").asString(null))
                            ? "1 reaction" : "1 action";
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

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", spell, "slotLevel", slotLevel, "targetIds", targetIds), dm.accessToken());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds) {
        expect(HttpStatus.OK, castRequest(spell, slotLevel, targetIds));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
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
