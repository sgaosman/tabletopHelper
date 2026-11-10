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
 * Monster traits that change how spells work, end to end (D191): Magic Resistance on a spell's save, named in the log,
 * Limited Magic Immunity keeping a 5th-level spell out, and a stat block's condition immunity against a spell's
 * condition. A table of a DM and one player, whose cleric Aria (save DC 14) acts first on 20.
 */
class SpellTraitsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Flame Strike", "Hold Monster");
    private static final String MAGIC_RESISTANCE = "[{\"name\": \"Magic Resistance\", \"description\": \"The golem has "
            + "advantage on saving throws against spells and other magical effects.\"}]";
    private static final String LIMITED_MAGIC_IMMUNITY = "[{\"name\": \"Limited Magic Immunity\", \"description\": "
            + "\"The rakshasa can't be affected or detected by spells of 6th level or lower unless it wishes to be. It "
            + "has advantage on saving throws against all other spells and magical effects.\"}]";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String ariaId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        RegisteredUser player = register("aria");
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
    @DisplayName("Magic Resistance: the golem saves against Flame Strike with advantage, named in the log, and takes "
            + "half; the ogre beside it saves once and takes it all")
    void magicResistance() {
        String golem = monster("Golem", 60, builder -> builder.traits(MAGIC_RESISTANCE));
        String ogre = monster("Ogre", 60, UnaryOperator.identity());
        start();
        // 8d6 of 3s = 24 (12 fire, 12 radiant); the golem's DEX saves 2 and 18 keep 18 - 1 = 17, which succeeds; the
        // ogre's 2 - 1 fails
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 2, 18, 2);
        cast("Flame Strike", 5, List.of(golem, ogre));
        assertThat(hp(golem)).isEqualTo(48);
        assertThat(hp(ogre)).isEqualTo(36);
        assertThat(log()).anyMatch(line -> line.contains("Golem: DEX save (advantage: Magic Resistance) 2d20(2, 18)"));
        assertThat(log()).anyMatch(line -> line.contains("Ogre: DEX save d20(2)"));
    }

    @Test
    @DisplayName("Limited Magic Immunity: a 5th-level Flame Strike has no effect on the rakshasa")
    void limitedMagicImmunity() {
        String rakshasa = monster("Rakshasa", 110, builder -> builder.traits(LIMITED_MAGIC_IMMUNITY));
        start();
        dice.script(3, 3, 3, 3, 3, 3, 3, 3);
        cast("Flame Strike", 5, List.of(rakshasa));
        assertThat(hp(rakshasa)).isEqualTo(110);
        assertThat(log()).anyMatch(line -> line.contains("Flame Strike has no effect on Rakshasa (Limited Magic "
                + "Immunity)"));
    }

    @Test
    @DisplayName("A stat block's immunity to being paralyzed stops Hold Monster's paralysis on a failed save")
    void conditionImmunity() {
        String golem = monster("Iron Golem", 210, builder -> builder.conditionImmunities("[\"paralyzed\"]"));
        start();
        dice.script(2);
        cast("Hold Monster", 5, List.of(golem));
        JsonNode conditions = participant(dmState(), golem).get("activeConditions");
        assertThat(conditions == null || conditions.isNull() || conditions.isEmpty()).isTrue();
        assertThat(log()).anyMatch(line -> line.contains("immune to paralyzed"));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String monster(String name, int hp, UnaryOperator<Monster.MonsterBuilder> more) {
        Monster saved = monsterRepository.save(more.apply(Monster.builder().name(name).type("construct")
                .challengeRating("5").source("MM").hitPoints(hp).armourClass(11).dexterity(8).constitution(16)
                .wisdom(7).charisma(7)).build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
        initiatives.put(id, 19 - initiatives.size());
        return id;
    }

    // Starts the fight, then gives Aria her spells
    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("5", new SpellSlot(3, 0))));
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

    private void cast(String spell, int slotLevel, List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds), dm.accessToken()));
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
