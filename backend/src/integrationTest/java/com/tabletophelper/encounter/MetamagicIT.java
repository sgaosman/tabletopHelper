package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sorcerer's Metamagic and Font of Magic end to end (M18, PHB p.101–102). A player's Sorin, a Sorcerer 5 with five
 * sorcery points, chooses Twinned and Quickened Spell from the Metamagic choice 3rd level brings, converts slots and
 * points on the sheet and at the table, and casts Hold Person twinned at a goblin and an orc. Undo gives the points
 * and the slot back, and the slot a sorcerer created in combat reaches the sheet until a long rest.
 */
class MetamagicIT extends IntegrationTest {

    private static final String SORCERY_POINTS = "class:sorcerer-sorcery-points";

    private static final String HOLD_PERSON = """
            {"spellName": "Hold Person", "spellLevel": 2, "castingTime": "ACTION", "deliveryMethod": "SAVING_THROW",
             "saveAbility": "WIS", "halfOnSave": false, "concentration": true, "durationRounds": 10,
             "durationText": "Concentration, up to 1 minute", "targetType": "SINGLE_TARGET", "targetCount": 1,
             "targetCountUpcastScaling": {"additionalTargetsPerLevel": 1}, "selfOnly": false,
             "components": {"verbal": true, "somatic": true}, "effects": [], "conditionsInflicted": ["paralyzed"]}""";

    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private String sorinCharacterId;

