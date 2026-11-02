package com.tabletophelper.maintenance;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The preview-migration dry run (D150, D168), previewing V15 against rows in the shapes it converts.
 * Flyway has already applied V15 to this database, so the tests call {@code preview} directly, past the
 * check that the migration is pending.
 */
class PreviewMigrationJobIT extends IntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository userRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private CombatLogRepository combatLogRepository;

    private Map<String, UUID> participants;

    private PreviewMigrationJob job(String version) {
        return new PreviewMigrationJob(dataSource, jdbcTemplate, entityManagerFactory, objectMapper, version);
    }

    @BeforeEach
    void insertLegacyRows() {
        User dm = userRepository.save(User.builder().username("dm").email("dm@example.com").passwordHash("hash").build());
        Campaign campaign = campaignRepository.save(Campaign.builder().name("Lost Mine").dm(dm).inviteCode("ABCD2345").build());
        Encounter encounter = Encounter.builder().campaign(campaign).name("Ambush").build();
        for (String name : List.of("Lich", "Djinni", "Goblin", "Kobold", "Gimli", "Mimic")) {
            encounter.getParticipants().add(EncounterParticipant.builder()
                    .encounter(encounter).participantType(ParticipantType.MONSTER).displayName(name)
                    .hpMax(10).hpCurrent(10).armourClass(12)
                    .build());
        }
        encounter = encounterRepository.save(encounter);
        participants = encounter.getParticipants().stream()
                .collect(Collectors.toMap(EncounterParticipant::getDisplayName, EncounterParticipant::getId));
        combatLogRepository.save(CombatLog.builder().encounter(encounter).roundNumber(1)
                .actionType(CombatActionType.TURN_ADVANCE).description("Turn passes to Lich (Round 1)").build());

        // The shapes the old code wrote, which the entities can no longer hold
        setColumn("Lich", "spell_slots_current",
                "{\"1\": {\"max\": 4, \"remaining\": 3}, \"pact_2\": {\"max\": 2, \"remaining\": 0}}");
        setColumn("Djinni", "spell_slots_current", "{\"innate\": true, \"source\": \"innate\", \"dailySpellCount\": 3}");
        setColumn("Goblin", "active_conditions", "[\"prone\", \"poisoned\"]");
        // Wrong JSON types: a condition that is neither a name nor an entry, and spells that aren't a list
        setColumn("Kobold", "active_conditions", "[\"prone\", 7, {\"name\": \"poisoned\", \"duration\": 2, "
                + "\"appliedRound\": 3, \"sourceSpellName\": null, \"sourceParticipantId\": null, "
                + "\"sourceRequiresConcentration\": null}]");
        setColumn("Kobold", "spells_known", "{\"name\": \"Shield\", \"level\": 1}");
        // Already in the new shapes
        setColumn("Gimli", "spell_slots_current", "{\"1\": {\"max\": 2, \"used\": 1}}");
        setColumn("Gimli", "active_conditions", "[{\"name\": \"prone\", \"duration\": null, \"appliedRound\": 1, "
                + "\"sourceSpellName\": null, \"sourceParticipantId\": null, \"sourceRequiresConcentration\": null}]");
        // V15 leaves pools alone, and this one can't be read: a use count that isn't a number
        setColumn("Mimic", "resource_pools_current", "[{\"poolId\": \"monster:recharge:bite\", \"displayName\": \"Bite\", "
                + "\"sourceType\": \"MONSTER\", \"maxUses\": \"lots\", \"currentUses\": 1}]");
    }

    private void setColumn(String participant, String column, String json) {
        jdbcTemplate.update("UPDATE encounter_participants SET " + column + " = ?::jsonb WHERE id = ?",
                json, participants.get(participant));
    }

    private String column(String participant, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + "::text FROM encounter_participants WHERE id = ?",
                String.class, participants.get(participant));
    }

    private String change(MaintenanceReport report, String participant) {
        return report.changes().stream()
                .filter(change -> change.contains(participants.get(participant).toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(participant + " is not among the changes: " + report.changes()));
    }

    @Test
    @DisplayName("Reports each legacy row before and after V15, and only those")
    void reportsLegacyRows() {
        MaintenanceReport report = job("").preview(15);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(8);
        assertThat(report.changes()).hasSize(4);
        assertThat(change(report, "Lich"))
                .contains("spell_slots_current: {\"1\":{\"max\":4,\"remaining\":3},\"pact_2\":{\"max\":2,\"remaining\":0}}"
                        + " → {\"1\":{\"max\":4,\"used\":1},\"pact_2\":{\"max\":2,\"used\":2}}");
        assertThat(change(report, "Djinni"))
                .contains("spell_slots_current: {\"innate\":true,\"source\":\"innate\",\"dailySpellCount\":3} → null");
        assertThat(change(report, "Goblin"))
                .contains("active_conditions: [\"prone\",\"poisoned\"] → [{\"name\":\"prone\",\"duration\":null,"
                        + "\"appliedRound\":1,\"sourceSpellName\":null,\"sourceParticipantId\":null,"
                        + "\"sourceRequiresConcentration\":null},{\"name\":\"poisoned\"");
        assertThat(change(report, "Kobold"))
                .contains("→ [{\"name\":\"prone\",\"duration\":null,\"appliedRound\":1,\"sourceSpellName\":null,"
                        + "\"sourceParticipantId\":null,\"sourceRequiresConcentration\":null},"
                        + "{\"name\":\"poisoned\",\"duration\":2,\"appliedRound\":3,")
                .contains("spells_known: {\"name\":\"Shield\",\"level\":1} → null");
        assertThat(report.render()).contains("Examined 8 rows; 4 would change.")
                .contains("Flyway applies V15 at the next normal start");
    }

    @Test
    @DisplayName("Reports each row that doesn't load once converted, and loads the rest")
    void reportsRowsThatDoNotLoad() {
        MaintenanceReport report = job("").preview(15);

        assertThat(report.warnings()).singleElement().asString()
                .contains(participants.get("Mimic").toString()).contains("(Mimic) does not load")
                .contains("lots");
    }

    @Test
    @DisplayName("Writes nothing: every row keeps its legacy shape and Flyway's history is unchanged")
    void writesNothing() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");

        job("").preview(15);

        assertThat(column("Lich", "spell_slots_current"))
                .isEqualTo("{\"1\": {\"max\": 4, \"remaining\": 3}, \"pact_2\": {\"max\": 2, \"remaining\": 0}}");
        assertThat(column("Djinni", "spell_slots_current")).contains("\"innate\": true");
        assertThat(column("Goblin", "active_conditions")).isEqualTo("[\"prone\", \"poisoned\"]");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM combat_logs", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                .isEqualTo(history);
    }

    @Test
    @DisplayName("Refuses --maintenance.apply=true, because Flyway applies the migration itself")
    void refusesToApply() {
        assertThatThrownBy(() -> job("15").run(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Flyway applies the migration at the next normal start");
    }

    @Test
    @DisplayName("Refuses a missing version, and a migration Flyway has already applied")
    void refusesWithoutAPendingMigration() {
        assertThatThrownBy(() -> job("").run(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--maintenance.version=15");
        assertThatThrownBy(() -> job("15").run(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V15 is already applied")
                .hasMessageContaining("--spring.flyway.target=14");
        assertThatThrownBy(() -> job("99").run(false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no migration V99");
    }
}
