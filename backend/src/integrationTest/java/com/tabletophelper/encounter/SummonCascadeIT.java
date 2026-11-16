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
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * What a summoned creature leaves on others when it disappears (M15 "cascade companion effects", D182, D203): the
 * druid Aria's Conjure Animals brings a giant constrictor snake, with the bundled definition's Constrict, that grapples
 * and restrains an ogre. When her concentration drops, or the DM dismisses the snake, the grapple ends at once and the
 * restraint tied to it with it (PHB p.290, MM p.324).
 */
class SummonCascadeIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String CONJURE_ANIMALS = """
            {"spellLevel": 3, "concentration": true, "durationRounds": 600, "deliveryMethod": "NONE",
             "effects": [{"effectType": "SUMMON"}], "requiresManualResolution": true,
             "manualResolutionReason": "Summons creatures"}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String ogreId;
    private String snakeId;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        player = register("brea");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Wood Elf"), insertClass("Druid", 8));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        spellRepository.save(Spell.builder().name("Conjure Animals").level(3).source("PHB").castingTime("1 action")
                .effectTemplate(SpellTemplate.parse(CONJURE_ANIMALS)).build());

        Monster snake = Monster.builder().name("Giant Constrictor Snake").source("MM").size("Huge").type("beast")
                .challengeRating("2").hitPoints(60).armourClass(12).strength(19).dexterity(14).constitution(12)
                .intelligence(1).wisdom(10).charisma(3).actionTemplates(templates("Giant Constrictor Snake", "MM"))
                .build();
        monsterRepository.save(snake);
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").source("MM").size("Large")
                .type("giant").challengeRating("2").hitPoints(59).armourClass(11).strength(10).dexterity(10)
                .constitution(10).intelligence(10).wisdom(10).charisma(10).build());

        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", ariaId, "initiative", 18),
                        Map.of("participantId", ogreId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        // Set after the start, which copies each player character's sheet into its participant again (D166)
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(40);
        aria.setHpCurrent(40);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("3", new SpellSlot(3, 0))));
        aria.setSpellsKnown(List.of(
                new KnownSpell(null, "Conjure Animals", 3, "class:Druid", true, null, null, null, null)));
        participantRepository.save(aria);

        // Conjure Animals' one beast of challenge rating 2, on one group roll of 9
        dice.script(9);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ariaId, Map.of("spellName", "Conjure Animals", "slotLevel", 3,
                "targetIds", List.of(), "summon", Map.of("optionId", "1xCR2",
                        "creatures", List.of(Map.of("monsterId", snake.getId(), "count", 1)))), player.accessToken()));
        snakeId = idOf(cast.get("encounterState"), "Giant Constrictor Snake");

        // Constrict: 15 + 6 hits AC 11; 2d8 3 + 3 + 4 = 10 bludgeoning, grappled (escape DC 16) and restrained
        dice.script(15, 3, 3);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", snakeId, "actionName", "Constrict", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ogreId)), dm.accessToken()));
        JsonNode ogreNow = participant(dmView(), ogreId);
        assertThat(ogreNow.get("hpCurrent").asInt()).isEqualTo(49);
        assertThat(conditions(ogreNow)).containsExactlyInAnyOrder("grappled", "restrained");
    }

    @Test
    @DisplayName("Aria's concentration dropping dismisses the snake, and its grapple and the restraint tied to it end")
    void concentrationDrops_endsTheSnakesGrapple() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/concentration",
                Map.of("participantId", ariaId), player.accessToken()));

        JsonNode state = dmView();
        assertThat(state.get("participants").findValuesAsString("id")).doesNotContain(snakeId);
        assertThat(conditions(participant(state, ogreId))).isEmpty();
        assertThat(descriptions(dmLog())).contains("Giant Constrictor Snake disappears (Conjure Animals ends)",
                "grappled ends on Ogre (its grappler is gone)",
                "restrained ends on Ogre (what it lasted while has ended)");
        assertThat(dice.remaining()).isZero();
    }

    @Test
    @DisplayName("The DM dismissing the snake while the spell goes on frees the ogre at once, not at the next turn")
    void dmDismissal_endsTheSnakesGrapple() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/summons/dismiss",
                Map.of("participantIds", List.of(snakeId)), dm.accessToken()));

        JsonNode state = dmView();
        assertThat(conditions(participant(state, ogreId))).isEmpty();
        assertThat(participant(state, ariaId).get("concentrationSpell").asString()).isEqualTo("Conjure Animals");
        assertThat(descriptions(dmLog())).contains("grappled ends on Ogre (its grappler is gone)");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static MonsterTemplates templates(String name, String source) throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                if (name.equals(definition.path("monsterName").asString(""))
                        && source.equals(definition.path("source").asString(""))) {
                    return MonsterTemplates.of(definition);
                }
            }
        }
        throw new AssertionError(name + " has no definition");
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static List<String> conditions(JsonNode participant) {
        List<String> names = new ArrayList<>();
        participant.path("activeConditions").forEach(c -> names.add(c.get("name").asString()));
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

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
