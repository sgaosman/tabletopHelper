package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.Feature;
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
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summoning, end to end (D182). A table of a DM and one player, whose druid Aria acts first on 18 against a goblin on
 * 10: her familiar joins while the encounter is prepared, Conjure Animals at 5th brings sixteen wolves that undo takes
 * back, Summon Lesser Demons waits for the DM's demons, which players see as monsters, and removing a summoned
 * creature dismisses it. The 4th-level summonings (D185): Summon Elemental at 4th and 5th with each element's damage
 * and defences, and Summon Greater Demon's challenge rating cap and its demon breaking free. The 5th-level ones
 * (D187): Summon Celestial's Defender, Animate Objects' budget counted by size, Conjure Elemental's elemental staying
 * hostile when concentration breaks, and Danse Macabre's count and bonus.
 */
class SummonIT extends IntegrationTest {

    private static final String SUMMON_TEMPLATE = """
            {"spellLevel": %d, "concentration": %b, "durationRounds": 600, "deliveryMethod": "NONE",
             "effects": [{"effectType": "SUMMON"}], "requiresManualResolution": true,
             "manualResolutionReason": "Summons creatures"}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private CharacterRepository characterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String breaId;
    private String goblinId;
    private UUID character;
    private Monster wolf;
    private Monster owl;
    private Monster dretch;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("brea");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        character = createCharacter(player.accessToken(), insertRace("Wood Elf"), insertClass("Druid", 8));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        spell("Conjure Animals", 3, true);
        spell("Summon Lesser Demons", 3, true);
        spell("Find Familiar", 1, false);

        wolf = monster("Wolf", "beast", null, "1/4", 11, 13, 15);
        owl = monster("Owl", "beast", null, "0", 1, 11, 13);
        dretch = monster("Dretch", "fiend", "demon", "1/4", 18, 11, 11);
        Monster goblin = monster("Goblin", "humanoid", "goblinoid", "1/4", 7, 15, 14);

        breaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        goblinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin")), "Goblin");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", breaId, "initiative", 18),
                        Map.of("participantId", goblinId, "initiative", 10))),
                dm.accessToken()));
    }

    private void start() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant brea = participantRepository.findById(UUID.fromString(breaId)).orElseThrow();
        brea.setHpMax(40);
        brea.setHpCurrent(40);
        brea.setSpellSlotsCurrent(new HashMap<>(Map.of("3", new SpellSlot(3, 0), "5", new SpellSlot(1, 0))));
        brea.setSpellAttackBonus(6);
        brea.setSpellSaveDc(14);
        brea.setSpellsKnown(List.of(
                new KnownSpell(null, "Conjure Animals", 3, "class:Druid", true, null, null, null, null),
                new KnownSpell(null, "Summon Lesser Demons", 3, "class:Druid", true, null, null, null, null)));
        participantRepository.save(brea);
    }

    @Test
    @DisplayName("A familiar joins while the encounter is prepared, as the DM's companion, with its own initiative, and the encounter starts with it")
    void findFamiliar_whilePreparing() {
        dice.script(12);
        JsonNode state = addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "displayName", "Owl", "monsterId", owl.getId(), "summonerParticipantId", breaId,
                "spellName", "Find Familiar", "creatureType", "fey"));

        JsonNode familiar = participant(state, idOf(state, "Owl"));
        assertThat(familiar.get("participantType").asString()).isEqualTo("COMPANION");
        assertThat(familiar.get("summonedByParticipantId").asString()).isEqualTo(breaId);
        assertThat(familiar.get("summon").get("spell").asString()).isEqualTo("Find Familiar");
        assertThat(familiar.get("summon").get("cannotAttack").asBoolean()).isTrue();
        assertThat(familiar.get("summon").get("creatureType").asString()).isEqualTo("fey");
        assertThat(familiar.get("controlledByUserId").asString()).isEqualTo(player.id().toString());
        // d20 12 + DEX 13's +1
        assertThat(familiar.get("initiative").asInt()).isEqualTo(13);
        JsonNode effect = participant(state, breaId).get("activeEffects").get(0);
        assertThat(effect.get("summon").get("status").asString()).isEqualTo("PLACED");
        assertThat(effect.get("summonedParticipantIds").get(0).asString()).isEqualTo(familiar.get("id").asString());

        start();
        assertThat(dmState().get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Conjure Animals at 5th brings sixteen wolves on one group roll; the player sees their numbers; undo takes them back with the slot")
    void conjureAnimals_fifthLevel_andUndo() {
        start();
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Conjure Animals&slotLevel=5", null,
                player.accessToken()));
        assertThat(options.get("multiplier").asInt()).isEqualTo(2);
        List<String> optionIds = new ArrayList<>();
        options.get("options").forEach(option -> optionIds.add(option.get("id").asString()));
        assertThat(optionIds).containsExactly("2xCR2", "4xCR1", "8xCR1/2", "16xCR1/4");
        assertThat(options.get("candidates").findValuesAsString("name")).contains("Wolf", "Owl").doesNotContain("Goblin");
        assertThat(options.get("defaultInitiativeMode").asString()).isEqualTo("GROUP");

        dice.script(9);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Conjure Animals", "slotLevel", 5,
                "targetIds", List.of(), "summon", Map.of("optionId", "16xCR1/4",
                        "creatures", List.of(Map.of("monsterId", wolf.getId(), "count", 16)))), player.accessToken()));

        // The cast says what it summoned, not the spell data's note for resolving it by hand
        assertThat(cast.get("resultSummary").asString()).contains("casts Conjure Animals at level 5 — summons Wolf ×16")
                .doesNotContain("Summons creatures");
        assertThat(cast.get("autoResolved").asBoolean()).isTrue();

        JsonNode seen = cast.get("encounterState");
        List<JsonNode> wolves = companions(seen);
        assertThat(wolves).hasSize(16);
        assertThat(wolves).allSatisfy(w -> {
            assertThat(w.get("initiative").asInt()).isEqualTo(11);
            assertThat(w.get("hpCurrent").asInt()).isEqualTo(11);
            assertThat(w.get("summon").get("creatureType").asString()).isEqualTo("fey");
        });
        assertThat(participant(seen, breaId).get("concentrationSpell").asString()).isEqualTo("Conjure Animals");
        assertThat(participant(dmState(), breaId).get("spellSlotsCurrent").get("5").get("used").asInt()).isEqualTo(1);

        JsonNode undo = dmState().get("undo");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", undo.get("commandId").asString()), dm.accessToken()));
        JsonNode after = dmState();
        assertThat(companions(after)).isEmpty();
        assertThat(participant(after, breaId).get("spellSlotsCurrent").get("5").get("used").asInt()).isZero();
        assertThat(participant(after, breaId).get("concentrationSpell").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM encounter_participants WHERE "
                + "participant_type = 'COMPANION'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("A wrong count is a 400 that spends nothing")
    void conjureAnimals_wrongCount() {
        start();
        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Conjure Animals", "slotLevel", 3,
                "targetIds", List.of(), "summon", Map.of("optionId", "8xCR1/4",
                        "creatures", List.of(Map.of("monsterId", wolf.getId(), "count", 5)))), player.accessToken()));
        assertThat(participant(dmState(), breaId).get("spellSlotsCurrent").get("3").get("used").asInt()).isZero();
    }

    @Test
    @DisplayName("Summon Lesser Demons waits for the DM, who places demons the players see as monsters; a player can't place them")
    void lesserDemons_dmFlow() {
        start();
        dice.script(3);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Summon Lesser Demons", "slotLevel", 3,
                "targetIds", List.of()), player.accessToken()));
        assertThat(cast.get("resultSummary").asString()).contains("the DM chooses them")
                .doesNotContain("Summons creatures");
        assertThat(cast.get("manualResolutionReason").asString())
                .isEqualTo("The DM chooses the creatures for Summon Lesser Demons");
        JsonNode effect = participant(dmState(), breaId).get("activeEffects").get(0);
        assertThat(effect.get("summon").get("status").asString()).isEqualTo("AWAITING_DM");
        assertThat(effect.get("summon").get("roll").asInt()).isEqualTo(3);
        assertThat(effect.get("summon").get("optionId").asString()).isEqualTo("4xCR1/2");
        String effectId = effect.get("id").asString();

        Map<String, Object> place = Map.of("effectId", effectId,
                "creatures", List.of(Map.of("monsterId", dretch.getId(), "count", 4)));
        expect(HttpStatus.FORBIDDEN, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/summons",
                place, player.accessToken()));
        // The d6 set the option (XGE p.167): the DM can't name another, which leaves the rolled one
        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/summons", Map.of("effectId", effectId, "optionId", "2xCR1",
                "creatures", List.of(Map.of("monsterId", dretch.getId(), "count", 2))), dm.accessToken()));
        assertThat(participant(dmState(), breaId).get("activeEffects").get(0).get("summon").get("optionId")
                .asString()).isEqualTo("4xCR1/2");
        dice.script(15);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/summons", place,
                dm.accessToken()));

        List<JsonNode> demons = companions(dmState());
        assertThat(demons).hasSize(4);
        assertThat(demons).allSatisfy(d -> assertThat(d.get("controlledByUserId").isNull()).isTrue());
        List<JsonNode> seen = companions(playerState());
        assertThat(seen).hasSize(4);
        assertThat(seen).allSatisfy(d -> {
            assertThat(d.get("hpCurrent").isNull()).isTrue();
            assertThat(d.get("healthStatus").asString()).isEqualTo("UNHARMED");
            assertThat(d.get("monsterId").isNull()).isTrue();
        });
    }

    @Test
    @DisplayName("A summoned companion added from the form takes the DM's name, numbered when there are several, or its stat block's")
    void companion_takesDisplayName() {
        dice.script(12);
        JsonNode state = addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "displayName", "Hawk", "monsterId", owl.getId(), "summonerParticipantId", breaId,
                "spellName", "Find Familiar", "creatureType", "fey"));
        assertThat(participant(state, idOf(state, "Hawk")).get("summon").get("spell").asString())
                .isEqualTo("Find Familiar");

        spell("Animate Dead", 3, false);
        Monster skeleton = monster("Skeleton", "undead", null, "1/4", 13, 13, 14);
        dice.script(8);
        state = addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "displayName", "Bones", "monsterId", skeleton.getId(), "quantity", 3,
                "summonerParticipantId", breaId, "spellName", "Animate Dead", "slotLevel", 4));
        idOf(state, "Bones 1");
        idOf(state, "Bones 3");

        // Without a name, the stat block's
        dice.script(5);
        state = addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "monsterId", skeleton.getId(), "summonerParticipantId", breaId, "spellName", "Animate Dead"));
        idOf(state, "Skeleton");

        // A monster still needs its name
        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/participants",
                Map.of("participantType", "MONSTER", "monsterId", skeleton.getId()), dm.accessToken()));
    }

    @Test
    @DisplayName("A reprinted demon is offered once, from the newer book")
    void candidates_reprintOnce() {
        monster("Maw Demon", "fiend", "demon", "1", 33, 13, 8, "VGM");
        Monster newer = monster("Maw Demon", "fiend", "demon", "1", 33, 13, 8, "MPMM");
        start();
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Summon Lesser Demons&slotLevel=3",
                null, player.accessToken()));
        List<JsonNode> maws = new ArrayList<>();
        options.get("candidates").forEach(c -> {
            if ("Maw Demon".equals(c.get("name").asString())) {
                maws.add(c);
            }
        });
        assertThat(maws).hasSize(1);
        assertThat(maws.getFirst().get("monsterId").asString()).isEqualTo(newer.getId().toString());
        assertThat(options.get("options").findValuesAsString("label"))
                .containsExactly("2 demons of challenge rating 1 or lower", "4 demons of challenge rating 1/2 or lower",
                        "8 demons of challenge rating 1/4 or lower");
    }

    @Test
    @DisplayName("Removing a summoned creature mid-fight dismisses it from both views; removing its caster dismisses the rest")
    void remove_dismisses() {
        start();
        dice.script(9);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Conjure Animals", "slotLevel", 3,
                "targetIds", List.of(), "summon", Map.of("optionId", "2xCR1",
                        "creatures", List.of(Map.of("monsterId", wolf.getId(), "count", 2)))), player.accessToken()));
        String first = companions(dmState()).getFirst().get("id").asString();

        expect(HttpStatus.OK, exchange(HttpMethod.DELETE, "/api/encounters/" + encounterId + "/participants/" + first,
                null, dm.accessToken()));
        assertThat(companions(dmState())).hasSize(1);
        assertThat(companions(playerState())).hasSize(1);
        assertThat(dmState().get("turnOrder").findValuesAsString("participantId")).doesNotContain(first);
        assertThat(jdbcTemplate.queryForObject("SELECT dismissed FROM encounter_participants WHERE id = ?::uuid",
                Boolean.class, first)).isTrue();

        expect(HttpStatus.OK, exchange(HttpMethod.DELETE, "/api/encounters/" + encounterId + "/participants/" + breaId,
                null, dm.accessToken()));
        assertThat(companions(dmState())).isEmpty();
    }

    @Test
    @DisplayName("A Pact of the Chain warlock's familiar attacks with its reaction in place of one of the warlock's "
            + "attacks, on the warlock's turn; a second is refused, and undo takes it back (PHB p.107)")
    void pactOfTheChain_familiarAttacks() {
        Monster imp = monsterRepository.save(Monster.builder().name("Imp").type("fiend").subtype("devil")
                .challengeRating("1").source("MM").hitPoints(10).armourClass(13).dexterity(17)
                .actionTemplates(MonsterTemplates.parse("""
                        {"actions": [{"name": "Sting", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
                          "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                          "effects": [{"effectType": "DAMAGE", "damageDice": "1d4 + 3", "damageType": "piercing"}]}]}"""))
                .build());
        dice.script(13);
        String impId = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "COMPANION",
                "monsterId", imp.getId(), "summonerParticipantId", breaId, "spellName", "Find Familiar",
                "creatureType", "fiend")), "Imp");
        start();
        Map<String, Object> sting = Map.of("monsterParticipantId", impId, "actionName", "Sting",
                "actionSource", "ACTION", "targetParticipantIds", List.of(goblinId), "pactOfTheChain", true);

        // Without Pact of the Chain a familiar's attack is refused
        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/monster-action", sting, player.accessToken()));
        PlayerCharacter sheet = characterRepository.findById(character).orElseThrow();
        sheet.setFeatures(new ArrayList<>(List.of(new Feature("Pact of the Chain", "A familiar", "Warlock"))));
        characterRepository.save(sheet);

        dice.script(15, 2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                sting, player.accessToken()));
        JsonNode state = dmState();
        assertThat(participant(state, goblinId).get("hpCurrent").asInt()).isEqualTo(7 - 5);
        assertThat(participant(state, breaId).get("turnEconomy").get("attackActionTaken").asBoolean()).isTrue();
        assertThat(participant(state, impId).get("turnEconomy").get("reactionUsed").asBoolean()).isTrue();
        assertThat(participant(playerState(), breaId).get("pactOfTheChain").asBoolean()).isTrue();

        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/monster-action", sting, player.accessToken()));

        String newest = dmState().get("undo").get("commandId").asString();
        JsonNode undone = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/undo", Map.of("expectedCommandId", newest), dm.accessToken()));
        assertThat(participant(undone, goblinId).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(participant(undone, breaId).get("turnEconomy").get("attackActionTaken").asBoolean()).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void spell(String name, int level, boolean concentration) {
        spellRepository.save(Spell.builder().name(name).level(level).source("PHB").castingTime("1 action")
                .effectTemplate(SpellTemplate.parse(SUMMON_TEMPLATE.formatted(level, concentration))).build());
    }

    private Monster monster(String name, String type, String subtype, String cr, int hp, int ac, int dex) {
        return monster(name, type, subtype, cr, hp, ac, dex, "MM");
    }

    private Monster monster(String name, String type, String subtype, String cr, int hp, int ac, int dex,
                            String source) {
        return monsterRepository.save(Monster.builder().name(name).type(type).subtype(subtype).challengeRating(cr)
                .source(source).hitPoints(hp).armourClass(ac).dexterity(dex).build());
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode playerState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken()));
    }

    private static List<JsonNode> companions(JsonNode encounter) {
        List<JsonNode> found = new ArrayList<>();
        encounter.get("participants").forEach(p -> {
            if ("COMPANION".equals(p.get("participantType").asString())) {
                found.add(p);
            }
        });
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

    // ── The 4th-level summonings (D185) ─────────────────────────

    // Gives Aria 4th- and 5th-level slots and the 4th-level spell, after the start
    private void fourthLevel(String spellName) {
        EncounterParticipant brea = participantRepository.findById(UUID.fromString(breaId)).orElseThrow();
        brea.setSpellSlotsCurrent(new HashMap<>(Map.of("4", new SpellSlot(2, 0), "5", new SpellSlot(1, 0))));
        List<KnownSpell> known = new ArrayList<>(brea.getSpellsKnown());
        known.add(new KnownSpell(null, spellName, 4, "class:Druid", true, null, null, null, null));
        brea.setSpellsKnown(known);
        participantRepository.save(brea);
    }

    private JsonNode castSummon(String spellName, int slotLevel, Map<String, Object> summon, HttpStatus status) {
        return expect(status, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", spellName, "slotLevel", slotLevel,
                "targetIds", List.of(), "summon", summon), player.accessToken()));
    }

    private JsonNode damage(String targetId, int amount, String damageType) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount, "damageType", damageType), dm.accessToken()));
    }

    @Test
    @DisplayName("Summon Elemental at 4th and 5th: the spirit's AC, HP and Slams scale with the slot, the Fire spirit "
            + "slams with fire and ignores it, and the Water spirit at 5th resists acid (TCE p.111)")
    void summonElemental_fourthAndFifth() {
        spell("Summon Elemental", 4, true);
        // Its row lists only poison as a plain immunity; the variants' defences are conditional entries (TCE p.111)
        monsterRepository.save(Monster.builder().name("Elemental Spirit").type("elemental").source("TCE")
                .hitPoints(50).armourClass(15).dexterity(15).charisma(16)
                .damageImmunities("[\"poison\", {\"immune\": [\"fire\"], \"cond\": true, \"note\": \"(Fire only)\"}]")
                .damageResistances("[{\"resist\": [\"acid\"], \"cond\": true, \"note\": \"(Water only)\"}]")
                .build());
        start();
        fourthLevel("Summon Elemental");

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Summon Elemental&slotLevel=4", null,
                player.accessToken()));
        List<String> variants = new ArrayList<>();
        options.get("variants").forEach(v -> variants.add(v.asString()));
        assertThat(variants).containsExactly("Air", "Earth", "Fire", "Water");
        assertThat(options.get("spirit").get("armourClass").asInt()).isEqualTo(15);
        assertThat(options.get("spirit").get("hitPoints").get("Fire").asInt()).isEqualTo(50);

        JsonNode cast = castSummon("Summon Elemental", 4, Map.of("variant", "Fire"), HttpStatus.OK);
        assertThat(cast.get("resultSummary").asString()).contains("summons Elemental Spirit (Fire)");
        JsonNode fire = companions(dmState()).getFirst();
        String fireId = fire.get("id").asString();
        assertThat(fire.get("displayName").asString()).isEqualTo("Elemental Spirit (Fire)");
        assertThat(fire.get("armourClass").asInt()).isEqualTo(15);
        assertThat(fire.get("hpMax").asInt()).isEqualTo(50);
        assertThat(fire.get("summon").get("damageImmunities").get(0).asString()).isEqualTo("fire");
        // The player who controls it reads its actions
        JsonNode actions = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/participants/" + fireId + "/actions", null, player.accessToken()));
        JsonNode multiattack = actions.get("actions").get(0);
        assertThat(multiattack.get("multiattackComponents").size()).isEqualTo(2);
        JsonNode slam = actions.get("actions").get(1);
        assertThat(slam.get("name").asString()).isEqualTo("Slam");
        assertThat(slam.get("attackBonus").asInt()).isEqualTo(6);
        assertThat(slam.get("effects").get(0).get("damageDice").asString()).isEqualTo("1d10 + 8");
        assertThat(slam.get("effects").get(0).get("damageType").asString()).isEqualTo("fire");

        // Immune to fire and poison, not to cold
        assertThat(participant(damage(fireId, 12, "fire"), fireId).get("hpCurrent").asInt()).isEqualTo(50);
        assertThat(participant(damage(fireId, 12, "poison"), fireId).get("hpCurrent").asInt()).isEqualTo(50);
        assertThat(participant(damage(fireId, 12, "cold"), fireId).get("hpCurrent").asInt()).isEqualTo(38);

        // At 5th, on Aria's next turn (after the spirit's and the goblin's), a new casting ends the first's
        // concentration, and with it the fire spirit
        nextTurn();
        nextTurn();
        nextTurn();
        castSummon("Summon Elemental", 5, Map.of("variant", "Water"), HttpStatus.OK);
        List<JsonNode> left = companions(dmState());
        assertThat(left).hasSize(1);
        JsonNode water = left.getFirst();
        String waterId = water.get("id").asString();
        assertThat(waterId).isNotEqualTo(fireId);
        assertThat(water.get("armourClass").asInt()).isEqualTo(16);
        assertThat(water.get("hpMax").asInt()).isEqualTo(60);
        JsonNode waterSlam = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/participants/" + waterId + "/actions", null, player.accessToken())).get("actions").get(1);
        assertThat(waterSlam.get("effects").get(0).get("damageDice").asString()).isEqualTo("1d10 + 9");
        assertThat(waterSlam.get("effects").get(0).get("damageType").asString()).isEqualTo("bludgeoning");
        assertThat(participant(damage(waterId, 10, "acid"), waterId).get("hpCurrent").asInt()).isEqualTo(55);
        assertThat(participant(damage(waterId, 10, "fire"), waterId).get("hpCurrent").asInt()).isEqualTo(45);
    }

    @Test
    @DisplayName("Summon Greater Demon offers demons of CR 5 at 4th and 6 at 5th; a stronger one is a 400; the demon "
            + "rolls its own initiative and, saving at the end of its turn, breaks free to the DM (XGE p.166)")
    void summonGreaterDemon_crCapAndControl() {
        spell("Summon Greater Demon", 4, true);
        Monster barlgura = monster("Barlgura", "fiend", "demon", "5", 68, 15, 15);
        Monster vrock = monster("Vrock", "fiend", "demon", "6", 104, 15, 15);
        monster("Bearded Devil", "fiend", "devil", "3", 52, 13, 15);
        start();
        fourthLevel("Summon Greater Demon");

        JsonNode atFourth = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Summon Greater Demon&slotLevel=4",
                null, player.accessToken()));
        assertThat(atFourth.get("options").get(0).get("id").asString()).isEqualTo("1xCR5");
        assertThat(atFourth.get("options").get(0).get("maxCr").asDouble()).isEqualTo(5.0);
        assertThat(atFourth.get("candidates").findValuesAsString("name")).contains("Barlgura", "Dretch")
                .doesNotContain("Vrock", "Bearded Devil");
        JsonNode atFifth = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Summon Greater Demon&slotLevel=5",
                null, player.accessToken()));
        assertThat(atFifth.get("options").get(0).get("id").asString()).isEqualTo("1xCR6");
        assertThat(atFifth.get("candidates").findValuesAsString("name")).contains("Barlgura", "Vrock");

        castSummon("Summon Greater Demon", 4, Map.of("optionId", "1xCR5",
                "creatures", List.of(Map.of("monsterId", vrock.getId(), "count", 1))), HttpStatus.BAD_REQUEST);
        assertThat(participant(dmState(), breaId).get("spellSlotsCurrent").get("4").get("used").asInt()).isZero();

        // d20 3 + DEX 15's +2: it acts after the goblin's 10
        dice.script(3);
        castSummon("Summon Greater Demon", 4, Map.of("optionId", "1xCR5",
                "creatures", List.of(Map.of("monsterId", barlgura.getId(), "count", 1))), HttpStatus.OK);
        JsonNode demon = companions(dmState()).getFirst();
        String demonId = demon.get("id").asString();
        assertThat(demon.get("initiative").asInt()).isEqualTo(5);
        assertThat(demon.get("controlledByUserId").asString()).isEqualTo(player.id().toString());
        assertThat(demon.get("summon").get("controlSaveDc").asInt()).isEqualTo(14);

        // Aria's turn, then the goblin's; the demon's turn ends with its save: d20 17 + CHA 10's +0 = 17 vs DC 14
        nextTurn();
        nextTurn();
        dice.script(17);
        nextTurn();
        JsonNode freed = participant(dmState(), demonId);
        assertThat(freed.get("controlledByUserId").isNull()).isTrue();
        assertThat(freed.get("summon").get("uncontrolled").asBoolean()).isTrue();
        // The players see it as they see a monster now
        JsonNode seen = participant(playerState(), demonId);
        assertThat(seen.get("hpCurrent").isNull()).isTrue();
    }

    // ── The 5th-level summonings (D187) ─────────────────────────

    // Gives Aria 5th- and 6th-level slots and the 5th-level spell, after the start
    private void fifthLevel(String spellName) {
        EncounterParticipant brea = participantRepository.findById(UUID.fromString(breaId)).orElseThrow();
        brea.setSpellSlotsCurrent(new HashMap<>(Map.of("5", new SpellSlot(2, 0), "6", new SpellSlot(1, 0))));
        List<KnownSpell> known = new ArrayList<>(brea.getSpellsKnown());
        known.add(new KnownSpell(null, spellName, 5, "class:Druid", true, null, null, null, null));
        brea.setSpellsKnown(known);
        participantRepository.save(brea);
    }

    private JsonNode actionsOf(String participantId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/participants/" + participantId + "/actions", null, player.accessToken()));
    }

    private static JsonNode named(JsonNode actions, String name) {
        for (JsonNode action : actions) {
            if (name.equals(action.get("name").asString())) {
                return action;
            }
        }
        throw new AssertionError(name + " is not among the actions");
    }

    @Test
    @DisplayName("Summon Celestial at 5th: the Defender has AC 18 to the Avenger's 16, 40 HP, a Radiant Mace of "
            + "1d10 + 8 and acts right after its caster (TCE p.110)")
    void summonCelestial_defender() {
        spell("Summon Celestial", 5, true);
        monsterRepository.save(Monster.builder().name("Celestial Spirit").type("celestial").source("TCE")
                .hitPoints(40).armourClass(16).dexterity(14).charisma(16).damageResistances("[\"radiant\"]")
                .build());
        start();
        fifthLevel("Summon Celestial");

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Summon Celestial&slotLevel=5", null,
                player.accessToken()));
        List<String> variants = new ArrayList<>();
        options.get("variants").forEach(v -> variants.add(v.asString()));
        assertThat(variants).containsExactly("Avenger", "Defender");
        assertThat(options.get("spirit").get("armourClasses").get("Avenger").asInt()).isEqualTo(16);
        assertThat(options.get("spirit").get("armourClasses").get("Defender").asInt()).isEqualTo(18);

        JsonNode cast = castSummon("Summon Celestial", 5, Map.of("variant", "Defender"), HttpStatus.OK);
        assertThat(cast.get("resultSummary").asString()).contains("summons Celestial Spirit (Defender)");
        JsonNode defender = companions(dmState()).getFirst();
        assertThat(defender.get("armourClass").asInt()).isEqualTo(18);
        assertThat(defender.get("hpMax").asInt()).isEqualTo(40);
        assertThat(defender.get("initiative").asInt()).isEqualTo(18);
        assertThat(defender.get("summon").get("initiativeMode").asString()).isEqualTo("FOLLOWS_CASTER");
        JsonNode actions = actionsOf(defender.get("id").asString()).get("actions");
        JsonNode mace = named(actions, "Radiant Mace");
        assertThat(mace.get("attackBonus").asInt()).isEqualTo(6);
        assertThat(mace.get("effects").get(0).get("damageDice").asString()).isEqualTo("1d10 + 8");
        assertThat(named(actions, "Multiattack").get("multiattackComponents").size()).isEqualTo(2);
        assertThat(named(actions, "Healing Touch (1/Day)").get("description").asString()).contains("2d8 + 5");
    }

    @Test
    @DisplayName("Animate Objects at 5th animates up to ten objects counted by size: a Huge and a Large is a 400, a "
            + "Huge and two Tiny fit, with the stat blocks' hit points and one initiative per kind (PHB p.213)")
    void animateObjects_budget() {
        spell("Animate Objects", 5, true);
        Monster huge = monsterRepository.save(Monster.builder().name("Animated Object (Huge)").type("construct")
                .source("PHB").size("Huge").hitPoints(0).armourClass(10).dexterity(6).build());
        Monster large = monsterRepository.save(Monster.builder().name("Animated Object (Large)").type("construct")
                .source("PHB").size("Large").hitPoints(0).armourClass(10).dexterity(10).build());
        Monster tiny = monsterRepository.save(Monster.builder().name("Animated Object (Tiny)").type("construct")
                .source("PHB").size("Tiny").hitPoints(0).armourClass(18).dexterity(18).build());
        start();
        fifthLevel("Animate Objects");

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Animate Objects&slotLevel=5", null,
                player.accessToken()));
        assertThat(options.get("options").get(0).get("count").asInt()).isEqualTo(10);
        assertThat(options.get("needsCommand").asBoolean()).isTrue();
        assertThat(options.get("candidates").findValuesAsString("name"))
                .contains("Animated Object (Huge)", "Animated Object (Large)", "Animated Object (Tiny)");
        assertThat(options.get("candidates").findValues("hitPoints").stream().map(JsonNode::asInt).toList())
                .contains(80, 50, 20);
        JsonNode atSixth = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Animate Objects&slotLevel=6", null,
                player.accessToken()));
        assertThat(atSixth.get("options").get(0).get("count").asInt()).isEqualTo(12);

        castSummon("Animate Objects", 5, Map.of("optionId", "10", "creatures", List.of(
                Map.of("monsterId", huge.getId(), "count", 1), Map.of("monsterId", large.getId(), "count", 1))),
                HttpStatus.BAD_REQUEST);
        assertThat(participant(dmState(), breaId).get("spellSlotsCurrent").get("5").get("used").asInt()).isZero();

        // d20 4 + DEX 6's -2 for the Huge one, d20 11 + DEX 18's +4 for the Tiny ones
        dice.script(4, 11);
        castSummon("Animate Objects", 5, Map.of("optionId", "10", "creatures", List.of(
                Map.of("monsterId", huge.getId(), "count", 1), Map.of("monsterId", tiny.getId(), "count", 2))),
                HttpStatus.OK);
        List<JsonNode> objects = companions(dmState());
        assertThat(objects).hasSize(3);
        JsonNode hugeObject = participant(dmState(), idOf(dmState(), "Animated Object (Huge)"));
        assertThat(hugeObject.get("hpMax").asInt()).isEqualTo(80);
        assertThat(hugeObject.get("initiative").asInt()).isEqualTo(2);
        assertThat(hugeObject.get("summon").get("needsCommand").asBoolean()).isTrue();
        JsonNode tinyObject = participant(dmState(), idOf(dmState(), "Animated Object (Tiny) 1"));
        assertThat(tinyObject.get("hpMax").asInt()).isEqualTo(20);
        assertThat(tinyObject.get("initiative").asInt()).isEqualTo(15);
        assertThat(participant(dmState(), idOf(dmState(), "Animated Object (Tiny) 2")).get("initiative").asInt())
                .isEqualTo(15);
    }

    @Test
    @DisplayName("Conjure Elemental: placed by the DM, its own initiative; when Aria's concentration breaks it stays, "
            + "hostile and the DM's; undo gives it back to her, and ending the spell by hand dismisses it (PHB p.225)")
    void conjureElemental_hostileWhenConcentrationBreaks() {
        spell("Conjure Elemental", 5, true);
        Monster fireElemental = monster("Fire Elemental", "elemental", null, "5", 102, 13, 17);
        monster("Invisible Stalker", "elemental", null, "6", 104, 14, 19);
        start();

        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Conjure Elemental&slotLevel=5", null,
                dm.accessToken()));
        assertThat(options.get("castInCombat").asBoolean()).isFalse();
        assertThat(options.get("options").get(0).get("id").asString()).isEqualTo("1xCR5");
        assertThat(options.get("candidates").findValuesAsString("name")).contains("Fire Elemental")
                .doesNotContain("Invisible Stalker", "Wolf");

        // d20 6 + DEX 17's +3
        dice.script(6);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/summons/companion",
                Map.of("casterParticipantId", breaId, "spellName", "Conjure Elemental", "slotLevel", 5,
                        "optionId", "1xCR5", "creatures", List.of(Map.of("monsterId", fireElemental.getId(),
                                "count", 1))), dm.accessToken()));
        JsonNode elemental = companions(dmState()).getFirst();
        String elementalId = elemental.get("id").asString();
        assertThat(elemental.get("initiative").asInt()).isEqualTo(9);
        assertThat(elemental.get("controlledByUserId").asString()).isEqualTo(player.id().toString());
        assertThat(participant(dmState(), breaId).get("concentrationSpell").asString())
                .isEqualTo("Conjure Elemental");

        // Aria drops to 0, which breaks her concentration: the elemental stays, hostile and the DM's
        damage(breaId, 100, "fire");
        JsonNode state = dmState();
        JsonNode hostile = participant(state, elementalId);
        assertThat(companions(state)).hasSize(1);
        assertThat(hostile.get("controlledByUserId").isNull()).isTrue();
        assertThat(hostile.get("summon").get("uncontrolled").asBoolean()).isTrue();
        assertThat(participant(state, breaId).get("concentrationSpell").isNull()).isTrue();
        assertThat(state.get("turnOrder").findValuesAsString("participantId")).contains(elementalId);
        assertThat(participant(state, breaId).path("activeEffects").findValuesAsString("spell"))
                .doesNotContain("Conjure Elemental");

        String newest = state.get("undo").get("commandId").asString();
        JsonNode undone = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/undo", Map.of("expectedCommandId", newest), dm.accessToken()));
        JsonNode back = participant(undone, elementalId);
        assertThat(back.get("controlledByUserId").asString()).isEqualTo(player.id().toString());
        assertThat(back.get("summon").has("uncontrolled")).isFalse();
        assertThat(participant(undone, breaId).get("concentrationSpell").asString()).isEqualTo("Conjure Elemental");

        // Ending the spell by hand isn't a broken concentration: the elemental disappears
        String effectId = participant(undone, breaId).get("activeEffects").get(0).get("id").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/spell-effect/end",
                Map.of("participantId", breaId, "effectId", effectId), dm.accessToken()));
        assertThat(companions(dmState())).isEmpty();
    }

    @Test
    @DisplayName("Danse Macabre at 5th raises up to five zombies or skeletons, seven at 6th, adding Aria's Wisdom "
            + "modifier to their attacks and damage (XGE p.153)")
    void danseMacabre_countAndBonus() {
        spell("Danse Macabre", 5, true);
        Monster zombie = monsterRepository.save(Monster.builder().name("Zombie").type("undead").challengeRating("1/4")
                .source("MM").hitPoints(22).armourClass(8).dexterity(6)
                .actionTemplates(MonsterTemplates.parse("""
                        {"actions": [{"name": "Slam", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
                          "attackType": "MELEE_WEAPON", "attackBonus": 3, "targetCount": 1,
                          "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 1", "damageType": "bludgeoning"}]}]}"""))
                .build());
        Monster skeleton = monster("Skeleton", "undead", null, "1/4", 13, 13, 14);
        PlayerCharacter sheet = characterRepository.findById(character).orElseThrow();
        sheet.setWisdom(16);
        characterRepository.save(sheet);
        start();
        fifthLevel("Danse Macabre");
        // The test's Druid class names no spellcasting ability, so the participant's says Wisdom: 16, +3
        EncounterParticipant brea = participantRepository.findById(UUID.fromString(breaId)).orElseThrow();
        brea.setSpellcastingAbility("WIS");
        participantRepository.save(brea);

        JsonNode atFifth = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Danse Macabre&slotLevel=5", null,
                player.accessToken()));
        assertThat(atFifth.get("options").get(0).get("count").asInt()).isEqualTo(5);
        assertThat(atFifth.get("candidates").findValuesAsString("name")).containsExactlyInAnyOrder("Skeleton",
                "Zombie");
        JsonNode atSixth = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/summon-options?participantId=" + breaId + "&spellName=Danse Macabre&slotLevel=6", null,
                player.accessToken()));
        assertThat(atSixth.get("options").get(0).get("count").asInt()).isEqualTo(7);

        castSummon("Danse Macabre", 5, Map.of("optionId", "5", "creatures", List.of(
                Map.of("monsterId", zombie.getId(), "count", 6))), HttpStatus.BAD_REQUEST);

        dice.script(10, 12);
        JsonNode cast = castSummon("Danse Macabre", 5, Map.of("optionId", "5", "creatures", List.of(
                Map.of("monsterId", zombie.getId(), "count", 3), Map.of("monsterId", skeleton.getId(), "count", 1))),
                HttpStatus.OK);
        assertThat(cast.get("resultSummary").asString()).contains("Zombie ×3");
        List<JsonNode> undead = companions(dmState());
        assertThat(undead).hasSize(4);
        assertThat(participant(dmState(), breaId).get("concentrationSpell").asString()).isEqualTo("Danse Macabre");
        String zombieId = idOf(dmState(), "Zombie 1");
        assertThat(participant(dmState(), zombieId).get("summon").get("needsCommand").asBoolean()).isTrue();
        JsonNode slam = named(actionsOf(zombieId).get("actions"), "Slam");
        assertThat(slam.get("attackBonus").asInt()).isEqualTo(6);
        assertThat(slam.get("effects").get(0).get("damageDice").asString()).isEqualTo("1d6 + 4");
    }

    @Test
    @DisplayName("Summon Draconic Spirit at 5th: a gem spirit with Shared Resistances to radiant halves radiant damage "
            + "to Aria until the spell ends; fire, which a gem spirit doesn't resist, is a 400 (FTD p.21)")
    void summonDraconicSpirit_sharedResistance() {
        spellRepository.save(Spell.builder().name("Summon Draconic Spirit").level(5).source("FTD")
                .castingTime("1 action").effectTemplate(SpellTemplate.parse("""
                        {"spellLevel": 5, "concentration": true, "durationRounds": 600, "deliveryMethod": "NONE",
                         "castOptions": ["acid", "cold", "fire", "lightning", "poison", "force", "necrotic",
                                         "psychic", "radiant", "thunder"],
                         "effects": [{"effectType": "SUMMON"},
                                     {"effectType": "BUFF", "description": "Shared Resistances",
                                      "modifiers": [{"kind": "RESISTANCE", "damageTypes": ["OPTION"]}]}],
                         "requiresManualResolution": true, "manualResolutionReason": "Summons creatures"}"""))
                .build());
        monsterRepository.save(Monster.builder().name("Draconic Spirit").type("dragon").source("FTD")
                .hitPoints(50).armourClass(19).dexterity(14).charisma(14).build());
        start();
        fifthLevel("Summon Draconic Spirit");

        expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Summon Draconic Spirit",
                "slotLevel", 5, "targetIds", List.of(), "option", "fire", "summon", Map.of("variant", "Gem")),
                player.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + breaId, Map.of("spellName", "Summon Draconic Spirit",
                "slotLevel", 5, "targetIds", List.of(), "option", "radiant", "summon", Map.of("variant", "Gem")),
                player.accessToken()));
        JsonNode dragon = companions(dmState()).getFirst();
        String dragonId = dragon.get("id").asString();
        assertThat(dragon.get("displayName").asString()).isEqualTo("Draconic Spirit (Gem)");
        assertThat(dragon.get("armourClass").asInt()).isEqualTo(19);
        // Her concentration save, DC 10, succeeds
        dice.script(20);
        assertThat(participant(damage(breaId, 10, "radiant"), breaId).get("hpCurrent").asInt()).isEqualTo(35);
        assertThat(participant(damage(dragonId, 10, "necrotic"), dragonId).get("hpCurrent").asInt()).isEqualTo(45);
        assertThat(participant(damage(dragonId, 10, "fire"), dragonId).get("hpCurrent").asInt()).isEqualTo(35);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }
}
