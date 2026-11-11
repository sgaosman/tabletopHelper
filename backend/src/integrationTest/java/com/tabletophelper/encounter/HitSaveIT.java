package com.tabletophelper.encounter;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A condition a monster's attack gives only on a failed save, end to end. A Dire Wolf, whose seeded bite (MM) records a
 * DC 13 Strength save against prone, bites Aria, a player's character with 40 HP, AC 12, STR 10 and INT 10. A Mind
 * Flayer's seeded tentacles (MM) grapple on a hit and keep their DC 15 Intelligence save against stunning apart from
 * saveDC. A Kraken's bite (MM) is read from the seeded data file itself: its DC 25 Constitution save is the kraken's own,
 * to regurgitate what it swallowed, so Aria takes the piercing damage and rolls nothing; not grappled, she isn't
 * swallowed (D204). The dice are scripted, so
 * each attack hits and Aria's save succeeds or fails as the test needs.
 */
class HitSaveIT extends IntegrationTest {

    // The Dire Wolf's seeded templates (MM), as the local database holds them
    private static final String DIRE_WOLF = """
            {"source": "MM", "traits": [
               {"name": "Keen Hearing and Smell", "description": "The wolf has advantage on Wisdom (Perception) checks that rely on hearing or smell.", "mechanicType": "PERCEPTION_BONUS", "combatRelevant": true},
               {"name": "Pack Tactics", "description": "The wolf has advantage on an attack roll against a creature if at least one of the wolf's allies is within 5 feet of the creature and the ally isn't incapacitated.", "mechanicType": "ADVANTAGE_CONDITION", "combatRelevant": true}],
             "actions": [{"name": "Bite", "reach": 5, "effects": [
                            {"damageDice": "2d6 + 3", "damageType": "piercing", "effectType": "DAMAGE"},
                            {"saveDC": 13, "condition": "prone", "effectType": "CONDITION", "saveAbility": "STR"}],
                          "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                          "deliveryMethod": "ATTACK_ROLL"}],
             "monsterName": "Dire Wolf", "challengeRating": "1"}""";

