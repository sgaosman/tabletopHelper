package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frightened condition end to end (PHB p.290, D201): the source each way of being frightened records, the
 * disadvantage on attack rolls while it is in sight, the DM's out-of-sight mark and its undo, and what each view shows of
 * the fear. Aria, a player's fighter with STR 16 and +2 proficiency, carries a longsword (+5, 1d8 + 3) and goes first;
 * an ogre (AC 11), an Adult Red Dragon with its Frightful Presence and a mage casting Cause Fear as the game data ships
 * it follow.
 */
class FrightenedIT extends IntegrationTest {

    private static final String GREATCLUB = """
            {"actions": [{"name": "Greatclub", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 6, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "2d8 + 4", "damageType": "bludgeoning"}]}]}""";

    private static final String FRIGHTFUL_PRESENCE = """
            {"actions": [{"name": "Frightful Presence", "actionType": "ACTION", "deliveryMethod": "SAVING_THROW",
              "saveDC": 19, "saveAbility": "WIS", "halfOnSave": false,
              "effects": [{"effectType": "CONDITION", "condition": "frightened", "durationText": "1 minute",
                           "durationRounds": 10, "saveToEndEachTurn": true, "saveToEndDC": 19,
                           "saveToEndAbility": "WIS"}]}]}""";

    @Autowired private ItemRepository itemRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;
    private String dragonId;
    private String mageId;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        Map<String, Object> aria = new HashMap<>(Map.of("name", "Aria", "raceId", insertRace("Human"),
                "classId", insertClass("Fighter", 10), "strength", 16, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10));
        aria.put("weaponProficiencies", List.of("simple", "martial"));
        aria.put("campaignId", campaignId);
        UUID character = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters",
                aria, player.accessToken())).get("id").asString());
        itemRepository.save(Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                .damageType("slashing").properties("{\"tags\": [\"V\"], \"versatile_damage\": \"1d10\"}")
                .source("PHB").build());
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character + "/inventory", Map.of(
                "equipment", List.of(Map.of("name", "Longsword", "quantity", 1)),
                "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)),
                player.accessToken()));
        shipped("Cause Fear");

        encounterId = createEncounter(dm.accessToken(), campaignId);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        ogreId = monster("Ogre", "giant", 59, GREATCLUB);
        dragonId = monster("Adult Red Dragon", "dragon", 256, FRIGHTFUL_PRESENCE);
        mageId = monster("Mage", "humanoid", 40, """
                {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 14,
                 "atWillSpells": ["cause fear"]}}""");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 20),
                        Map.of("participantId", ogreId, "initiative", 10),
                        Map.of("participantId", dragonId, "initiative", 8),
                        Map.of("participantId", mageId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Frightened of the dragon by hand: the state names it in sight, the preview and the attack roll "
            + "disadvantage, and the log says so")
    void manualSourceInSight() {
        expect(HttpStatus.OK, addCondition(ariaId, "frightened", dragonId, player.accessToken()));

        JsonNode fear = participant(dmState(), ariaId).get("fears").get(0);
        assertThat(fear.get("sourceId").asString()).isEqualTo(dragonId);
        assertThat(fear.get("sourceName").asString()).isEqualTo("Adult Red Dragon");
        assertThat(fear.get("inSight").asBoolean()).isTrue();
        assertThat(fear.get("sight").asString()).isEqualTo("in sight");
        assertThat(fear.get("markedOutOfSight").asBoolean()).isFalse();
        assertThat(condition(ariaId).get("sourceParticipantId").asString()).isEqualTo(dragonId);

        JsonNode preview = expect(HttpStatus.OK, preview(ariaId, ogreId, player.accessToken()));
        assertThat(preview.toString()).isEqualTo("{\"rollMode\":\"DISADVANTAGE\",\"sources\":[{\"mode\":"
                + "\"DISADVANTAGE\",\"reason\":\"attacker frightened (Adult Red Dragon in sight)\"}],"
                + "\"hitIsCritical\":false}");

        // 14 and 3 with disadvantage: 3 + 5 misses AC 11
        dice.script(14, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/attack?actorId="
                + ariaId, Map.of("targetId", ogreId, "weapon", "Longsword"), player.accessToken()));
        assertThat(log()).contains("Aria gains condition: frightened of Adult Red Dragon",
                "Aria uses Longsword on Ogre (disadvantage: attacker frightened (Adult Red Dragon in sight)): "
                        + "2d20(14, 3) with disadvantage → 3 + 5 = 8 vs AC 11 — miss.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The DM marks the dragon out of sight: no disadvantage, the state says why; undo puts it back in "
            + "sight; a player may not mark it")
    void outOfSightMark() {
        expect(HttpStatus.OK, addCondition(ariaId, "frightened", dragonId, dm.accessToken()));
        String conditionId = condition(ariaId).get("id").asString();

        assertThat(markOutOfSight(ariaId, conditionId, true, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode marked = expect(HttpStatus.OK, markOutOfSight(ariaId, conditionId, true, dm.accessToken()));
        JsonNode fear = participant(marked, ariaId).get("fears").get(0);
        assertThat(fear.get("inSight").asBoolean()).isFalse();
        assertThat(fear.get("markedOutOfSight").asBoolean()).isTrue();
        assertThat(fear.get("sight").asString()).isEqualTo("marked out of sight by the DM");
        assertThat(condition(ariaId).get("sourceOutOfSight").asBoolean()).isTrue();
        assertThat(expect(HttpStatus.OK, preview(ariaId, ogreId, dm.accessToken())).get("sources")).isEmpty();
        assertThat(log()).contains("The DM marks Adult Red Dragon out of Aria's sight: its frightened no longer "
                + "gives disadvantage on attack rolls and ability checks");

        undoNewest();
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("inSight").asBoolean()).isTrue();
        assertThat(condition(ariaId).has("sourceOutOfSight")).isFalse();
        assertThat(expect(HttpStatus.OK, preview(ariaId, ogreId, dm.accessToken())).get("sources")).hasSize(1);
    }

    @Test
    @DisplayName("Frightful Presence records the dragon; an invisible dragon is out of sight, and so is a dead one")
    void monsterActionSource() {
        nextTurn();
        nextTurn();
        // Aria's WIS save: 3 fails against DC 19
        dice.script(3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", dragonId, "actionName", "Frightful Presence", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken()));
        assertThat(condition(ariaId).get("sourceParticipantId").asString()).isEqualTo(dragonId);
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("inSight").asBoolean()).isTrue();

        expect(HttpStatus.OK, addCondition(dragonId, "invisible", null, dm.accessToken()));
        JsonNode fear = participant(dmState(), ariaId).get("fears").get(0);
        assertThat(fear.get("inSight").asBoolean()).isFalse();
        assertThat(fear.get("sight").asString()).isEqualTo("the source is invisible");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/remove",
                Map.of("targetId", dragonId, "condition", "invisible"), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("inSight").asBoolean()).isTrue();

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", dragonId, "amount", 300), dm.accessToken()));
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("sight").asString())
                .isEqualTo("the source isn't there to see");
        assertThat(expect(HttpStatus.OK, preview(ariaId, ogreId, dm.accessToken())).get("sources")).isEmpty();
    }

    @Test
    @DisplayName("A mage's Cause Fear, as the game data ships it, records the mage")
    void spellSource() {
        nextTurn();
        nextTurn();
        nextTurn();
        // Aria's WIS save: 2 fails against DC 14
        dice.script(2);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + mageId, Map.of("spellName", "Cause Fear", "slotLevel", 0,
                "targetIds", List.of(ariaId)), dm.accessToken()));

        assertThat(condition(ariaId).get("sourceParticipantId").asString()).isEqualTo(mageId);
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("sourceName").asString()).isEqualTo("Mage");
    }

    @Test
    @DisplayName("A source is refused on another condition and on the creature itself; a hidden source is named "
            + "neither to players nor through the player's request")
    void refusalsAndPlayerView() {
        assertThat(addCondition(ariaId, "poisoned", dragonId, dm.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(addCondition(ariaId, "frightened", ariaId, dm.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(markOutOfSight(ariaId, UUID.randomUUID().toString(), true, dm.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        expect(HttpStatus.OK, exchange(HttpMethod.PATCH, "/api/encounters/" + encounterId + "/participants/"
                + dragonId + "/visibility", Map.of("visible", false), dm.accessToken()));
        assertThat(addCondition(ariaId, "frightened", dragonId, player.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        expect(HttpStatus.OK, addCondition(ariaId, "frightened", dragonId, dm.accessToken()));

        JsonNode playerFear = participant(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/"
                + encounterId, null, player.accessToken())), ariaId).get("fears").get(0);
        assertThat(playerFear.get("inSight").asBoolean()).isTrue();
        assertThat(playerFear.has("sourceName") && !playerFear.get("sourceName").isNull()).isFalse();
        assertThat(playerFear.has("sourceId") && !playerFear.get("sourceId").isNull()).isFalse();
        assertThat(participant(dmState(), ariaId).get("fears").get(0).get("sourceName").asString())
                .isEqualTo("Adult Red Dragon");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String monster(String name, String type, int hp, String templates) {
        Monster saved = monsterRepository.save(Monster.builder().name(name).type(type).challengeRating("5")
                .source("MM").hitPoints(hp).armourClass(11).dexterity(8).wisdom(10)
                .actionTemplates(MonsterTemplates.parse(templates)).build());
        return idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
    }

    // A spell with its template as the game data ships it
    private void shipped(String name) throws IOException {
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
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private ResponseEntity<String> addCondition(String targetId, String condition, String sourceId, String token) {
        Map<String, Object> request = new HashMap<>(Map.of("targetId", targetId, "condition", condition));
        if (sourceId != null) {
            request.put("sourceParticipantId", sourceId);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/condition/add", request, token);
    }

    private ResponseEntity<String> markOutOfSight(String participantId, String conditionId, boolean outOfSight,
                                                  String token) {
        return exchange(HttpMethod.PUT, "/api/encounters/" + encounterId + "/combat/condition/fear-sight",
                Map.of("participantId", participantId, "conditionId", conditionId, "outOfSight", outOfSight), token);
    }

    private ResponseEntity<String> preview(String attackerId, String targetId, String token) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/attack-roll-preview?attackerId="
                + attackerId + "&targetId=" + targetId + "&withinFiveFeet=true", null, token);
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next",
                null, dm.accessToken()));
    }

    private void undoNewest() {
        String newest = dmState().get("undo").get("commandId").asString();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", newest), dm.accessToken()));
    }

    // The participant's frightened entry
    private JsonNode condition(String participantId) {
        for (JsonNode condition : participant(dmState(), participantId).get("activeConditions")) {
            if ("frightened".equals(condition.get("name").asString())) {
                return condition;
            }
        }
        throw new AssertionError("No frightened entry");
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
