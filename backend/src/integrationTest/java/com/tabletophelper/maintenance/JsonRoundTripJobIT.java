package com.tabletophelper.maintenance;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The json-roundtrip job (D168): every typed JSON column of a character, an encounter and its participants, a monster,
 * a spell and a feat, all written by the app, reads back as stored; and what it reports for values planted over JDBC
 * that the records don't keep as they are.
 */
class JsonRoundTripJobIT extends IntegrationTest {

    @Autowired private JsonRoundTripJob job;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private FeatRepository featRepository;

    private UUID characterId;
    private UUID monsterId;

    @BeforeEach
    void writeEveryTypedColumn() {
        RegisteredUser dm = register("dungeonmaster");
        RegisteredUser player = register("bram");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        characterId = UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters",
                Map.of("name", "Bram", "raceId", insertRace("Tiefling"), "classId", insertCaster("Warlock", 8, "CHA", true),
                        "strength", 10, "dexterity", 14, "constitution", 12, "intelligence", 10, "wisdom", 10,
                        "charisma", 16, "spellsKnown", List.of(Map.of("name", "Hellish Rebuke", "level", 1,
                                "source", "race:Tiefling", "usesPerLongRest", 1))),
                player.accessToken())).get("id").asString());

        Monster goblin = monsterRepository.save(Monster.builder()
                .name("Goblin").type("Humanoid").challengeRating("1/4").source("MM")
                .hitPoints(7).armourClass(15).dexterity(14).speed("{\"walk\": 30}").senses("{\"darkvision\": 60}")
                .build());
        monsterId = goblin.getId();
        jdbcTemplate.update("UPDATE monsters SET action_templates = '{\"actions\": [{\"name\": \"Scimitar\", "
                + "\"attackBonus\": 4, \"damage\": \"1d6+2\", \"description\": \"Melee Weapon Attack\"}]}' WHERE id = ?",
                monsterId);
        spellRepository.save(Spell.builder().name("Fire Bolt").level(0).source("PHB")
                .components("{\"v\": true, \"s\": true}").classes("[\"Wizard\"]")
                .effectTemplate(SpellTemplate.parse("{\"spellLevel\": 0, \"deliveryMethod\": \"SPELL_ATTACK\", "
                        + "\"effects\": [{\"damageDice\": \"1d10\", \"damageType\": \"fire\"}], \"notes\": \"kept\"}"))
                .build());
        featRepository.save(Feat.builder().name("Grappler").source("PHB").description("You've developed the skills")
                .prerequisite("[{\"ability\": [\"Strength 13\"]}]").build());

        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Bram"));
        addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", monsterId, "displayName", "Goblin"));
        startEncounter(dm.accessToken(), encounterId);
        jdbcTemplate.update("UPDATE encounters SET monsters_in_lair = ?::jsonb WHERE id = ?",
                "[\"" + monsterId + "\"]", encounterId);
    }

    @Test
    @DisplayName("What the app writes reads back unchanged in every typed column, and the raw columns are listed as skipped")
    void cleanRoundTrip() {
        MaintenanceReport report = job.run(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.changes()).isEmpty();
        assertThat(report.examined()).isGreaterThan(15);
        assertThat(report.nextStep()).endsWith(" 0 changed, 0 unreadable.");
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .startsWith("skipped ")
                .contains("monsters.speed", "monsters.actions", "spells.components", "spells.classes",
                        "feats.prerequisite", "feats.effects")
                .doesNotContain("monsters.action_templates", "spells.effect_template"));
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("don't fit the records"));
        // Every typed column of the six tables is checked, the ones this test leaves null included
        assertThat(report.subject()).isEqualTo("typed JSON values in 31 columns");
    }

    @Test
    @DisplayName("An unknown key in a record's column is dropped on reading, so it is reported as a change")
    void unknownKeyInARecordIsReported() {
        jdbcTemplate.update("UPDATE player_characters SET currency = "
                + "'{\"cp\": 0, \"sp\": 0, \"ep\": 0, \"gp\": 5, \"pp\": 0, \"gems\": 3}' WHERE id = ?", characterId);

        MaintenanceReport report = job.run(false);

        assertThat(report.changes()).singleElement().asString()
                .startsWith("changed: player_characters.currency " + characterId + ": stored ")
                .contains("\"gems\": 3")
                .contains(" → reads back as ");
        assertThat(report.nextStep()).endsWith(" 1 changed, 0 unreadable.");
    }

    @Test
    @DisplayName("An unknown key in a monster's action templates is kept, because the templates keep their JSON whole")
    void unknownKeyInTemplatesIsKept() {
        jdbcTemplate.update("UPDATE monsters SET action_templates = action_templates || '{\"reviewNote\": \"check\"}' "
                + "WHERE id = ?", monsterId);

        MaintenanceReport report = job.run(false);

        assertThat(report.changes()).isEmpty();
    }

    @Test
    @DisplayName("A null in a primitive field reads as 0 under today's mapper, so it is reported as a change, not as unreadable")
    void nullInAPrimitiveReadsAsZero() {
        jdbcTemplate.update("UPDATE player_characters SET currency = "
                + "'{\"cp\": 0, \"sp\": 0, \"ep\": 0, \"gp\": null, \"pp\": 0}' WHERE id = ?", characterId);

        MaintenanceReport report = job.run(false);

        assertThat(report.changes()).singleElement().asString()
                .startsWith("changed: player_characters.currency " + characterId + ": stored ")
                .contains("\"gp\": null")
                .contains(" → reads back as ")
                .endsWith("\"gp\": 0, \"pp\": 0, \"sp\": 0}");
    }

    @Test
    @DisplayName("A null field the records write and the stored JSON leaves out is a change, marked as only nulls differing")
    void missingNullFieldIsMarked() {
        // An end rule as V16 and R5 workstream D wrote it, before EndRule had participantId
        UUID participantId = jdbcTemplate.queryForObject("SELECT id FROM encounter_participants WHERE monster_id = ?",
                UUID.class, monsterId);
        jdbcTemplate.update("UPDATE encounter_participants SET active_conditions = '[{\"id\": \"" + UUID.randomUUID()
                + "\", \"name\": \"prone\", \"endRule\": {\"type\": \"MANUAL\"}, \"appliedRound\": 1}]' WHERE id = ?",
                participantId);

        MaintenanceReport report = job.run(false);

        assertThat(report.changes()).singleElement().asString()
                .startsWith("changed: encounter_participants.active_conditions " + participantId
                        + " (only fields that are null differ): stored ")
                .contains("\"participantId\": null");
    }

    @Test
    @DisplayName("A value the record can't read is reported as unreadable, and templates that don't fit their records as a warning")
    void unreadableValuesAreReported() {
        jdbcTemplate.update("UPDATE player_characters SET currency = '{\"gp\": \"lots\"}' WHERE id = ?", characterId);
        jdbcTemplate.update("UPDATE monsters SET action_templates = '{\"actions\": \"Scimitar\"}' WHERE id = ?",
                monsterId);

        MaintenanceReport report = job.run(false);

        assertThat(report.changes()).singleElement().asString()
                .startsWith("unreadable: player_characters.currency " + characterId + ": ")
                .endsWith("stored {\"gp\": \"lots\"}");
        assertThat(report.nextStep()).endsWith(" 0 changed, 1 unreadable.");
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .startsWith("monsters.action_templates " + monsterId + " reads back unchanged, but its parts don't fit"));
    }

    @Test
    @DisplayName("Never writes, so it refuses --maintenance.apply=true")
    void refusesToApply() {
        assertThatThrownBy(() -> job.run(true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never writes");
    }
}
