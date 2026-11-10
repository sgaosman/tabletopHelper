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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The low-level spells whose buffs and debuffs were only logged, end to end (D199), with their templates as the game
 * data ships them. A DM and one player, whose cleric Aria (spell attack +6, save DC 14, WIS spellcasting, 30 HP)
 * acts first on 20; each test adds an ogre (AC 11, 60 HP, CON 16, walking speed 40) acting after her.
 */
class LogOnlySpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Shocking Grasp", "Heat Metal", "Enhance Ability",
            "Darkvision", "Fly", "Expeditious Retreat", "Tasha's Mind Whip", "Detect Magic");

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
    @DisplayName("Shocking Grasp has advantage against the ogre in chain mail, and its hit leaves it unable to take "
            + "reactions until the start of its next turn (PHB p.275)")
    void shockingGrasp() {
        String ogre = ogre("chain mail");
        start();
        // advantage: 3 and 15 keep 15, + 6 hits AC 11; 1d8 lightning rolls 5
        dice.script(3, 15, 5);
        cast("Shocking Grasp", 0, List.of(ogre), null);

        assertThat(hp(ogre)).isEqualTo(55);
        assertThat(log()).anyMatch(line -> line.contains("target wears metal armour"));
        assertThat(chips(ogre)).contains("can't take reactions (until the start of its next turn)");

        nextTurn();
        assertThat(chips(ogre)).isEmpty();
    }

    @Test
    @DisplayName("Heat Metal on a held weapon: the ogre keeps hold on a successful CON save and has disadvantage until "
            + "Aria's next turn; the bonus action again burns it for 2d8 and it drops the weapon on a failure (PHB "
            + "p.250)")
    void heatMetal() {
        String ogre = ogre(null);
        start();
        // 2d8 fire: 4 + 4; the ogre's CON save: 12 + 3 = 15 succeeds against DC 14
        dice.script(4, 4, 12);
        cast("Heat Metal", 2, List.of(ogre), "held");
        assertThat(hp(ogre)).isEqualTo(52);
        assertThat(chips(ogre)).contains("disadvantage on attack rolls (until the start of the caster's next turn)",
                "disadvantage on ability checks (until the start of the caster's next turn)");

        nextTurn();
        nextTurn();
        assertThat(chips(ogre)).isEmpty();
        String effectId = participant(dmState(), ariaId).get("activeEffects").get(0).get("id").asString();
        // 2d8 of 3s, and the save: 2 + 3 = 5 fails
        dice.script(3, 3, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/repeat-spell-effect?actorId=" + ariaId,
                Map.of("effectId", effectId, "targetIds", List.of(ogre)), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(46);
        assertThat(chips(ogre)).isEmpty();
        assertThat(log()).anyMatch(line -> line.contains("Ogre drops the object"));
    }

    @Test
    @DisplayName("Heat Metal needs an option, held or worn")
    void heatMetalOption() {
        String ogre = ogre(null);
        start();
        expect(HttpStatus.BAD_REQUEST, castRequest("Heat Metal", 2, List.of(ogre), null));
    }

    @Test
    @DisplayName("Enhance Ability's Bear's Endurance gives Aria 2d6 temporary hit points and advantage on CON checks "
            + "(PHB p.237)")
    void bearsEndurance() {
        ogre(null);
        start();
        dice.script(2, 5);
        cast("Enhance Ability", 2, List.of(ariaId), "Bear's Endurance");
        assertThat(participant(dmState(), ariaId).get("hpTemp").asInt()).isEqualTo(7);
        assertThat(chips(ariaId)).contains("advantage on Constitution checks");
    }

    @Test
    @DisplayName("Darkvision leaves its Spell Effect on Aria for 8 hours, showing what it gives (PHB p.230)")
    void darkvision() {
        ogre(null);
        start();
        cast("Darkvision", 2, List.of(ariaId), null);
        JsonNode effect = participant(dmState(), ariaId).get("activeEffects").get(0);
        assertThat(effect.get("spell").asString()).isEqualTo("Darkvision");
        assertThat(effect.get("durationRounds").asInt()).isEqualTo(4800);
        assertThat(effect.get("affectedIds").get(0).asString()).isEqualTo(ariaId);
        assertThat(chips(ariaId)).contains("darkvision out to 60 feet");
    }

    @Test
    @DisplayName("Detect Magic leaves a concentration Spell Effect on its caster for 10 minutes (PHB p.231)")
    void detectMagic() {
        ogre(null);
        start();
        cast("Detect Magic", 1, List.of(), null);
        JsonNode effect = participant(dmState(), ariaId).get("activeEffects").get(0);
        assertThat(effect.get("concentration").asBoolean()).isTrue();
        assertThat(effect.get("durationRounds").asInt()).isEqualTo(100);
        assertThat(chips(ariaId)).anyMatch(chip -> chip.startsWith("senses magic within 30 feet"));
    }

    @Test
    @DisplayName("Fly gives the ogre a flying speed of 60 feet (PHB p.243)")
    void fly() {
        String ogre = ogre(null);
        start();
        cast("Fly", 3, List.of(ogre), null);
        JsonNode speed = participant(dmState(), ogre).get("speed");
        assertThat(speed.get("fly").asInt()).isEqualTo(60);
        assertThat(speed.get("walk").asInt()).isEqualTo(40);
    }

    @Test
    @DisplayName("Expeditious Retreat Dashes as it is cast, and on Aria's next turn she Dashes as a bonus action "
            + "(PHB p.238)")
    void expeditiousRetreat() {
        ogre(null);
        start();
        cast("Expeditious Retreat", 1, List.of(), null);
        assertThat(participant(dmState(), ariaId).get("turnEconomy").get("dashed").asBoolean()).isTrue();

        nextTurn();
        nextTurn();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/standard-action",
                Map.of("participantId", ariaId, "action", "DASH", "cost", "BONUS_ACTION"), player.accessToken()));
        assertThat(log()).anyMatch(line -> line.contains("(bonus action, Expeditious Retreat)"));
    }

    @Test
    @DisplayName("Tasha's Mind Whip keeps the ogre from reactions until the end of its next turn (TCE p.115)")
    void mindWhip() {
        String ogre = ogre(null);
        start();
        // INT save: 2 - 1 fails; 3d6 psychic of 2s
        dice.script(2, 2, 2, 2);
        cast("Tasha's Mind Whip", 2, List.of(ogre), null);
        assertThat(chips(ogre)).contains("can't take reactions (until the end of its next turn)");
        nextTurn();
        assertThat(chips(ogre)).contains("can't take reactions (until the end of its next turn)");
        nextTurn();
        assertThat(chips(ogre)).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private List<String> chips(String participantId) {
        List<String> texts = new ArrayList<>();
        JsonNode chips = participant(dmState(), participantId).get("spellModifiers");
        if (chips != null && chips.isArray()) {
            chips.forEach(chip -> chip.get("modifiers").forEach(text -> texts.add(text.asString())));
        }
        return texts;
    }

    private String ogre(String acType) {
        Monster.MonsterBuilder builder = Monster.builder().name("Ogre").type("giant").challengeRating("2")
                .source("MM").hitPoints(60).armourClass(11).dexterity(8).constitution(16).intelligence(5)
                .wisdom(7).charisma(7).speed("{\"walk\": 40}");
        if (acType != null) {
            builder.acType(acType);
        }
        Monster saved = monsterRepository.save(builder.build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", "Ogre")), "Ogre");
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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0))));
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
                    String castingTime = switch (definition.path("castingTime").asString("")) {
                        case "REACTION" -> "1 reaction";
                        case "BONUS_ACTION" -> "1 bonus action";
                        default -> "1 action";
                    };
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
