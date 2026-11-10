package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
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
 * Counterspell, Dispel Magic, Blink and Mirror Image end to end (D196), with their templates as the game data ships
 * them. A DM and one player, whose cleric Aria (spell attack +6, save DC 14, WIS spellcasting, 30 HP) acts first on
 * 20; each test adds the creatures it needs, acting after her.
 */
class LowLevelSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Counterspell", "Dispel Magic", "Blink", "Mirror Image",
            "Fireball", "Cone of Cold", "Hold Person", "Shillelagh", "Spirit Shroud", "Sanctuary");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private ItemRepository itemRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
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
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Cleric", 8));
        campaignId = UUID.fromString(campaign.get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);
    }

    @Test
    @DisplayName("Counterspell at 3rd level stops a 3rd-level Fireball outright: its damage is taken back, Aria's slot "
            + "is spent, and undoing the Counterspell lets the Fireball stand (PHB p.228)")
    void counterspellAutomatic() {
        String mage = mage();
        start();
        nextTurn();
        // 8d6 of 3s = 24 fire, and Aria's DEX save of 1 fails
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 1);
        castBy(mage, "Fireball", List.of(ariaId));
        assertThat(hp(ariaId)).isEqualTo(6);

        cast("Counterspell", 3, List.of(mage));
        assertThat(hp(ariaId)).isEqualTo(30);
        assertThat(slotsUsed("3")).isEqualTo(1);
        assertThat(log()).anyMatch(line -> line.equals(
                "Aria counters Mage's Fireball (level 3) - the spell fails and has no effect."));

        undoNewest();
        assertThat(hp(ariaId)).isEqualTo(6);
        assertThat(slotsUsed("3")).isZero();
    }

    @Test
    @DisplayName("Counterspell at 3rd level against a 5th-level Cone of Cold needs a WIS check against DC 15: a 1 "
            + "lets the spell stand (PHB p.228)")
    void counterspellCheckFails() {
        String mage = mage();
        start();
        nextTurn();
        // 8d8 of 2s = 16 cold, and Aria's CON save of 1 fails
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 1);
        castBy(mage, "Cone of Cold", List.of(ariaId));
        assertThat(hp(ariaId)).isEqualTo(14);

        dice.script(1);
        cast("Counterspell", 3, List.of(mage));
        assertThat(hp(ariaId)).isEqualTo(14);
        assertThat(slotsUsed("3")).isEqualTo(1);
        assertThat(log()).anyMatch(line -> line.startsWith("Aria counters Mage's Cone of Cold (level 5): "
                + "spellcasting ability check d20(1)") && line.endsWith("vs DC 15 - the spell goes ahead."));
    }

    @Test
    @DisplayName("Counterspell's check of 20 against DC 15 stops the Cone of Cold (PHB p.228)")
    void counterspellCheckSucceeds() {
        String mage = mage();
        start();
        nextTurn();
        dice.script(2, 2, 2, 2, 2, 2, 2, 2, 1);
        castBy(mage, "Cone of Cold", List.of(ariaId));

        dice.script(20);
        cast("Counterspell", 3, List.of(mage));
        assertThat(hp(ariaId)).isEqualTo(30);
    }

    @Test
    @DisplayName("Counterspell needs the target's spell to be the newest action")
    void counterspellNeedsACast() {
        String mage = mage();
        start();
        expect(HttpStatus.CONFLICT, castRequest("Counterspell", 3, List.of(mage)));
    }

    @Test
    @DisplayName("Dispel Magic at 3rd level ends a 2nd-level Hold Person outright, and Aria's concentration with it "
            + "(PHB p.234)")
    void dispelMagic() {
        String ogre = ogre();
        start();
        // The ogre's WIS save of 1 fails
        dice.script(1);
        cast("Hold Person", 2, List.of(ogre));
        assertThat(condition(ogre, "paralyzed")).isNotNull();
        nextTurn();
        // Its repeat save at the end of its turn fails again
        dice.script(1);
        nextTurn();

        cast("Dispel Magic", 3, List.of(ogre));
        assertThat(condition(ogre, "paralyzed")).isNull();
        JsonNode concentration = participant(dmState(), ariaId).get("concentration");
        assertThat(concentration == null || concentration.isNull()).isTrue();
        assertThat(log()).anyMatch(line -> line.equals("Hold Person (level 2) on Ogre ends."));
    }

    @Test
    @DisplayName("Dispel Magic at 3rd level against Hold Person cast at 5th needs a check against DC 15; a 1 holds")
    void dispelMagicCheck() {
        String ogre = ogre();
        start();
        dice.script(1);
        cast("Hold Person", 5, List.of(ogre));
        nextTurn();
        dice.script(1);
        nextTurn();

        dice.script(1);
        cast("Dispel Magic", 3, List.of(ogre));
        assertThat(condition(ogre, "paralyzed")).isNotNull();
        assertThat(log()).anyMatch(line -> line.startsWith("Hold Person (level 5) on Ogre holds (check d20(1)"));
    }

    @Test
    @DisplayName("Blink: an 11 or higher at the end of Aria's turn makes her ethereal, so the ogre can't attack her, "
            + "until the start of her next turn (PHB p.219)")
    void blink() {
        String ogre = ogre();
        start();
        cast("Blink", 3, List.of());
        assertThat(condition(ariaId, "blinking")).isNotNull();

        dice.script(15);
        nextTurn();
        assertThat(condition(ariaId, "ethereal")).isNotNull();
        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/attack?actorId=" + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8",
                "damageType", "slashing"), dm.accessToken()));

        nextTurn();
        assertThat(condition(ariaId, "ethereal")).isNull();
        assertThat(condition(ariaId, "blinking")).isNotNull();
    }

    @Test
    @DisplayName("Mirror Image: an attack on Aria whose d20 of 10 beats 6 targets a duplicate, which its 21 destroys; "
            + "Aria takes nothing and has two left (PHB p.260)")
    void mirrorImage() {
        String ogre = ogre();
        start();
        cast("Mirror Image", 2, List.of());
        assertThat(condition(ariaId, "mirror image").get("bonus").asInt()).isEqualTo(3);
        nextTurn();

        // The attack's 15 + 6 hits, its 1d8 rolls 4, and Mirror Image's d20 is 10
        dice.script(15, 4, 10);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8", "damageType", "slashing"),
                dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(30);
        assertThat(condition(ariaId, "mirror image").get("bonus").asInt()).isEqualTo(2);
        assertThat(log()).anyMatch(line -> line.startsWith("Mirror Image: d20(10) against 6+ with 3 duplicates - the "
                + "attack targets a duplicate"));
    }

    @Test
    @DisplayName("Sanctuary: the ogre's WIS save of 5 - 2 fails against Aria's DC 14, so its hit does nothing to her; "
            + "a later save of 20 lets the next attack through (PHB p.272)")
    void sanctuary() {
        String ogre = ogre();
        start();
        cast("Sanctuary", 1, List.of(ariaId));
        assertThat(condition(ariaId, "sanctuary").get("bonus").asInt()).isEqualTo(14);
        nextTurn();

        // The attack's 15 + 6 hits and its 1d8 rolls 4; the ogre's WIS save rolls 5
        dice.script(15, 4, 5);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8", "damageType", "slashing"),
                dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(30);
        assertThat(log()).anyMatch(line -> line.startsWith("Sanctuary: Ogre's WIS save d20(5) - 2 = 3 vs DC 14 - it "
                + "fails"));

        // The DM's attack for the ogre's next attack, its save of 20 succeeding
        dice.script(15, 4, 20);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "1d8", "damageType", "slashing"),
                dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(26);
    }

    @Test
    @DisplayName("Spirit Shroud adds 1d8 of the chosen type to Aria's hits (TCE p.108)")
    void spiritShroud() {
        String ogre = ogre();
        start();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", "Spirit Shroud", "slotLevel", 3,
                "targetIds", List.of(), "option", "necrotic"), dm.accessToken()));

        // 15 + 6 hits AC 11; the 1d8 slashing rolls 4 and the shroud's 1d8 necrotic 5
        dice.script(15, 4, 5);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", ogre, "attackBonus", 6, "damageDice", "1d8", "damageType", "slashing"),
                dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(60 - 9);
        assertThat(log()).anyMatch(line -> line.contains("Spirit Shroud"));
    }

    @Test
    @DisplayName("Shillelagh: Oak's quarterstaff deals a d8 and uses her WIS of 16 instead of her STR of 10 for its "
            + "attack and damage (PHB p.275)")
    void shillelagh() {
        itemRepository.save(Item.builder().name("Quarterstaff").type("Melee Weapon").subtype("simple")
                .damageDice("1d6").damageType("bludgeoning")
                .properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d8\"}").source("PHB").build());
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Oak", "raceId", insertRace("Elf"),
                "classId", insertClass("Druid", 8), "level", 1, "strength", 10, "dexterity", 10,
                "constitution", 12, "intelligence", 8, "wisdom", 16, "charisma", 10));
        body.put("campaignId", campaignId);
        body.put("weaponProficiencies", List.of("simple"));
        String character = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken())).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Quarterstaff", "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken()));
        String oak = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "PLAYER",
                "characterId", UUID.fromString(character), "displayName", "Oak")), "Oak");
        initiatives.clear();
        initiatives.put(oak, 20);
        String ogre = ogre();
        initiatives.put(ariaId, 1);
        start();
        EncounterParticipant druid = participantRepository.findById(UUID.fromString(oak)).orElseThrow();
        druid.setSpellcastingAbility("WIS");
        druid.setSpellsKnown(new ArrayList<>(List.of(new KnownSpell(null, "Shillelagh", 0, "class:Druid", true,
                null, null, null, null))));
        participantRepository.save(druid);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + oak, Map.of("spellName", "Shillelagh", "slotLevel", 0,
                "targetIds", List.of()), dm.accessToken()));
        assertThat(condition(oak, "shillelagh")).isNotNull();

        // 10 + 2 proficiency + 3 WIS = 15 hits AC 11; the d8 rolls 5, + 3 WIS
        dice.script(10, 5);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + oak, Map.of("targetId", ogre, "weapon", "Quarterstaff"), dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(60 - 8);
        assertThat(log()).anyMatch(line -> line.contains("Shillelagh d8, WIS"));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String mage() {
        return monster("Mage", "humanoid", 40, MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 14,
                 "atWillSpells": ["fireball", "cone of cold"]}}"""));
    }

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

    // A monster's innate spell, cast by the DM
    private void castBy(String monsterId, String spell, List<String> targetIds) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + monsterId, Map.of("spellName", spell, "slotLevel", 0, "targetIds", targetIds), dm.accessToken()));
    }

    private void undoNewest() {
        String newest = dmState().get("undo").get("commandId").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
    }

    private int slotsUsed(String level) {
        return participant(dmState(), ariaId).get("spellSlotsCurrent").get(level).get("used").asInt();
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
