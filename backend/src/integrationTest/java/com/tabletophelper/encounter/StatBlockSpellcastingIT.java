package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
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
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monsters whose spellcasting the definitions lacked until D193, cast from the definitions as the data now gives them:
 * Baba Lysaga (CoS), a 16th-level wizard with slots, and Arkhan the Cruel (BGDIA), whose innate spells are 3/day and
 * 1/day each. Each monster's action templates and each spell's effect template are the bundled data's own.
 */
class StatBlockSpellcastingIT extends IntegrationTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String SPELL_EFFECTS = "data/spell-effects/spell-effect-definitions.json";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String babaId;
    private String arkhanId;

    @BeforeEach
    void setUpTable() throws IOException {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        JsonNode effects = read(SPELL_EFFECTS);
        spell(effects, "Fireball", 3);
        spell(effects, "Branding Smite", 2);
        spell(effects, "Geas", 5);

        JsonNode definitions = read(DEFINITIONS);
        babaId = addMonster(definitions, Monster.builder().name("Baba Lysaga").source("CoS").challengeRating("11")
                .hitPoints(120).armourClass(15).dexterity(10).intelligence(20));
        arkhanId = addMonster(definitions, Monster.builder().name("Arkhan the Cruel").source("BGDIA")
                .challengeRating("16").hitPoints(100).armourClass(22).dexterity(10).charisma(16));

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", babaId, "initiative", 15),
                        Map.of("participantId", arkhanId, "initiative", 10))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("Baba Lysaga joins with her wizard slots and DC 17, and Fireball spends a 3rd-level slot")
    void slottedSpell() {
        JsonNode baba = participant(dmView(), babaId);
        assertThat(baba.get("spellSaveDc").asInt()).isEqualTo(17);
        assertThat(baba.get("spellAttackBonus").asInt()).isEqualTo(9);
        assertThat(baba.get("spellSlotsCurrent").get("3").get("max").asInt()).isEqualTo(3);
        assertThat(baba.get("spellSlotsCurrent").get("8").get("max").asInt()).isEqualTo(1);
        JsonNode fireball = option(expect(HttpStatus.OK, spellOptions(babaId)), "Fireball");
        assertThat(fireball.get("sources").get(0).get("source").asString()).isEqualTo("SLOT");

        // 24 on 8d6, and Arkhan's DEX save of 2 fails against DC 17
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 2);
        JsonNode cast = expect(HttpStatus.OK, castSpell(babaId, Map.of("spellName", "Fireball",
                "spellSource", "SLOT", "slotLevel", 3, "targetIds", List.of(arkhanId))));

        assertThat(cast.get("spellSource").asString()).isEqualTo("SLOT");
        assertThat(cast.get("slotLevelUsed").asInt()).isEqualTo(3);
        JsonNode state = cast.get("encounterState");
        assertThat(participant(state, arkhanId).get("hpCurrent").asInt()).isEqualTo(76);
        assertThat(participant(state, babaId).get("spellSlotsCurrent").get("3").get("used").asInt()).isEqualTo(1);
        assertThat(descriptions(dmLog())).contains("Baba Lysaga uses a level 3 spell slot (2/3 remaining)");
    }

    @Test
    @DisplayName("Arkhan's innate Branding Smite spends one of its three daily uses, with no slot")
    void innateDailySpell() {
        JsonNode arkhan = participant(dmView(), arkhanId);
        assertThat(arkhan.get("spellSaveDc").asInt()).isEqualTo(17);
        assertThat(arkhan.get("spellSlotsCurrent").isNull()).isTrue();
        assertThat(pool(arkhan, "innate:branding-smite").get("currentUses").asInt()).isEqualTo(3);
        assertThat(pool(arkhan, "innate:geas").get("maxUses").asInt()).isEqualTo(1);
        JsonNode smite = option(expect(HttpStatus.OK, spellOptions(arkhanId)), "Branding Smite");
        assertThat(smite.get("sources").get(0).get("source").asString()).isEqualTo("INNATE");
        assertThat(smite.get("sources").get(0).get("usesMax").asInt()).isEqualTo(3);

        JsonNode cast = expect(HttpStatus.OK, castSpell(arkhanId, Map.of("spellName", "Branding Smite",
                "spellSource", "INNATE", "slotLevel", 0, "targetIds", List.of(arkhanId))));

        assertThat(cast.get("spellSource").asString()).isEqualTo("INNATE");
        assertThat(pool(participant(cast.get("encounterState"), arkhanId), "innate:branding-smite")
                .get("currentUses").asInt()).isEqualTo(2);
        assertThat(descriptions(dmLog()))
                .contains("Arkhan the Cruel uses an innate use of Branding Smite (2/3 remaining)");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void spell(JsonNode effects, String name, int level) {
        for (JsonNode effect : effects) {
            if (name.equals(effect.path("spellName").asString(""))) {
                spellRepository.save(Spell.builder().name(name).level(level).source("PHB")
                        .effectTemplate(SpellTemplate.parse(effect.toString())).build());
                return;
            }
        }
        throw new AssertionError(name + " has no effect definition");
    }

    // The monster with the action templates the bundled definitions give it
    private String addMonster(JsonNode definitions, Monster.MonsterBuilder builder) {
        Monster monster = builder.type("Fiend").build();
        for (JsonNode definition : definitions) {
            if (monster.getName().equals(definition.path("monsterName").asString(""))
                    && monster.getSource().equals(definition.path("source").asString(""))) {
                monster.setActionTemplates(MonsterTemplates.of(definition));
                Monster saved = monsterRepository.save(monster);
                return idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                        "monsterId", saved.getId(), "displayName", saved.getName())), saved.getName());
            }
        }
        throw new AssertionError(monster.getName() + " has no definition");
    }

    private static JsonNode read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return JacksonDefaults.mapper().readTree(in);
        }
    }

    private ResponseEntity<String> castSpell(String casterId, Map<String, Object> request) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + casterId,
                request, dm.accessToken());
    }

    private ResponseEntity<String> spellOptions(String participantId) {
        return exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/spell-options?participantId="
                + participantId, null, dm.accessToken());
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode dmLog() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken()));
    }

    private static JsonNode option(JsonNode options, String name) {
        for (JsonNode option : options) {
            if (option.get("name").asString().equals(name)) {
                return option;
            }
        }
        throw new AssertionError(name + " is not among the spell options " + options);
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

    private static JsonNode pool(JsonNode participant, String poolId) {
        for (JsonNode pool : participant.get("resourcePoolsCurrent")) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError(participant.get("displayName").asString() + " has no " + poolId + " pool");
    }

    private static List<String> descriptions(JsonNode log) {
        List<String> descriptions = new ArrayList<>();
        log.forEach(entry -> descriptions.add(entry.get("description").asString()));
        return descriptions;
    }
}
