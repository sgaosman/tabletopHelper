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
 * {@code preview} called directly because Flyway has already applied it to this database. V16 is applied to a
 * schema of its own that Flyway takes only to V15, so its backfill is checked row by row (D161, D162); its
 * preview, which loads the rows with entities now at V17, gave way to V17's. V17 is previewed through
 * {@code run}, pending as it would be in use, against a schema at V16, and then applied there (D163). V19 is applied
 * the same way, against player characters in a schema at V18 (D168), and so is V20, against level histories in a
 * schema at V19 (D167); their previews, which load the characters with entities now at V22, gave way to V21's,
 * applied against characters in a schema at V20 (D158), whose preview in turn gave way to V22's, previewed and applied
 * against characters in a schema at V21 (D158).
 */
class PreviewMigrationJobIT extends IntegrationTest {

    private static final String V16_SCHEMA = "v16_preview";
    private static final String V17_SCHEMA = "v17_preview";
    private static final String V19_SCHEMA = "v19_preview";
    private static final String V20_SCHEMA = "v20_preview";
    private static final String V21_SCHEMA = "v21_preview";
    private static final String V22_SCHEMA = "v22_preview";

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
    void dropSchemas() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V16_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V17_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V19_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V20_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V21_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V22_SCHEMA + " CASCADE");
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

    // Connections that find the schema's tables first, as the app would on a database at its version
    private DataSource schemaDataSource(String schema) {
        String url = postgres.getJdbcUrl();
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
                + ",public", postgres.getUsername(), postgres.getPassword());
    }

    private Map<String, Object> v16Row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT hp_max, hp_current, hp_temp, life_state, death_save_successes, "
                + "death_save_failures, concentration::text AS concentration, active_conditions::text AS conditions "
                + "FROM " + V16_SCHEMA + ".encounter_participants WHERE id = ?", id);
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

    // ── V17: the turn order (D163) ───────────────────────────────

    /**
     * Takes a schema of its own to V16 and fills it with encounters whose turns the old code marked: one with a
     * participant flagged, a paused one with none flagged, and one still being prepared.
     */
    private Map<String, UUID> v17Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V17_SCHEMA).locations("classpath:db/migration")
                .target("16").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V17_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V17_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("Ambush", v16Encounter(campaign, "Ambush", "ACTIVE", 1));
        rows.put("Ogre", v16Participant(rows.get("Ambush"), "Ogre", 0, false));
        rows.put("Goblin", v16Participant(rows.get("Ambush"), "Goblin", 1, true));
        rows.put("Wizard", v16Participant(rows.get("Ambush"), "Wizard", 2, false));
        // Unmarked: the turn index counts in sort order, unsorted participants last
        rows.put("Unmarked", v16Encounter(campaign, "Unmarked", "PAUSED", 2));
        rows.put("Unsorted", v16Participant(rows.get("Unmarked"), "Unsorted", null, false));
        rows.put("First", v16Participant(rows.get("Unmarked"), "First", 0, false));
        rows.put("Second", v16Participant(rows.get("Unmarked"), "Second", 1, false));
        rows.put("Prepared", v16Encounter(campaign, "Prepared", "PREPARING", 0));
        rows.put("Waiting", v16Participant(rows.get("Prepared"), "Waiting", 0, false));
        return rows;
    }

    private UUID v16Encounter(UUID campaign, String name, String status, int turnIndex) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V17_SCHEMA + ".encounters (id, name, status, round_number, "
                + "current_turn_index, campaign_id) VALUES (?, ?, ?, 3, ?, ?)", id, name, status, turnIndex, campaign);
        return id;
    }

    private UUID v16Participant(UUID encounter, String name, Integer sortOrder, boolean currentTurn) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V17_SCHEMA + ".encounter_participants (id, display_name, participant_type, "
                        + "hp_max, hp_current, hp_temp, armour_class, sort_order, is_current_turn, life_state, "
                        + "death_save_successes, death_save_failures, encounter_id) "
                        + "VALUES (?, ?, 'MONSTER', 7, 7, 0, 12, ?, ?, 'ALIVE', 0, 0, ?)",
                id, name, sortOrder, currentTurn, encounter);
        return id;
    }

    private Map<String, Object> v17Encounter(UUID id) {
        return jdbcTemplate.queryForMap("SELECT current_turn_participant_id, is_lair_turn FROM " + V17_SCHEMA
                + ".encounters WHERE id = ?", id);
    }

    private int v17Columns(String table, String... columns) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = ? AND column_name = ANY (?)", Integer.class, V17_SCHEMA, table, columns);
    }

    @Test
    @DisplayName("Previews V17 on a database at V16: every encounter and participant changes, every row loads, nothing is written")
    void previewsV17() {
        Map<String, UUID> rows = v17Rows();
        DataSource v17 = schemaDataSource(V17_SCHEMA);

        MaintenanceReport report = new PreviewMigrationJob(v17, new JdbcTemplate(v17), entityManagerFactory,
                objectMapper, "17").run(false);

        assertThat(report.warnings()).isEmpty();
        assertThat(report.examined()).isEqualTo(10);
        // Three encounters and seven participants, which lose their turn columns and gain the new ones
        assertThat(report.changes()).hasSize(10);
        String ambush = report.changes().stream().filter(c -> c.contains(rows.get("Ambush").toString()))
                .findFirst().orElseThrow();
        assertThat(ambush).containsPattern("current_turn_participant_id: +→ \"" + rows.get("Goblin") + "\"")
                .containsPattern("current_turn_index: 1 → *\n")
                .containsPattern("is_lair_turn: +→ false");
        assertThat(v17Columns("encounter_participants", "is_current_turn")).isEqualTo(1);
        assertThat(v17Columns("encounters", "current_turn_participant_id")).isZero();
    }

    @Test
    @DisplayName("V17 keeps whose turn it was, by flag or else by turn index, and drops the old turn columns")
    void appliesV17() {
        Map<String, UUID> rows = v17Rows();

        Flyway.configure().dataSource(dataSource).schemas(V17_SCHEMA).locations("classpath:db/migration")
                .target("17").load().migrate();

        assertThat(v17Encounter(rows.get("Ambush"))).containsEntry("current_turn_participant_id", rows.get("Goblin"))
                .containsEntry("is_lair_turn", false);
        assertThat(v17Encounter(rows.get("Unmarked")).get("current_turn_participant_id")).isEqualTo(rows.get("Unsorted"));
        assertThat(v17Encounter(rows.get("Prepared")).get("current_turn_participant_id")).isNull();
        assertThat(v17Columns("encounter_participants", "is_current_turn")).isZero();
        assertThat(v17Columns("encounters", "current_turn_index")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT bool_or(death_save_due) FROM " + V17_SCHEMA
                + ".encounter_participants", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT prompt_player_repeat_saves FROM " + V17_SCHEMA
                + ".campaigns", Boolean.class)).isFalse();

        // Never both a participant's turn and the Lair's
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V17_SCHEMA + ".encounters SET is_lair_turn = TRUE "
                + "WHERE id = ?", rows.get("Ambush")))
                .hasMessageContaining("encounters_one_current_turn_check");
        // Deleting the participant whose turn it is leaves no turn rather than failing
        jdbcTemplate.update("DELETE FROM " + V17_SCHEMA + ".encounter_participants WHERE id = ?", rows.get("Goblin"));
        assertThat(v17Encounter(rows.get("Ambush")).get("current_turn_participant_id")).isNull();
    }

    // ── V19: typed character JSON (D168) ─────────────────────────

    /**
     * Takes a schema of its own to V18 and fills it with characters in the shapes V19 converts: spell slots as
     * {total, used}, hit dice maps, equipment stored as names, values of the wrong JSON type, and a character V19
     * leaves alone.
     */
    private Map<String, UUID> v19Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V19_SCHEMA).locations("classpath:db/migration")
                .target("18").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V19_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V19_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        // A used count above the total is capped, and a key that isn't a slot level goes
        rows.put("Wizard", v18Character(owner, race, "Wizard", Map.of(
                "spell_slots", "{\"1\": {\"total\": 4, \"used\": 1}, \"pact_2\": {\"total\": 2, \"used\": 3}, "
                        + "\"innate\": true}",
                "hit_dice_map", "{\"Wizard\": {\"total\": 5, \"remaining\": 3, \"faces\": 6}}")));
        rows.put("Fighter", v18Character(owner, race, "Fighter", Map.of(
                "hit_dice_map", "{\"Fighter\": {\"total\": 3, \"remaining\": 3, \"faces\": 10}, "
                        + "\"Rogue\": {\"total\": 1, \"remaining\": 0, \"faces\": 8}}",
                "equipment", "[\"Longsword\", {\"name\": \"Rope\", \"quantity\": 1}, 7]")));
        rows.put("Broken", v18Character(owner, race, "Broken", Map.of(
                "skill_proficiencies", "\"Athletics\"",
                "currency", "[1, 2]",
                "features", "{\"name\": \"Darkvision\"}")));
        rows.put("Settled", v18Character(owner, race, "Settled", Map.of(
                "skill_proficiencies", "[\"Stealth\"]",
                "currency", "{\"cp\": 0, \"sp\": 0, \"ep\": 0, \"gp\": 15, \"pp\": 0}")));
        return rows;
    }

    private UUID v18Character(UUID owner, UUID race, String name, Map<String, String> jsonColumns) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V19_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, armour_class) "
                + "VALUES (?, ?, ?, ?, 10, 10, 10, 10, 10, 10, 8, 8, 10)", id, owner, race, name);
        jsonColumns.forEach((column, json) -> jdbcTemplate.update("UPDATE " + V19_SCHEMA + ".player_characters SET "
                + column + " = ?::jsonb WHERE id = ?", json, id));
        return id;
    }

    private String v19Column(String column, UUID id) {
        return jdbcTemplate.queryForObject("SELECT " + column + "::text FROM " + V19_SCHEMA
                + ".player_characters WHERE id = ?", String.class, id);
    }

    private String characterChange(MaintenanceReport report, UUID id) {
        return report.changes().stream().filter(change -> change.contains(id.toString())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not among the changes: " + report.changes()));
    }

    @Test
    @DisplayName("V19 types the slots, moves the hit dice to their own column and keeps the deprecated map")
    void appliesV19() {
        Map<String, UUID> rows = v19Rows();

        Flyway.configure().dataSource(dataSource).schemas(V19_SCHEMA).locations("classpath:db/migration")
                .target("19").load().migrate();

        UUID wizard = rows.get("Wizard");
        assertThat(v19Column("spell_slots", wizard))
                .isEqualTo("{\"1\": {\"max\": 4, \"used\": 1}, \"pact_2\": {\"max\": 2, \"used\": 2}}");
        assertThat(v19Column("hit_dice", wizard)).isEqualTo("{\"Wizard\": {\"total\": 5, \"dieSize\": 6, \"remaining\": 3}}");
        assertThat(v19Column("hit_dice_map", wizard)).isEqualTo("{\"Wizard\": {\"faces\": 6, \"total\": 5, \"remaining\": 3}}");
        assertThat(v19Column("hit_dice", rows.get("Fighter")))
                .isEqualTo("{\"Rogue\": {\"total\": 1, \"dieSize\": 8, \"remaining\": 0}, "
                        + "\"Fighter\": {\"total\": 3, \"dieSize\": 10, \"remaining\": 3}}");
        assertThat(v19Column("equipment", rows.get("Fighter")))
                .isEqualTo("[{\"name\": \"Longsword\"}, {\"name\": \"Rope\", \"quantity\": 1}]");
        assertThat(v19Column("skill_proficiencies", rows.get("Broken"))).isNull();
        assertThat(v19Column("currency", rows.get("Broken"))).isNull();
        assertThat(v19Column("features", rows.get("Broken"))).isNull();
        assertThat(v19Column("skill_proficiencies", rows.get("Settled"))).isEqualTo("[\"Stealth\"]");
        assertThat(v19Column("hit_dice", rows.get("Settled"))).isNull();
    }

    // ── V20: hit die results (D167) ──────────────────────────────

    /**
     * Takes a schema of its own to V19 and fills it with level histories as the old code wrote them, without hit die
     * results: a single-class Fighter, a Fighter with a Wizard dip, a level whose class row is gone but whose Hit Dice
     * the sheet still has, a character created without a class, a level that already has a result, and a character
     * without history.
     */
    private Map<String, UUID> v20Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V20_SCHEMA).locations("classpath:db/migration")
                .target("19").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        UUID fighter = UUID.randomUUID();
        UUID wizard = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V20_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V20_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);
        jdbcTemplate.update("INSERT INTO " + V20_SCHEMA + ".character_classes (id, name, hit_dice) VALUES (?, 'Fighter', 10)",
                fighter);
        jdbcTemplate.update("INSERT INTO " + V20_SCHEMA + ".character_classes (id, name, hit_dice) VALUES (?, 'Wizard', 6)",
                wizard);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("Fighter", v19Character(owner, race, "Fighter", "[" + level(1, fighter, "Fighter", 1, 12) + ", "
                + level(2, fighter, "Fighter", 2, 8) + ", " + level(3, fighter, "Fighter", 3, 8) + "]", null));
        rows.put("Dip", v19Character(owner, race, "Dip", "[" + level(1, fighter, "Fighter", 1, 12) + ", "
                + level(2, wizard, "Wizard", 1, 6) + "]", null));
        rows.put("Gone", v19Character(owner, race, "Gone", "[" + level(1, UUID.randomUUID(), "Sorcerer", 1, 8) + ", "
                + level(2, UUID.randomUUID(), "Sorcerer", 2, 6) + "]",
                "{\"Sorcerer\": {\"dieSize\": 6, \"total\": 2, \"remaining\": 2}}"));
        rows.put("Classless", v19Character(owner, race, "Classless",
                "[{\"characterLevel\": 1, \"classLevel\": 1, \"hpGained\": 9, \"featuresGained\": [], \"choices\": {}}]",
                null));
        rows.put("Recorded", v19Character(owner, race, "Recorded", "[{\"characterLevel\": 1, \"classId\": \"" + fighter
                + "\", \"className\": \"Fighter\", \"classLevel\": 1, \"hpGained\": 12, \"hitDieResult\": 10, "
                + "\"featuresGained\": [], \"choices\": {}}]", null));
        rows.put("Empty", v19Character(owner, race, "Empty", null, null));
        return rows;
    }

    private static String level(int characterLevel, UUID classId, String className, int classLevel, int hpGained) {
        return "{\"characterLevel\": " + characterLevel + ", \"classId\": \"" + classId + "\", \"className\": \""
                + className + "\", \"classLevel\": " + classLevel + ", \"hpGained\": " + hpGained
                + ", \"featuresGained\": [], \"choices\": {}}";
    }

    private UUID v19Character(UUID owner, UUID race, String name, String levelHistory, String hitDice) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V20_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, armour_class, "
                + "level_history, hit_dice) VALUES (?, ?, ?, ?, 10, 10, 14, 10, 10, 10, 8, 8, 10, ?::jsonb, ?::jsonb)",
                id, owner, race, name, levelHistory, hitDice);
        return id;
    }

    private List<Integer> hitDieResults(UUID id) {
        return jdbcTemplate.queryForList("SELECT (e ->> 'hitDieResult')::int FROM " + V20_SCHEMA
                + ".player_characters, jsonb_array_elements(level_history) e WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("V20 gives each level the die's maximum at character level 1 and half the die plus 1 after, as calculateHpGain did")
    void appliesV20() {
        Map<String, UUID> rows = v20Rows();

        Flyway.configure().dataSource(dataSource).schemas(V20_SCHEMA).locations("classpath:db/migration")
                .target("20").load().migrate();

        assertThat(hitDieResults(rows.get("Fighter"))).containsExactly(10, 6, 6);
        // A dip's first level isn't character level 1, so it has the average (PHB p.163)
        assertThat(hitDieResults(rows.get("Dip"))).containsExactly(10, 4);
        // A class row that is gone leaves the sheet's own Hit Dice, and no class at all the d8 the old code used
        assertThat(hitDieResults(rows.get("Gone"))).containsExactly(6, 4);
        assertThat(hitDieResults(rows.get("Classless"))).containsExactly(8);
        assertThat(hitDieResults(rows.get("Recorded"))).containsExactly(10);
        assertThat(jdbcTemplate.queryForObject("SELECT level_history IS NULL FROM " + V20_SCHEMA
                + ".player_characters WHERE id = ?", Boolean.class, rows.get("Empty"))).isTrue();
        // Everything else in an entry is kept
        assertThat(jdbcTemplate.queryForObject("SELECT level_history -> 1 ->> 'hpGained' FROM " + V20_SCHEMA
                + ".player_characters WHERE id = ?", String.class, rows.get("Fighter"))).isEqualTo("8");
    }

    // ── V21: character version (D158) ────────────────────────────

    /**
     * Takes a schema of its own to V20 and fills it with characters as R6 workstream C left them: a Fighter with its
     * level history and Hit Dice, and a character with neither.
     */
    private Map<String, UUID> v21Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V21_SCHEMA).locations("classpath:db/migration")
                .target("20").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        UUID fighter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V21_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V21_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);
        jdbcTemplate.update("INSERT INTO " + V21_SCHEMA + ".character_classes (id, name, hit_dice) VALUES (?, 'Fighter', 10)",
                fighter);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String name : List.of("Fighter", "Empty")) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V21_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                    + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, armour_class) "
                    + "VALUES (?, ?, ?, ?, 10, 10, 14, 10, 10, 10, 12, 12, 10)", id, owner, race, name);
            rows.put(name, id);
        }
        jdbcTemplate.update("UPDATE " + V21_SCHEMA + ".player_characters SET level_history = ?::jsonb, "
                + "hit_dice = '{\"Fighter\": {\"dieSize\": 10, \"total\": 1, \"remaining\": 1}}' WHERE id = ?",
                "[{\"characterLevel\": 1, \"classId\": \"" + fighter + "\", \"className\": \"Fighter\", "
                        + "\"classLevel\": 1, \"hpGained\": 12, \"hitDieResult\": 10, \"featuresGained\": [], "
                        + "\"choices\": {}}]", rows.get("Fighter"));
        return rows;
    }

    @Test
    @DisplayName("V21 starts every character at version 0 and leaves the rest of the row as it was")
    void appliesV21() {
        Map<String, UUID> rows = v21Rows();

        Flyway.configure().dataSource(dataSource).schemas(V21_SCHEMA).locations("classpath:db/migration")
                .target("21").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT version FROM " + V21_SCHEMA + ".player_characters ORDER BY name",
                Long.class)).containsExactly(0L, 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT level_history -> 0 ->> 'hitDieResult' FROM " + V21_SCHEMA
                + ".player_characters WHERE id = ?", String.class, rows.get("Fighter"))).isEqualTo("10");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V21_SCHEMA + ".player_characters SET version = NULL "
                + "WHERE id = ?", rows.get("Empty")))
                .hasMessageContaining("version");
    }

    // ── V22: DM Adjustments (D158) ───────────────────────────────

    /**
     * Takes a schema of its own to V21 and fills it with characters as R6 workstream D left them: a Fighter with its
     * level history, Hit Dice and a version moved on by its commands, and a character with neither.
     */
    private Map<String, UUID> v22Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V22_SCHEMA).locations("classpath:db/migration")
                .target("21").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        UUID fighter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V22_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V22_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);
        jdbcTemplate.update("INSERT INTO " + V22_SCHEMA + ".character_classes (id, name, hit_dice) VALUES (?, 'Fighter', 10)",
                fighter);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String name : List.of("Fighter", "Empty")) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V22_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                    + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, armour_class) "
                    + "VALUES (?, ?, ?, ?, 10, 10, 14, 10, 10, 10, 12, 12, 10)", id, owner, race, name);
            rows.put(name, id);
        }
        jdbcTemplate.update("UPDATE " + V22_SCHEMA + ".player_characters SET level_history = ?::jsonb, "
                + "hit_dice = '{\"Fighter\": {\"dieSize\": 10, \"total\": 1, \"remaining\": 1}}', version = 4 "
                + "WHERE id = ?",
                "[{\"characterLevel\": 1, \"classId\": \"" + fighter + "\", \"className\": \"Fighter\", "
                        + "\"classLevel\": 1, \"hpGained\": 12, \"hitDieResult\": 10, \"featuresGained\": [], "
                        + "\"choices\": {}}]", rows.get("Fighter"));
        return rows;
    }

    private Integer v22AdjustmentColumns() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'player_characters' AND column_name = 'dm_adjustments'", Integer.class, V22_SCHEMA);
    }

    @Test
    @DisplayName("Previews V22 on a database at V21: every character gains an empty list of DM Adjustments, every row loads, nothing is written")
    void previewsV22() {
        Map<String, UUID> rows = v22Rows();
        DataSource v22 = schemaDataSource(V22_SCHEMA);

        MaintenanceReport report = new PreviewMigrationJob(v22, new JdbcTemplate(v22), entityManagerFactory,
                objectMapper, "22").run(false);

        assertThat(report.warnings()).isEmpty();
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.changes()).hasSize(2);
        assertThat(characterChange(report, rows.get("Fighter"))).contains("(Fighter)")
                .containsPattern("dm_adjustments: +→ \\[]");
        assertThat(characterChange(report, rows.get("Empty"))).containsPattern("dm_adjustments: +→ \\[]");
        assertThat(v22AdjustmentColumns()).isZero();
    }

    @Test
    @DisplayName("V22 gives every character an empty list of DM Adjustments, never null, and leaves the rest of the row as it was")
    void appliesV22() {
        Map<String, UUID> rows = v22Rows();

        Flyway.configure().dataSource(dataSource).schemas(V22_SCHEMA).locations("classpath:db/migration")
                .target("22").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT dm_adjustments::text FROM " + V22_SCHEMA
                + ".player_characters ORDER BY name", String.class)).containsExactly("[]", "[]");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM " + V22_SCHEMA
                + ".player_characters WHERE id = ?", Long.class, rows.get("Fighter"))).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("SELECT level_history -> 0 ->> 'hitDieResult' FROM " + V22_SCHEMA
                + ".player_characters WHERE id = ?", String.class, rows.get("Fighter"))).isEqualTo("10");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V22_SCHEMA + ".player_characters "
                + "SET dm_adjustments = NULL WHERE id = ?", rows.get("Empty")))
                .hasMessageContaining("dm_adjustments");
    }
}
