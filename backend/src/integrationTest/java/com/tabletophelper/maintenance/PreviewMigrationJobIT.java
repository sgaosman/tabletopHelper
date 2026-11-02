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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The preview-migration dry run (D150, D168). V15 is previewed against rows in the shapes it converts, with
 * {@code preview} called directly because Flyway has already applied it to this database. V16 is previewed
 * through {@code run}, pending as it would be in use, against a schema of its own that Flyway takes only to
 * V15, and then applied there, so its backfill is checked row by row (D161, D162).
 */
class PreviewMigrationJobIT extends IntegrationTest {

    private static final String V16_SCHEMA = "v16_preview";

    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository userRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private CombatLogRepository combatLogRepository;
    @Autowired private PostgreSQLContainer<?> postgres;

    private Map<String, UUID> participants;

    private PreviewMigrationJob job(String version) {
        return new PreviewMigrationJob(dataSource, jdbcTemplate, entityManagerFactory, objectMapper, version);
    }

    @AfterEach
    void dropV16Schema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V16_SCHEMA + " CASCADE");
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

    // ── V16: combat pipeline state (D161, D162, D163) ────────────

    /** Takes a schema of its own to V15 and fills it with rows in the shapes V16 converts. */
    private Map<String, UUID> v16Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V16_SCHEMA).locations("classpath:db/migration")
                .target("15").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V16_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V16_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);
        jdbcTemplate.update("INSERT INTO " + V16_SCHEMA + ".encounters (id, name, status, round_number, "
                + "current_turn_index, campaign_id) VALUES (?, 'Ambush', 'ACTIVE', 4, 0, ?)", encounter, campaign);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("Wizard", legacyParticipant(encounter, "Wizard", "PLAYER", 20, 20, 0, true, 0, 0, "Bless", 1,
                "[{\"name\": \"blessed\", \"duration\": 10, \"appliedRound\": 2, \"sourceSpellName\": \"Bless\", "
                        + "\"sourceParticipantId\": null, \"sourceRequiresConcentration\": true}]"));
        rows.put("Goblin", legacyParticipant(encounter, "Goblin", "MONSTER", 7, 0, 0, false, 0, 0, null, null, null));
        rows.put("Fallen", legacyParticipant(encounter, "Fallen", "PLAYER", 30, 0, 0, false, 1, 3, null, null, null));
        rows.put("Steady", legacyParticipant(encounter, "Steady", "PLAYER", 30, 0, 0, false, 3, 1, null, null, null));
        rows.put("Bleeding", legacyParticipant(encounter, "Bleeding", "PLAYER", 30, 0, null, false, 1, 2, null, null,
                "[{\"name\": \"prone\", \"duration\": null, \"appliedRound\": 3, \"sourceSpellName\": null, "
                        + "\"sourceParticipantId\": null, \"sourceRequiresConcentration\": null}]"));
        rows.put("Broken", legacyParticipant(encounter, "Broken", "MONSTER", 0, -3, null, true, null, null, null, null,
                null));
        rows.put("Healthy", legacyParticipant(encounter, "Healthy", "MONSTER", 7, 7, 0, true, 0, 0, null, null, null));
        UUID entry = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V16_SCHEMA + ".combat_logs (id, encounter_id, action_type, description, "
                + "round_number, legendary_resistance_eligible, lr_target_id, lr_damage_dealt, lr_conditions_applied, "
                + "lr_resolved) VALUES (?, ?, 'MONSTER_ACTION', 'Aboleth uses Enslave on Goblin', 2, TRUE, ?, 30, "
                + "'charmed', FALSE)", entry, encounter, rows.get("Goblin"));
        rows.put("entry", entry);
        return rows;
    }

    private UUID legacyParticipant(UUID encounter, String name, String type, int hpMax, int hp, Integer tempHp,
                                   boolean alive, Integer successes, Integer failures, String concentration,
                                   Integer concentrationLevel, String conditions) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V16_SCHEMA + ".encounter_participants (id, display_name, participant_type, "
                        + "hp_max, hp_current, hp_temp, armour_class, is_alive, death_save_successes, "
                        + "death_save_failures, concentration_spell, concentration_slot_level, active_conditions, "
                        + "encounter_id) VALUES (?, ?, ?, ?, ?, ?, 12, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                id, name, type, hpMax, hp, tempHp, alive, successes, failures, concentration, concentrationLevel,
                conditions, encounter);
        return id;
    }

    // Connections that find the V16 schema's tables first, as the app would on a database at V15
    private DataSource v16DataSource() {
        String url = postgres.getJdbcUrl();
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + V16_SCHEMA
                + ",public", postgres.getUsername(), postgres.getPassword());
    }

    private Map<String, Object> v16Row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT hp_max, hp_current, hp_temp, life_state, death_save_successes, "
                + "death_save_failures, concentration::text AS concentration, active_conditions::text AS conditions "
                + "FROM " + V16_SCHEMA + ".encounter_participants WHERE id = ?", id);
    }

    @Test
    @DisplayName("Previews V16 on a database at V15: every participant changes, every row loads, and nothing is written")
    void previewsV16() {
        Map<String, UUID> rows = v16Rows();
        DataSource v16 = v16DataSource();

        MaintenanceReport report = new PreviewMigrationJob(v16, new JdbcTemplate(v16), entityManagerFactory,
                objectMapper, "16").run(false);

        assertThat(report.warnings()).isEmpty();
        assertThat(report.examined()).isEqualTo(9);
        // Seven participants and the log entry, whose new Legendary Resistance columns are empty
        assertThat(report.changes()).hasSize(8);
        String wizard = report.changes().stream().filter(c -> c.contains(rows.get("Wizard").toString()))
                .findFirst().orElseThrow();
        assertThat(wizard).contains("\"spell\":\"Bless\"").contains("\"slotLevel\":1").contains("\"startedRound\":4")
                .containsPattern("life_state: +→ \"ALIVE\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'encounter_participants' AND column_name = 'is_alive'", Integer.class, V16_SCHEMA))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V16 backfills the life state, Concentration Instances and condition IDs, and leaves valid vitals")
    void appliesV16() {
        Map<String, UUID> rows = v16Rows();

        Flyway.configure().dataSource(dataSource).schemas(V16_SCHEMA).locations("classpath:db/migration")
                .target("16").load().migrate();

        Map<String, Object> wizard = v16Row(rows.get("Wizard"));
        assertThat(wizard.get("life_state")).isEqualTo("ALIVE");
        assertThat((String) wizard.get("concentration")).contains("\"spell\": \"Bless\"")
                .contains("\"slotLevel\": 1").contains("\"startedRound\": 4").contains("\"durationRounds\": null")
                .containsPattern("\"id\": \"[0-9a-f-]{36}\"");
        // Conditions from before V16 end only by hand, even a concentration spell's
        assertThat((String) wizard.get("conditions")).containsPattern("\"id\": \"[0-9a-f-]{36}\"")
                .contains("\"endRule\": {\"type\": \"MANUAL\"}").doesNotContain("concentrationInstanceId");

        assertThat(v16Row(rows.get("Goblin")).get("life_state")).isEqualTo("DEAD");
        assertThat(v16Row(rows.get("Fallen")).get("life_state")).isEqualTo("DEAD");
        assertThat(v16Row(rows.get("Healthy")).get("life_state")).isEqualTo("ALIVE");

        Map<String, Object> steady = v16Row(rows.get("Steady"));
        assertThat(steady.get("life_state")).isEqualTo("STABLE");
        assertThat(steady.get("death_save_successes")).isEqualTo(0);
        assertThat(steady.get("death_save_failures")).isEqualTo(0);
        assertThat((String) steady.get("conditions")).contains("\"name\": \"unconscious\"")
                .contains("{\"type\": \"HP_REGAINED\"}").contains("\"name\": \"prone\"");

        Map<String, Object> bleeding = v16Row(rows.get("Bleeding"));
        assertThat(bleeding.get("life_state")).isEqualTo("DYING");
        assertThat(bleeding.get("death_save_successes")).isEqualTo(1);
        assertThat(bleeding.get("death_save_failures")).isEqualTo(2);
        assertThat(bleeding.get("hp_temp")).isEqualTo(0);
        String bleedingConditions = (String) bleeding.get("conditions");
        assertThat(bleedingConditions.indexOf("\"name\": \"prone\"")).isEqualTo(bleedingConditions.lastIndexOf("\"name\": \"prone\""));
        assertThat(bleedingConditions).contains("\"appliedRound\": 3").contains("HP_REGAINED");

        Map<String, Object> broken = v16Row(rows.get("Broken"));
        assertThat(broken.get("hp_max")).isEqualTo(1);
        assertThat(broken.get("hp_current")).isEqualTo(0);
        assertThat(broken.get("hp_temp")).isEqualTo(0);
        assertThat(broken.get("life_state")).isEqualTo("DEAD");

        // Every row now satisfies rules.combat.Vitals
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + V16_SCHEMA + ".encounter_participants "
                + "WHERE hp_max < 1 OR hp_current < 0 OR hp_current > hp_max OR hp_temp < 0 "
                + "OR death_save_successes NOT BETWEEN 0 AND 3 OR death_save_failures NOT BETWEEN 0 AND 3 "
                + "OR (life_state = 'ALIVE') <> (hp_current > 0) "
                + "OR (participant_type <> 'PLAYER' AND life_state IN ('DYING', 'STABLE'))", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'encounter_participants' AND column_name IN ('is_alive', 'concentration_spell', "
                + "'concentration_slot_level')", Integer.class, V16_SCHEMA)).isZero();
        // The old Legendary Resistance entry can't be undone, and the new action types are allowed
        assertThat(jdbcTemplate.queryForObject("SELECT lr_success_damage FROM " + V16_SCHEMA + ".combat_logs WHERE id = ?",
                Integer.class, rows.get("entry"))).isNull();
        jdbcTemplate.update("UPDATE " + V16_SCHEMA + ".combat_logs SET action_type = 'HP_SET' WHERE id = ?",
                rows.get("entry"));
        jdbcTemplate.update("UPDATE " + V16_SCHEMA + ".combat_logs SET action_type = 'REPEAT_SAVE' WHERE id = ?",
                rows.get("entry"));
    }
}