    // The Mind Flayer's seeded tentacles (MM), as the local database holds them
    private static final String MIND_FLAYER = """
            {"source": "MM",
             "actions": [{"name": "Tentacle", "reach": 5, "effects": [
                            {"damageDice": "2d10+4", "damageType": "psychic", "effectType": "DAMAGE"},
                            {"escapeDC": 15, "condition": "grappled", "effectType": "CONDITION", "durationText": "until escape", "durationRounds": null},
                            {"condition": "stunned", "effectType": "CONDITION"}],
                          "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 7, "targetCount": 1,
                          "deliveryMethod": "ATTACK_ROLL", "secondarySaveDC": 15, "secondarySaveAbility": "INT"}],
             "monsterName": "Mind Flayer", "challengeRating": "7"}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private CharacterRepository characterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String wolfId;
    private String mindFlayerId;
    private String ariaId;
    private String krakenId;
    private UUID ariaCharacterId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        RegisteredUser player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID aria = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Fighter", 10));
        ariaCharacterId = aria;
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));

        Monster wolf = monsterRepository.save(Monster.builder().name("Dire Wolf").type("Beast")
                .challengeRating("1").source("MM").hitPoints(37).armourClass(14)
                .strength(17).dexterity(15).constitution(15).intelligence(3).wisdom(12).charisma(7)
                .actionTemplates(MonsterTemplates.parse(DIRE_WOLF)).build());
        wolfId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", wolf.getId(), "displayName", "Dire Wolf")), "Dire Wolf");
        Monster mindFlayer = monsterRepository.save(Monster.builder().name("Mind Flayer").type("Aberration")
                .challengeRating("7").source("MM").hitPoints(71).armourClass(15)
                .strength(11).dexterity(12).constitution(12).intelligence(19).wisdom(17).charisma(17)
                .actionTemplates(MonsterTemplates.parse(MIND_FLAYER)).build());
        mindFlayerId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", mindFlayer.getId(), "displayName", "Mind Flayer")),
                "Mind Flayer");
        Monster kraken = monsterRepository.save(Monster.builder().name("Kraken").type("Monstrosity")
                .challengeRating("23").source("MM").hitPoints(472).armourClass(18)
                .strength(30).dexterity(11).constitution(25).intelligence(22).wisdom(18).charisma(20)
                .actionTemplates(seeded("Kraken", "MM")).build());
        krakenId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", kraken.getId(), "displayName", "Kraken")), "Kraken");
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria")), "Aria");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", wolfId, "initiative", 20),
                        Map.of("participantId", mindFlayerId, "initiative", 15),
                        Map.of("participantId", krakenId, "initiative", 12),
                        Map.of("participantId", ariaId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant ariaParticipant = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        ariaParticipant.setHpMax(40);
        ariaParticipant.setHpCurrent(40);
        ariaParticipant.setArmourClass(12);
        participantRepository.save(ariaParticipant);
    }

    @Test
    @DisplayName("A bite whose Strength save succeeds deals its damage and leaves Aria standing")
    void saveSucceeds() {
        // 15 + 5 hits AC 12 for 4 + 3 + 3; Aria's STR save of 13 + 0 makes DC 13
        dice.script(15, 4, 3, 13);
        JsonNode result = bite();

        JsonNode aria = participant(dmView(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(30);
        assertThat(conditionNames(aria)).isEmpty();
        assertThat(result.get("targetResults").get(0).get("savedSuccessfully").asBoolean()).isTrue();
        assertThat(result.get("conditionsInflicted").isEmpty()).isTrue();
        assertThat(descriptions(dmLog())).contains("Dire Wolf uses Bite on Aria: d20(15) + 5 = 20 vs AC 12 — hit, "
                + "10 piercing damage, STR save d20(13) + 0 = 13 vs DC 13 succeeds against prone.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A bite whose Strength save fails knocks Aria prone")
    void saveFails() {
        // The same hit; Aria's STR save of 12 + 0 misses DC 13
        dice.script(15, 4, 3, 12);
        JsonNode result = bite();

        JsonNode aria = participant(dmView(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(30);
        assertThat(conditionNames(aria)).containsExactly("prone");
        assertThat(result.get("targetResults").get(0).get("savedSuccessfully").asBoolean()).isFalse();
        assertThat(result.get("targetResults").get(0).get("conditionsApplied").get(0).asString()).isEqualTo("prone");
        assertThat(descriptions(dmLog())).contains("Dire Wolf uses Bite on Aria: d20(15) + 5 = 20 vs AC 12 — hit, "
                + "10 piercing damage, STR save d20(12) + 0 = 12 vs DC 13 fails, gains prone.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A Mind Flayer's tentacles grapple Aria on every hit, and stun her only when her Intelligence save "
            + "fails")
    void mindFlayerGrapplesAndStunsOnAFailedSave() {
        // 15 + 7 hits AC 12 for 5 + 5 + 4; Aria's INT save of 16 + 0 makes DC 15
        dice.script(15, 5, 5, 16);
        tentacle();
        assertThat(conditionNames(participant(dmView(), ariaId))).containsExactly("grappled");

        // The same hit; her save of 4 + 0 misses, and the grapple she already has isn't added again
        dice.script(15, 5, 5, 4);
        tentacle();

        JsonNode aria = participant(dmView(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(12);
        assertThat(conditionNames(aria)).containsExactly("grappled", "stunned");
        assertThat(descriptions(dmLog())).containsSubsequence(
                "Mind Flayer uses Tentacle on Aria: d20(15) + 7 = 22 vs AC 12 — hit, 14 psychic damage, "
                        + "INT save d20(16) + 0 = 16 vs DC 15 succeeds against stunned, gains grappled (escape DC 15).",
                "Mind Flayer uses Tentacle on Aria: d20(15) + 7 = 22 vs AC 12 — hit, 14 psychic damage, "
                        + "INT save d20(4) + 0 = 4 vs DC 15 fails, gains stunned.");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("A Kraken's bite deals its piercing damage, rolls no Constitution save for Aria and swallows only a "
            + "creature it grapples")
    void krakenBiteRollsNoSaveForTheTarget() {
        // 15 + 17 hits AC 12 for 4 + 4 + 4 + 10; nothing else is rolled
        dice.script(15, 4, 4, 4);
        JsonNode result = monsterAction(krakenId, "Bite");

        JsonNode aria = participant(dmView(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(18);
        assertThat(conditionNames(aria)).isEmpty();
        assertThat(result.get("conditionsInflicted").isEmpty()).isTrue();
        assertThat(dice.remaining()).isZero();
        List<String> log = descriptions(dmLog());
        assertThat(log).contains("Kraken uses Bite on Aria: d20(15) + 17 = 32 vs AC 12 — hit, 22 piercing damage.");
        // The bite swallows only a creature the kraken grapples (MM p.197, D204)
        assertThat(log).contains("Aria isn't swallowed by Bite: only a creature it is grappling.");
        assertThat(log).noneMatch(entry -> entry.contains("CON save"));
    }

    @Test
    @DisplayName("Aria, immune to being prone on her sheet, fails the bite's save and stays standing, and the log "
            + "says she is immune")
    void conditionImmunityOnTheSheet() {
        PlayerCharacter character = characterRepository.findById(ariaCharacterId).orElseThrow();
        character.setConditionImmunities(List.of("prone"));
        characterRepository.save(character);

        // The hit of saveFails, whose STR save of 12 + 0 misses DC 13
        dice.script(15, 4, 3, 12);
        bite();

        JsonNode aria = participant(dmView(), ariaId);
        assertThat(aria.get("hpCurrent").asInt()).isEqualTo(30);
        assertThat(conditionNames(aria)).isEmpty();
        assertThat(descriptions(dmLog())).contains("Dire Wolf uses Bite on Aria: d20(15) + 5 = 20 vs AC 12 — hit, "
                + "10 piercing damage, STR save d20(12) + 0 = 12 vs DC 13 fails, immune to prone.");
        assertThat(dice.remaining()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────

    // A monster's templates as the seeded data file holds them
    private MonsterTemplates seeded(String monster, String source) {
        try (InputStream in = new ClassPathResource("data/monster-actions/monster-action-definitions.json")
                .getInputStream()) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (monster.equals(definition.path("monsterName").asString())
                        && source.equals(definition.path("source").asString())) {
                    return MonsterTemplates.of(definition);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("No seeded templates for " + monster + " (" + source + ")");
    }

    private JsonNode bite() {
        return monsterAction(wolfId, "Bite");
    }

    private JsonNode tentacle() {
        return monsterAction(mindFlayerId, "Tentacle");
    }

    private JsonNode monsterAction(String monsterId, String actionName) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", monsterId, "actionName", actionName, "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken()));
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

    private static List<String> conditionNames(JsonNode participant) {
        List<String> names = new ArrayList<>();
        participant.path("activeConditions").forEach(condition -> names.add(condition.get("name").asString()));
        return names;
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