    @BeforeEach
    void setUpSorcerer() {
        dm = register("dungeonmaster");
        player = register("sorin");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        UUID sorcererId = insertCaster("Sorcerer", 6, "CHA", false);
        CharacterClass sorcerer = characterClassRepository.findById(sorcererId).orElseThrow();
        ResourcePoolDefinition points = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId(SORCERY_POINTS).displayName("Sorcery Points").sourceType("CLASS")
                .maxUsesFormula("sorcererLevel").resetOn("longRest").spendActionType("FREE").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(sorcerer).minLevel(2)
                .poolDefinition(points).build());
        spellRepository.save(Spell.builder().name("Hold Person").level(2).source("PHB").castingTime("1 action")
                .rangeDistance("60 feet").effectTemplate(SpellTemplate.parse(HOLD_PERSON)).build());

        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Sorin", "raceId", insertRace("Human"),
                "classId", sorcererId, "level", 5, "strength", 8, "dexterity", 14, "constitution", 14,
                "intelligence", 10, "wisdom", 12, "charisma", 16));
        body.put("campaignId", campaignId);
        JsonNode created = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken()));
        sorinCharacterId = created.get("id").asString();

        String choiceId = null;
        for (JsonNode choice : created.get("pendingChoices")) {
            if (choice.get("type").asString().equals("METAMAGIC")) {
                choiceId = choice.get("id").asString();
                assertThat(choice.get("count").asInt()).isEqualTo(2);
            }
        }
        assertThat(choiceId).isNotNull();
        JsonNode chosen = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + sorinCharacterId
                + "/apply-choices", Map.of("choiceId", choiceId, "metamagic", List.of("Twinned Spell",
                "Quickened Spell")), player.accessToken()));
        List<String> features = new ArrayList<>();
        chosen.get("features").forEach(feature -> features.add(feature.get("name").asString()));
        assertThat(features).contains("Metamagic: Twinned Spell", "Metamagic: Quickened Spell");
    }

    @Test
    @DisplayName("On the sheet: points at their maximum refuse a slot, 5 points create a 3rd-level slot, and a long rest ends it")
    void flexibleCastingOnTheSheet() {
        assertThat(exchange(HttpMethod.POST, "/api/characters/" + sorinCharacterId + "/font-of-magic",
                Map.of("direction", "SLOT_TO_POINTS", "slotLevel", 1), player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode created = fontOfMagicOnSheet("POINTS_TO_SLOT", 3);
        assertThat(created.get("spellSlots").get("3").get("max").asInt()).isEqualTo(2);
        assertThat(created.get("spellSlots").get("3").get("created").asInt()).isEqualTo(1);
        assertThat(sheetPoints(created)).isZero();

        JsonNode converted = fontOfMagicOnSheet("SLOT_TO_POINTS", 1);
        assertThat(converted.get("spellSlots").get("1").get("used").asInt()).isEqualTo(1);
        assertThat(sheetPoints(converted)).isEqualTo(1);

        JsonNode rested = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + sorinCharacterId
                + "/long-rest", null, player.accessToken()));
        assertThat(rested.get("spellSlots").get("3").has("created")).isFalse();
        assertThat(rested.get("spellSlots").get("1").get("used").asInt()).isZero();
        assertThat(sheetPoints(rested)).isEqualTo(5);
    }

    @Test
    @DisplayName("Twinned Hold Person paralyzes a goblin and an orc for 2 points and one 2nd-level slot; undo gives both back")
    void twinnedHoldPersonAndUndo() {
        Table table = startEncounter();
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + table.encounterId
                + "/combat/spell-options?participantId=" + table.sorinId, null, player.accessToken()));
        JsonNode holdPerson = options.get(0);
        assertThat(holdPerson.get("name").asString()).isEqualTo("Hold Person");
        assertThat(holdPerson.get("metamagic")).hasSize(2);

        dice.script(3, 4);
        JsonNode cast = expect(HttpStatus.OK, castSpell(table, Map.of("spellName", "Hold Person", "slotLevel", 2,
                "targetIds", List.of(table.goblinId, table.orcId),
                "metamagic", List.of(Map.of("option", "TWINNED")))));
        assertThat(cast.get("resultSummary").asString()).contains("(Twinned Spell, 2 sorcery points)");
        JsonNode state = cast.get("encounterState");
        assertThat(conditionNames(participant(state, table.goblinId))).contains("paralyzed");
        assertThat(conditionNames(participant(state, table.orcId))).contains("paralyzed");
        JsonNode sorin = participant(state, table.sorinId);
        assertThat(points(sorin)).isEqualTo(3);
        assertThat(sorin.get("spellSlotsCurrent").get("2").get("used").asInt()).isEqualTo(1);
        assertThat(sorin.get("metamagicOptions")).hasSize(2);

        String newest = dmView(table).get("undo").get("commandId").asString();
        JsonNode undone = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + table.encounterId
                + "/combat/undo", Map.of("expectedCommandId", newest), dm.accessToken()));
        sorin = participant(undone, table.sorinId);
        assertThat(points(sorin)).isEqualTo(5);
        assertThat(sorin.get("spellSlotsCurrent").get("2").get("used").asInt()).isZero();
        assertThat(conditionNames(participant(undone, table.goblinId))).doesNotContain("paralyzed");
    }

    @Test
    @DisplayName("Flexible Casting at the table takes the bonus action, so Quickened Spell must wait; the created slot reaches the sheet")
    void flexibleCastingAtTheTable() {
        Table table = startEncounter();
        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + table.encounterId
                + "/combat/font-of-magic", Map.of("participantId", table.sorinId, "direction", "POINTS_TO_SLOT",
                "slotLevel", 1), player.accessToken()));
        JsonNode sorin = participant(state, table.sorinId);
        assertThat(points(sorin)).isEqualTo(3);
        assertThat(sorin.get("spellSlotsCurrent").get("1").get("created").asInt()).isEqualTo(1);
        assertThat(sorin.get("turnEconomy").get("bonusActionUsed").asBoolean()).isTrue();

        ResponseEntity<String> quickened = castSpell(table, Map.of("spellName", "Hold Person", "slotLevel", 2,
                "targetIds", List.of(table.goblinId), "metamagic", List.of(Map.of("option", "QUICKENED"))));
        assertThat(quickened.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(points(participant(dmView(table), table.sorinId))).isEqualTo(3);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + table.encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        JsonNode sheet = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + sorinCharacterId, null,
                player.accessToken()));
        assertThat(sheet.get("spellSlots").get("1").get("created").asInt()).isEqualTo(1);
        assertThat(sheetPoints(sheet)).isEqualTo(3);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private record Table(UUID encounterId, String sorinId, String goblinId, String orcId) {
    }

    private Table startEncounter() {
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        String sorinId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", sorinCharacterId, "displayName", "Sorin")),
                "Sorin");
        String goblinId = monster(encounterId, "Goblin");
        String orcId = monster(encounterId, "Orc");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", sorinId, "initiative", 20),
                        Map.of("participantId", goblinId, "initiative", 10),
                        Map.of("participantId", orcId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies the sheet into the participant (D166)
        EncounterParticipant sorin = participantRepository.findById(UUID.fromString(sorinId)).orElseThrow();
        sorin.setSpellsKnown(List.of(new KnownSpell(null, "Hold Person", 2, "class:Sorcerer", null, null, null,
                null, null)));
        participantRepository.save(sorin);
        return new Table(encounterId, sorinId, goblinId, orcId);
    }

    private String monster(UUID encounterId, String name) {
        Monster saved = monsterRepository.save(Monster.builder().name(name).type("humanoid").challengeRating("1")
                .source("MM").hitPoints(30).armourClass(13).dexterity(10).wisdom(10).build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
    }

    private ResponseEntity<String> castSpell(Table table, Map<String, Object> request) {
        return exchange(HttpMethod.POST, "/api/encounters/" + table.encounterId + "/combat/cast-spell?actorId="
                + table.sorinId, request, player.accessToken());
    }

    private JsonNode fontOfMagicOnSheet(String direction, int slotLevel) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + sorinCharacterId
                + "/font-of-magic", Map.of("direction", direction, "slotLevel", slotLevel), player.accessToken()));
    }

    private JsonNode dmView(Table table) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + table.encounterId, null,
                dm.accessToken()));
    }

    private static int sheetPoints(JsonNode character) {
        for (JsonNode pool : character.get("resourcePools")) {
            if (pool.get("poolId").asString().equals(SORCERY_POINTS)) {
                return pool.get("currentUses").asInt();
            }
        }
        throw new AssertionError("No sorcery points");
    }

    private static int points(JsonNode participant) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
            if (pool.get("poolId").asString().equals(SORCERY_POINTS)) {
                return pool.get("currentUses").asInt();
            }
        }
        throw new AssertionError("No sorcery points");
    }

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        JsonNode conditions = participant.get("activeConditions");
        if (conditions != null && conditions.isArray()) {
            conditions.forEach(condition -> names.add(condition.get("name").asString()));
        }
        return names;
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
