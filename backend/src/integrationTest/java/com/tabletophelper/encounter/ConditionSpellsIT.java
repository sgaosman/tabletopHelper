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
 * The summoning, condition and hit-point pool spells of D199 end to end, with their templates as the game data ships
 * them. A DM and one player, whose cleric Aria (spell attack +6, save DC 14, WIS spellcasting, 30 HP) acts first on 20;
 * each test adds the monsters it needs, acting after her.
 */
class ConditionSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Summon Beast", "Flock of Familiars", "Sleep",
            "Color Spray", "Command", "Bestow Curse", "Armor of Agathys", "Dragon's Breath", "Gaseous Form",
            "Revivify", "Goodberry", "Fog Cloud", "Leomund's Tiny Hut", "Alter Self");

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
    @DisplayName("Summon Beast, no longer marked for the DM, resolves through its summon step: the spirit joins and the "
            + "cast needs nothing from the DM (TCE p.109)")
    void summonBeast() {
        monsterRepository.save(Monster.builder().name("Bestial Spirit").type("beast").source("TCE").hitPoints(30)
                .armourClass(11).strength(18).dexterity(11).constitution(16).build());
        start();
        JsonNode cast = expect(HttpStatus.OK, castRequest("Summon Beast", 2, List.of(),
                Map.of("summon", Map.of("variant", "Land")), player));
        assertThat(cast.get("autoResolved").asBoolean()).isTrue();
        assertThat(cast.get("resultSummary").asString()).contains("summons Bestial Spirit (Land)");
        assertThat(companions(dmState())).hasSize(1);
        assertThat(log()).anyMatch(line -> line.contains("Pack Tactics"));
    }

    @Test
    @DisplayName("Flock of Familiars' familiars join as the DM's companions, each one Find Familiar's (LLK p.57)")
    void flockOfFamiliars() {
        Monster owl = monsterRepository.save(Monster.builder().name("Owl").type("beast").source("MM").hitPoints(1)
                .armourClass(11).dexterity(13).build());
        JsonNode state = addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "displayName", "Owl", "monsterId", owl.getId(), "summonerParticipantId", ariaId,
                "spellName", "Flock of Familiars", "creatureType", "fey", "quantity", 3));
        List<JsonNode> familiars = companions(state);
        assertThat(familiars).hasSize(3).allSatisfy(familiar -> {
            assertThat(familiar.get("summon").get("spell").asString()).isEqualTo("Flock of Familiars");
            assertThat(familiar.get("summon").get("cannotAttack").asBoolean()).isTrue();
        });
        // Concentration, up to 1 hour (LLK p.57)
        assertThat(participant(state, ariaId).get("activeEffects").get(0).get("durationRounds").asInt())
                .isEqualTo(600);
    }

    @Test
    @DisplayName("Sleep puts the goblin and the boss to sleep lowest first, the ogre beyond the pool; the goblin wakes "
            + "when it takes damage (PHB p.276)")
    void sleep() {
        String goblin = monster("Goblin", "humanoid", 7);
        String boss = monster("Goblin Boss", "humanoid", 21);
        String ogre = monster("Ogre", "giant", 59);
        start();
        // 5d8: 8, 8, 8, 2, 2 = 28: the goblin (7), then the boss (21), 0 left
        dice.script(8, 8, 8, 2, 2);
        cast("Sleep", 1, List.of(goblin, boss, ogre));
        assertThat(conditionNames(goblin)).contains("unconscious");
        assertThat(conditionNames(boss)).contains("unconscious");
        assertThat(conditionNames(ogre)).doesNotContain("unconscious");
        assertThat(log()).anyMatch(line -> line.contains("= 28 hit points") && line.contains("Ogre (59 HP) is beyond"));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", goblin, "amount", 1, "damageType", "fire"), dm.accessToken()));
        assertThat(conditionNames(goblin)).doesNotContain("unconscious");
    }

    @Test
    @DisplayName("Command: Grovel knocks the ogre prone on a failed WIS save (PHB p.223)")
    void command() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        // 2 - 2 = 0 fails DC 14
        dice.script(2);
        expect(HttpStatus.OK, castRequest("Command", 1, List.of(ogre), Map.of("option", "grovel"), dm));
        assertThat(conditionNames(ogre)).contains("prone");
        assertThat(log()).anyMatch(line -> line.contains("Halt doesn't move"));
    }

    @Test
    @DisplayName("Bestow Curse at 5th level needs no concentration and lasts 8 hours (PHB p.218)")
    void bestowCurse() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        dice.script(2);
        expect(HttpStatus.OK, castRequest("Bestow Curse", 5, List.of(ogre), Map.of("option", "constitution"), dm));
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("concentrationSpell").isNull()).isTrue();
        JsonNode curse = aria.get("activeEffects").get(0);
        assertThat(curse.get("spell").asString()).isEqualTo("Bestow Curse");
        assertThat(curse.get("durationRounds").asInt()).isEqualTo(4800);
        assertThat(curse.get("option").asString()).isEqualTo("constitution");
    }

    @Test
    @DisplayName("Armor of Agathys at 2nd: 10 temporary hit points, and the ogre's melee hit takes 10 cold back "
            + "(PHB p.215)")
    void armorOfAgathys() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        cast("Armor of Agathys", 2, List.of());
        assertThat(participant(dmState(), ariaId).get("hpTemp").asInt()).isEqualTo(10);
        nextTurn();
        // 15 + 6 hits; 2d8 of 2s = 4, which the temporary hit points take
        dice.script(15, 2, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ogre, Map.of("targetId", ariaId, "attackBonus", 6, "damageDice", "2d8", "damageType",
                "bludgeoning", "within5Ft", true), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("hpTemp").asInt()).isEqualTo(6);
        assertThat(hp(ogre)).isEqualTo(59 - 10);
    }

    @Test
    @DisplayName("Dragon's Breath on herself: Aria exhales 3d6 fire with her action, the ogre saving for half "
            + "(XGE p.154)")
    void dragonsBreath() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        expect(HttpStatus.OK, castRequest("Dragon's Breath", 2, List.of(ariaId), Map.of("option", "fire"), dm));
        // 3d6 of 4s = 12 fire; the ogre's DEX save 2 - 1 fails
        dice.script(4, 4, 4, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/repeat-spell-effect?actorId=" + ariaId, Map.of("targetIds", List.of(ogre)),
                dm.accessToken()));
        assertThat(hp(ogre)).isEqualTo(59 - 12);
        assertThat(log()).anyMatch(line -> line.contains("fire"));
    }

    @Test
    @DisplayName("Gaseous Form on Aria: she can't cast spells while it lasts (PHB p.242)")
    void gaseousForm() {
        monster("Ogre", "giant", 59);
        start();
        cast("Gaseous Form", 3, List.of(ariaId));
        expect(HttpStatus.CONFLICT, castRequest("Sleep", 1, List.of(), Map.of(), dm));
    }

    @Test
    @DisplayName("Revivify refuses a living ogre and returns a dead one with 1 hit point (PHB p.272)")
    void revivify() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        expect(HttpStatus.CONFLICT, castRequest("Revivify", 3, List.of(ogre), Map.of(), dm));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", ogre, "amount", 59, "damageType", "fire"), dm.accessToken()));
        assertThat(participant(dmState(), ogre).get("lifeState").asString()).isEqualTo("DEAD");
        cast("Revivify", 3, List.of(ogre));
        JsonNode revived = participant(dmState(), ogre);
        assertThat(revived.get("lifeState").asString()).isEqualTo("ALIVE");
        assertThat(revived.get("hpCurrent").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Goodberry gives Aria ten berries; eating one with her action restores 1 hit point (PHB p.246)")
    void goodberry() {
        monster("Ogre", "giant", 59);
        start();
        cast("Goodberry", 1, List.of());
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpCurrent(20);
        participantRepository.save(aria);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-pool",
                Map.of("participantId", ariaId, "poolId", "spell:goodberry"), dm.accessToken()));
        assertThat(hp(ariaId)).isEqualTo(21);
    }

    @Test
    @DisplayName("Fog Cloud blinds the ogre while Aria concentrates, and leaves its hour-long Spell Effect "
            + "(PHB p.243)")
    void fogCloud() {
        String ogre = monster("Ogre", "giant", 59);
        start();
        cast("Fog Cloud", 1, List.of(ogre));
        assertThat(conditionNames(ogre)).contains("blinded");
        JsonNode aria = participant(dmState(), ariaId);
        assertThat(aria.get("concentrationSpell").asString()).isEqualTo("Fog Cloud");
        assertThat(aria.get("activeEffects").get(0).get("durationRounds").asInt()).isEqualTo(600);
    }

    @Test
    @DisplayName("Leomund's Tiny Hut stays the DM's, but leaves its 8-hour Spell Effect and reminds the DM what it "
            + "does (PHB p.255)")
    void tinyHut() {
        monster("Ogre", "giant", 59);
        start();
        JsonNode cast = expect(HttpStatus.OK, castRequest("Leomund's Tiny Hut", 3, List.of(), Map.of(), dm));
        assertThat(cast.get("autoResolved").asBoolean()).isFalse();
        JsonNode effect = participant(dmState(), ariaId).get("activeEffects").get(0);
        assertThat(effect.get("spell").asString()).isEqualTo("Leomund's Tiny Hut");
        assertThat(effect.get("durationRounds").asInt()).isEqualTo(4800);
        assertThat(log()).anyMatch(line -> line.contains("10-foot dome"));
    }

    @Test
    @DisplayName("Alter Self's Natural Weapons head Aria's attack options: proficient, magic, 1d6 + 1 slashing "
            + "(PHB p.211)")
    void alterSelf() {
        monster("Ogre", "giant", 59);
        start();
        expect(HttpStatus.OK, castRequest("Alter Self", 2, List.of(), Map.of("option", "natural weapons (slashing)"),
                dm));
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/attack-options?participantId=" + ariaId, null, dm.accessToken()));
        JsonNode natural = options.get(0);
        assertThat(natural.get("weapon").asString()).isEqualTo("Natural Weapon");
        assertThat(natural.get("proficient").asBoolean()).isTrue();
        assertThat(natural.get("damageType").asString()).isEqualTo("slashing");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private List<String> conditionNames(String participantId) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant(dmState(), participantId).get("activeConditions");
        if (conditions != null && !conditions.isNull()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
    }

    private String monster(String name, String type, int hp) {
        Monster saved = monsterRepository.save(Monster.builder().name(name).type(type).challengeRating("1")
                .source("MM").hitPoints(hp).armourClass(11).dexterity(8).constitution(16).wisdom(7).charisma(7)
                .build());
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
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0), "4", new SpellSlot(3, 0), "5", new SpellSlot(3, 0))));
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

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds,
                                               Map<String, Object> extra, RegisteredUser as) {
        Map<String, Object> body = new HashMap<>(Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds));
        body.putAll(extra);
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                body, as.accessToken());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds) {
        expect(HttpStatus.OK, castRequest(spell, slotLevel, targetIds, Map.of(), dm));
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

    private static List<JsonNode> companions(JsonNode encounter) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode participant : encounter.get("participants")) {
            if ("COMPANION".equals(participant.get("participantType").asString())) {
                found.add(participant);
            }
        }
        return found;
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
