package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spellcasting from Spell Sources, end to end (D164, D165). Aria, a player's warlock with two level 2 pact slots
 * and Hellish Rebuke, goes first; then a night hag with Thunderwave once a day, an aboleth with legendary actions
 * but no Legendary Resistance, and a dragon with both. The pool triggers are the rows V18 leaves.
 */
class SpellcastingIT extends IntegrationTest {

    private static final String HELLISH_REBUKE = """
            {"spellName": "Hellish Rebuke", "spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "fire",
               "upcastScaling": {"additionalDicePerLevel": "1d10"}}]}""";

    private static final String THUNDERWAVE = """
            {"spellName": "Thunderwave", "spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON",
             "halfOnSave": true, "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "thunder"}]}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private MonsterPoolTriggerRepository triggerRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID encounterId;
    private String ariaId;
    private String hagId;
    private String abolethId;
    private String dragonId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Tiefling"), insertClass("Warlock", 8));
        encounterId = createEncounter(dm.accessToken(), campaignId);
        spell("Hellish Rebuke", 1, HELLISH_REBUKE);
        spell("Thunderwave", 1, THUNDERWAVE);
        spell("Detect Magic", 1, null);
        spell("Fireball", 3, null);
        seedTriggers();

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        hagId = addMonster(Monster.builder().name("Night Hag").hitPoints(112).armourClass(17).dexterity(16)
                .actionTemplates("""
                        {"spellcasting": {"innateSpells": true, "ability": "CHA", "saveDC": 14,
                         "atWillSpells": ["detect magic"], "dailySpells": {"1e/day": ["thunderwave"]}}}"""));
        abolethId = addMonster(Monster.builder().name("Aboleth").hitPoints(135).armourClass(17).dexterity(9)
                .legendaryActions("[{\"name\": \"Tail Swipe\", \"entries\": [\"A legendary action\"]}]")
                .traits("[{\"name\": \"Amphibious\"}, {\"name\": \"Mucous Cloud\"}]")
                .actionTemplates("{\"legendaryActionCount\": 3}"));
        dragonId = addMonster(Monster.builder().name("Young Dragon").hitPoints(178).armourClass(18).dexterity(10)
                .legendaryActions("[{\"name\": \"Wing Attack\"}]")
                .traits("[{\"name\": \"Legendary Resistance (3/Day)\"}]")
                .actionTemplates("{\"legendaryActionCount\": 3, \"legendaryResistanceCount\": 3}"));

        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(40);
        aria.setHpCurrent(40);
        aria.setSpellSaveDc(13);
        aria.setSpellAttackBonus(5);
        aria.setSpellSlotsCurrent(Map.of("pact_2", new SpellSlot(2, 0)));
        aria.setSpellsKnown(List.of(
                new KnownSpell(null, "Hellish Rebuke", 1, "class:Warlock", null, null, null, null, null)));
        participantRepository.save(aria);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", hagId, "initiative", 10),
                        Map.of("participantId", abolethId, "initiative", 5),
                        Map.of("participantId", dragonId, "initiative", 3))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("An innate monster spell spends its daily use through the one cast path, and none left is a 400")
    void innateMonsterSpell() {
        JsonNode hag = participant(dmView(), hagId);
        assertThat(pool(hag, "innate:thunderwave").get("currentUses").asInt()).isEqualTo(1);
        JsonNode options = expect(HttpStatus.OK, spellOptions(hagId, dm.accessToken()));
        assertThat(options.toString()).isEqualTo("[{\"name\":\"Detect Magic\",\"level\":1,\"sources\":["
                + "{\"source\":\"NONE\",\"levels\":[1],\"usesLeft\":null,\"usesMax\":null}]},"
                + "{\"name\":\"Thunderwave\",\"level\":1,\"sources\":["
                + "{\"source\":\"INNATE\",\"levels\":[1],\"usesLeft\":1,\"usesMax\":1}]}]");

        // 8 on 2d8, and Aria's CON save of 3 fails against DC 14
        dice.script(4, 4, 2);
        JsonNode cast = expect(HttpStatus.OK, castSpell(hagId,
                Map.of("spellName", "Thunderwave", "spellSource", "INNATE", "slotLevel", 0, "targetIds", List.of(ariaId)),
                dm.accessToken()));
        var again = castSpell(hagId, Map.of("spellName", "Thunderwave", "slotLevel", 0, "targetIds", List.of(ariaId)),
                dm.accessToken());

        assertThat(cast.get("spellSource").asText()).isEqualTo("INNATE");
        assertThat(cast.get("slotLevelUsed").asInt()).isEqualTo(1);
        assertThat(participant(cast.get("encounterState"), ariaId).get("hpCurrent").asInt()).isEqualTo(32);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(again).get("error").asText()).isEqualTo("Night Hag has no uses of Thunderwave left");
        assertThat(pool(participant(dmView(), hagId), "innate:thunderwave").get("currentUses").asInt()).isZero();
        assertThat(descriptions(dmLog())).contains("Night Hag uses an innate use of Thunderwave (0/1 remaining)");
        // The old monster-spell endpoint is gone
        assertThat(exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-spell",
                Map.of("monsterParticipantId", hagId, "spellName", "Thunderwave", "targetParticipantIds", List.of()),
                dm.accessToken()).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    @DisplayName("A warlock's pact slot casts at the pact slots' level, upcasting the spell")
    void warlockPactSlot() {
        // 15 on 3d10 at level 2, and the hag's DEX save of 2 + 3 fails against DC 13
        dice.script(5, 5, 5, 2);
        JsonNode cast = expect(HttpStatus.OK, castSpell(ariaId, Map.of("spellName", "Hellish Rebuke",
                "spellSource", "PACT_SLOT", "slotLevel", 0, "targetIds", List.of(hagId)), player.accessToken()));

        assertThat(cast.get("spellSource").asText()).isEqualTo("PACT_SLOT");
        assertThat(cast.get("slotLevelUsed").asInt()).isEqualTo(2);
        assertThat(cast.get("resultSummary").asText()).startsWith("Aria casts Hellish Rebuke at level 2.");
        JsonNode state = dmView();
        assertThat(participant(state, hagId).get("hpCurrent").asInt()).isEqualTo(97);
        assertThat(participant(state, ariaId).get("spellSlotsCurrent").get("pact_2").get("used").asInt()).isEqualTo(1);
        assertThat(descriptions(dmLog())).contains("Aria uses a level 2 pact slot (1/2 remaining)");
    }

    @Test
    @DisplayName("A spell the character can't cast, or can't pay for, is a 400, and nothing is spent")
    void illegalPlayerSpell() {
        var unknown = castSpell(ariaId, Map.of("spellName", "Fireball", "slotLevel", 2, "targetIds", List.of(hagId)),
                player.accessToken());
        var free = castSpell(ariaId, Map.of("spellName", "Hellish Rebuke", "slotLevel", 0, "targetIds", List.of(hagId)),
                player.accessToken());
        var wrongSource = castSpell(ariaId, Map.of("spellName", "Hellish Rebuke", "spellSource", "SLOT",
                "slotLevel", 2, "targetIds", List.of(hagId)), player.accessToken());

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(unknown).get("error").asText()).isEqualTo("Aria can't cast Fireball: it isn't one of their spells");
        assertThat(free.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(free).get("error").asText())
                .isEqualTo("Hellish Rebuke is a level 1 spell, so it needs a spell slot, a pact slot or an innate use");
        assertThat(wrongSource.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(wrongSource).get("error").asText()).isEqualTo("Hellish Rebuke can't be cast with a spell slot");
        assertThat(participant(dmView(), ariaId).get("spellSlotsCurrent").get("pact_2").get("used").asInt()).isZero();
        assertThat(participant(dmView(), hagId).get("hpCurrent").asInt()).isEqualTo(112);

        // The player reads their own options, and not a monster's
        JsonNode options = expect(HttpStatus.OK, spellOptions(ariaId, player.accessToken()));
        assertThat(options.get(0).get("name").asText()).isEqualTo("Hellish Rebuke");
        assertThat(options.get(0).get("sources").get(0).get("source").asText()).isEqualTo("PACT_SLOT");
        assertThat(spellOptions(hagId, player.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A player's Manual Modifier is a 403 once the campaign disallows them; the DM's is used and logged")
    void manualModifierSetting() {
        JsonNode settings = expect(HttpStatus.OK, exchange(HttpMethod.PATCH, "/api/campaigns/" + campaignId + "/settings",
                Map.of("allowPlayerManualModifiers", false), dm.accessToken()));
        Map<String, Object> withDc = new HashMap<>(Map.of("spellName", "Hellish Rebuke", "spellSource", "PACT_SLOT",
                "slotLevel", 2, "targetIds", List.of(hagId), "overrideSpellSaveDC", 20));

        var byPlayer = castSpell(ariaId, withDc, player.accessToken());
        // 15 on 3d10, and the hag's DEX save of 14 + 3 fails against the DM's DC 20
        dice.script(5, 5, 5, 14);
        expect(HttpStatus.OK, castSpell(ariaId, withDc, dm.accessToken()));

        assertThat(settings.get("allowPlayerManualModifiers").asBoolean()).isFalse();
        assertThat(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken())).get("allowPlayerManualModifiers").asBoolean()).isFalse();
        assertThat(byPlayer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(byPlayer).get("error").asText())
                .isEqualTo("This campaign doesn't allow players to enter Manual Modifiers");
        assertThat(participant(dmView(), hagId).get("hpCurrent").asInt()).isEqualTo(97);
        assertThat(descriptions(dmLog())).contains("Manual Modifier (DM): Aria's Hellish Rebuke uses spell save DC 20 "
                + "instead of 13.");
    }

    @Test
    @DisplayName("Joining monsters get Legendary Resistance from the trait of that name, not from legendary actions")
    void legendaryResistanceTrigger() {
        JsonNode state = dmView();

        assertThat(poolIds(participant(state, abolethId))).containsExactly("monster:legendary-actions");
        assertThat(poolIds(participant(state, dragonId)))
                .containsExactly("monster:legendary-actions", "monster:legendary-resistance");
        assertThat(poolIds(participant(state, hagId))).containsExactly("innate:thunderwave");
        // Players never see a monster's pools or spells (D157)
        JsonNode playerHag = participant(expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/encounters/" + encounterId, null, player.accessToken())), hagId);
        assertThat(playerHag.get("resourcePoolsCurrent").isNull()).isTrue();
        assertThat(playerHag.get("spellsKnown").isNull()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void spell(String name, int level, String template) {
        spellRepository.save(Spell.builder().name(name).level(level).source("PHB").effectTemplate(template).build());
    }

    // The monster pool definitions and triggers as V18 leaves them
    private void seedTriggers() {
        ResourcePoolDefinition legendaryActions = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId("monster:legendary-actions").displayName("Legendary Actions").sourceType("MONSTER")
                .maxUsesFormula("3").resetOn("turn").spendActionType("FREE").build());
        ResourcePoolDefinition legendaryResistance = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId("monster:legendary-resistance").displayName("Legendary Resistance").sourceType("MONSTER")
                .maxUsesFormula("3").resetOn("longRest").spendActionType("REACTION").build());
        triggerRepository.save(MonsterPoolTrigger.builder().poolDefinition(legendaryActions)
                .triggerCondition("{\"hasField\": \"legendary\"}").priority(1).build());
        triggerRepository.save(MonsterPoolTrigger.builder().poolDefinition(legendaryResistance)
                .triggerCondition("{\"hasFeature\": \"Legendary Resistance\"}").priority(2).build());
    }

    private String addMonster(Monster.MonsterBuilder monster) {
        Monster saved = monsterRepository.save(monster.type("Monstrosity").challengeRating("5").source("MM")
                .build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", saved.getName())),
                saved.getName());
    }

    private ResponseEntity<String> castSpell(String casterId, Map<String, Object> request, String token) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + casterId,
                request, token);
    }

    private ResponseEntity<String> spellOptions(String participantId, String token) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/spell-options?participantId="
                + participantId, null, token);
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asText().equals(displayName)) {
                return participant.get("id").asText();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asText().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
            if (pool.get("poolId").asText().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError(participant.get("displayName").asText() + " has no " + poolId + " pool");
    }

    private static List<String> poolIds(JsonNode participant) {
        List<String> ids = new ArrayList<>();
        participant.get("resourcePoolsCurrent").forEach(pool -> ids.add(pool.get("poolId").asText()));
        return ids;
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asText()));
        return descriptions;
    }
}
