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
import org.testcontainers.postgresql.PostgreSQLContainer;

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
 * preview, which loads the rows with entities now at V17, gave way to V17's. V17 is applied to a schema at V16
 * (D163); its preview through {@code run}, pending as it would be in use, gave way to V24's once the encounter
 * entities mapped V24's columns. V19 is applied
 * the same way, against player characters in a schema at V18 (D168), and so is V20, against level histories in a
 * schema at V19 (D167); their previews, which load the characters with entities now at V23, gave way to V21's,
 * applied against characters in a schema at V20 (D158), whose preview in turn gave way to V22's, applied against
 * characters in a schema at V21 (D158), and V22's preview to V23's, applied against characters in a schema at V22
 * (D159), whose preview in turn gave way to V24's, applied against encounters and characters in a schema at V23
 * (D166); V24's preview, which loads participants with entities now at V30, gave way to V30's. V29, which drops
 * columns, is applied against characters in a schema at V28; its preview, which listed each value a row loses and
 * loads characters with entities now at V35, gave way to V35's. V30 is applied against participants of an Active and a Paused encounter in a schema at V29 (D174); its
 * preview, which loads participants with entities now at V33, gave way to V33's, applied against participants with an
 * active spell and concentration spells in a schema at V32 (M15); its preview, which loads participants with entities
 * now at V34, gave way to V34's, applied against participants with Spell Effects in a schema at V33 (D182); its
 * preview, which loads participants with entities now at V35, gave way to V35's, applied against a character and its
 * participants in a schema at V34 (D183); its preview, which loads participants with entities now at V36, gave way to
 * V36's, previewed and applied against a rakshasa and a goblin in a schema at V35 (D198).
 */
class PreviewMigrationJobIT extends IntegrationTest {

    private static final String V16_SCHEMA = "v16_preview";
    private static final String V17_SCHEMA = "v17_preview";
    private static final String V19_SCHEMA = "v19_preview";
    private static final String V20_SCHEMA = "v20_preview";
    private static final String V21_SCHEMA = "v21_preview";
    private static final String V22_SCHEMA = "v22_preview";
    private static final String V23_SCHEMA = "v23_preview";
    private static final String V24_SCHEMA = "v24_preview";
    private static final String V29_SCHEMA = "v29_preview";
    private static final String V30_SCHEMA = "v30_preview";
    private static final String V33_SCHEMA = "v33_preview";
    private static final String V34_SCHEMA = "v34_preview";
    private static final String V35_SCHEMA = "v35_preview";
    private static final String V36_SCHEMA = "v36_preview";

    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository userRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private CombatLogRepository combatLogRepository;
    @Autowired private PostgreSQLContainer postgres;

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
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V23_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V24_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V29_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V30_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V33_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V34_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V35_SCHEMA + " CASCADE");
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + V36_SCHEMA + " CASCADE");
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

    // ── V23: Pending Choices (D159) ──────────────────────────────

    /**
     * Takes a schema of its own to V22 and fills it with characters as R6 workstream E left them: a Fighter 4 with its
     * level history, a DM Adjustment and a version moved on by its commands, and a character with neither.
     */
    private Map<String, UUID> v23Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V23_SCHEMA).locations("classpath:db/migration")
                .target("22").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        UUID fighter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V23_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V23_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);
        jdbcTemplate.update("INSERT INTO " + V23_SCHEMA + ".character_classes (id, name, hit_dice) VALUES (?, 'Fighter', 10)",
                fighter);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String name : List.of("Fighter", "Empty")) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V23_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                    + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, armour_class) "
                    + "VALUES (?, ?, ?, ?, 10, 10, 14, 10, 10, 10, 12, 12, 10)", id, owner, race, name);
            rows.put(name, id);
        }
        StringBuilder history = new StringBuilder("[");
        for (int level = 1; level <= 4; level++) {
            history.append(level > 1 ? ", " : "").append("{\"characterLevel\": ").append(level)
                    .append(", \"classId\": \"").append(fighter).append("\", \"className\": \"Fighter\", ")
                    .append("\"classLevel\": ").append(level).append(", \"hpGained\": ").append(level == 1 ? 12 : 8)
                    .append(", \"hitDieResult\": ").append(level == 1 ? 10 : 6).append(", \"featuresGained\": [], ")
                    .append("\"choices\": {}}");
        }
        history.append("]");
        jdbcTemplate.update("UPDATE " + V23_SCHEMA + ".player_characters SET level = 4, level_history = ?::jsonb, "
                + "hit_dice = '{\"Fighter\": {\"dieSize\": 10, \"total\": 4, \"remaining\": 4}}', version = 6, "
                + "dm_adjustments = '[{\"id\": \"" + UUID.randomUUID() + "\", \"kind\": \"STAT\", "
                + "\"target\": \"ARMOUR_CLASS\", \"amount\": 1, \"reason\": \"Ring of Protection\", "
                + "\"addedBy\": \"" + owner + "\", \"addedAt\": \"2026-11-03T10:00:00Z\"}]' WHERE id = ?",
                history.toString(), rows.get("Fighter"));
        return rows;
    }

    @Test
    @DisplayName("V23 gives every character an empty list of Pending Choices, never null, and leaves the rest of the row as it was")
    void appliesV23() {
        Map<String, UUID> rows = v23Rows();

        Flyway.configure().dataSource(dataSource).schemas(V23_SCHEMA).locations("classpath:db/migration")
                .target("23").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT pending_choices::text FROM " + V23_SCHEMA
                + ".player_characters ORDER BY name", String.class)).containsExactly("[]", "[]");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM " + V23_SCHEMA
                + ".player_characters WHERE id = ?", Long.class, rows.get("Fighter"))).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForObject("SELECT dm_adjustments -> 0 ->> 'reason' FROM " + V23_SCHEMA
                + ".player_characters WHERE id = ?", String.class, rows.get("Fighter"))).isEqualTo("Ring of Protection");
        assertThat(jdbcTemplate.queryForObject("SELECT jsonb_array_length(level_history) FROM " + V23_SCHEMA
                + ".player_characters WHERE id = ?", Integer.class, rows.get("Fighter"))).isEqualTo(4);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V23_SCHEMA + ".player_characters "
                + "SET pending_choices = NULL WHERE id = ?", rows.get("Empty")))
                .hasMessageContaining("pending_choices");
    }

    // ── V24: Write-back (D166) ───────────────────────────────────

    /**
     * Takes a schema of its own to V23 and fills it as R6 workstream F left it: Aria, at 30 of 38 hit points with 5
     * temporary, a level 1 slot spent, pact slots spent past their maximum and Rage held twice, the first with 1 use
     * left, in an Active, a Paused, a Preparing and a Completed encounter; and Bram, whose hit points stand above his
     * maximum, with no slots or pools, beside a goblin in the Active one.
     */
    private Map<String, UUID> v24Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V24_SCHEMA).locations("classpath:db/migration")
                .target("23").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, owner);
        jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("Aria", character(owner, race, "Aria", 38, 30, 5,
                "{\"1\": {\"max\": 4, \"used\": 1}, \"pact_1\": {\"max\": 2, \"used\": 3}}",
                "[" + rage(1) + ", " + rage(0) + "]"));
        rows.put("Bram", character(owner, race, "Bram", 12, 15, 0, null, null));
        for (String status : List.of("ACTIVE", "PAUSED", "PREPARING", "COMPLETED")) {
            UUID encounter = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".encounters (id, name, status, campaign_id) "
                    + "VALUES (?, ?, ?, ?)", encounter, status.toLowerCase(), status, campaign);
            rows.put(status, encounter);
            rows.put("Aria " + status, participant(encounter, "Aria", "PLAYER", rows.get("Aria")));
        }
        rows.put("Bram ACTIVE", participant(rows.get("ACTIVE"), "Bram", "PLAYER", rows.get("Bram")));
        rows.put("Goblin ACTIVE", participant(rows.get("ACTIVE"), "Goblin", "MONSTER", null));
        return rows;
    }

    private UUID character(UUID owner, UUID race, String name, int hpMax, int hp, int tempHp, String slots,
                           String pools) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, hp_temp, armour_class, "
                + "spell_slots, resource_pools) VALUES (?, ?, ?, ?, 10, 10, 14, 10, 10, 10, ?, ?, ?, 10, ?::jsonb, "
                + "?::jsonb)", id, owner, race, name, hpMax, hp, tempHp, slots, pools);
        return id;
    }

    private static String rage(int currentUses) {
        return "{\"poolId\": \"class:barbarian-rage\", \"displayName\": \"Rage\", \"sourceType\": \"CLASS\", "
                + "\"maxUses\": 2, \"currentUses\": " + currentUses + ", \"resetOn\": \"longRest\"}";
    }

    private UUID participant(UUID encounter, String name, String type, UUID character) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V24_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id, character_id) "
                + "VALUES (?, ?, ?, 20, 7, 12, 'ALIVE', ?, ?)", id, name, type, encounter, character);
        return id;
    }

    private String v24JoinedWith(UUID participant) {
        return jdbcTemplate.queryForObject("SELECT joined_with::text FROM " + V24_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, participant);
    }

    @Test
    @DisplayName("V24 gives player participants of Active and Paused encounters what they joined with from their sheets, and nobody else, and leaves the rest as it was")
    void appliesV24() {
        Map<String, UUID> rows = v24Rows();

        Flyway.configure().dataSource(dataSource).schemas(V24_SCHEMA).locations("classpath:db/migration")
                .target("24").load().migrate();

        String aria = "{\"hpTemp\": 5, \"hpCurrent\": 30, \"slotsUsed\": {\"1\": 1, \"pact_1\": 2}, "
                + "\"poolsCurrentUses\": {\"class:barbarian-rage\": 1}}";
        assertThat(v24JoinedWith(rows.get("Aria ACTIVE"))).isEqualTo(aria);
        assertThat(v24JoinedWith(rows.get("Aria PAUSED"))).isEqualTo(aria);
        assertThat(v24JoinedWith(rows.get("Bram ACTIVE")))
                .isEqualTo("{\"hpTemp\": 0, \"hpCurrent\": 12, \"slotsUsed\": {}, \"poolsCurrentUses\": {}}");
        assertThat(v24JoinedWith(rows.get("Aria PREPARING"))).isNull();
        assertThat(v24JoinedWith(rows.get("Aria COMPLETED"))).isNull();
        assertThat(v24JoinedWith(rows.get("Goblin ACTIVE"))).isNull();
        // The participant's own totals stay as combat left them
        assertThat(jdbcTemplate.queryForObject("SELECT hp_current FROM " + V24_SCHEMA
                + ".encounter_participants WHERE id = ?", Integer.class, rows.get("Aria ACTIVE"))).isEqualTo(7);
        assertThat(jdbcTemplate.queryForList("SELECT results_applied FROM " + V24_SCHEMA + ".encounters",
                Boolean.class)).hasSize(4).containsOnlyNulls();
        assertThat(jdbcTemplate.queryForList("SELECT is_dead FROM " + V24_SCHEMA + ".player_characters ORDER BY name",
                Boolean.class)).containsExactly(false, false);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V24_SCHEMA + ".player_characters "
                + "SET is_dead = NULL WHERE id = ?", rows.get("Bram")))
                .hasMessageContaining("is_dead");
    }

    // ── V29: the deprecated character columns dropped ────────────

    private static final List<String> V29_COLUMNS = List.of("hit_dice_total", "hit_dice_remaining", "hit_dice_map",
            "feat_resources", "prepared_spells", "attuned_items", "equipped_items");

    /**
     * Takes a schema of its own to V28 and fills it as R7 workstream F left it: Aria, whose deprecated hit dice columns
     * still hold what V19 copied into hit_dice, and Bram, whose deprecated columns are all NULL.
     */
    private Map<String, UUID> v29Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V29_SCHEMA).locations("classpath:db/migration")
                .target("28").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V29_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V29_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String name : List.of("Aria", "Bram")) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V29_SCHEMA + ".player_characters (id, user_id, race_id, name, "
                    + "strength, dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, "
                    + "armour_class) VALUES (?, ?, ?, ?, 10, 10, 10, 10, 10, 10, 8, 8, 10)", id, owner, race, name);
            rows.put(name, id);
        }
        jdbcTemplate.update("UPDATE " + V29_SCHEMA + ".player_characters SET hit_dice_total = '5d6', "
                + "hit_dice_remaining = '3d6', hit_dice_map = '{\"Wizard\": {\"total\": 5, \"remaining\": 3, "
                + "\"faces\": 6}}', hit_dice = '{\"Wizard\": {\"total\": 5, \"dieSize\": 6, \"remaining\": 3}}' "
                + "WHERE id = ?", rows.get("Aria"));
        return rows;
    }

    private List<String> v29Columns() {
        return jdbcTemplate.queryForList("SELECT column_name::text FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'player_characters' AND column_name = ANY (?) "
                + "ORDER BY column_name", String.class, V29_SCHEMA, V29_COLUMNS.toArray(String[]::new));
    }

    @Test
    @DisplayName("V29 drops the seven deprecated columns and leaves hit_dice as it was")
    void appliesV29() {
        Map<String, UUID> rows = v29Rows();

        Flyway.configure().dataSource(dataSource).schemas(V29_SCHEMA).locations("classpath:db/migration")
                .target("29").load().migrate();

        assertThat(v29Columns()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT hit_dice::text FROM " + V29_SCHEMA
                + ".player_characters WHERE id = ?", String.class, rows.get("Aria")))
                .isEqualTo("{\"Wizard\": {\"total\": 5, \"dieSize\": 6, \"remaining\": 3}}");
    }

    // ── V30: the turn economy (D174) ─────────────────────────────

    /**
     * Takes a schema of its own to V29 and fills it as M13 found it: Aria and a goblin in an Active encounter, Aria's
     * three action flags clear and the goblin's action and reaction set, and an ogre in a Paused one with its bonus
     * action set.
     */
    private Map<String, UUID> v30Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V30_SCHEMA).locations("classpath:db/migration")
                .target("29").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String status : List.of("ACTIVE", "PAUSED")) {
            UUID encounter = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".encounters (id, name, status, campaign_id) "
                    + "VALUES (?, ?, ?, ?)", encounter, status.toLowerCase(), status, campaign);
            rows.put(status, encounter);
        }
        rows.put("Aria", v30Participant(rows.get("ACTIVE"), "Aria", "PLAYER", false, false, false));
        rows.put("Goblin", v30Participant(rows.get("ACTIVE"), "Goblin", "MONSTER", true, false, true));
        rows.put("Ogre", v30Participant(rows.get("PAUSED"), "Ogre", "MONSTER", false, true, false));
        return rows;
    }

    private UUID v30Participant(UUID encounter, String name, String type, boolean action, boolean bonusAction,
                                boolean reaction) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id, action_used, "
                + "bonus_action_used, reaction_used) VALUES (?, ?, ?, 20, 7, 12, 'ALIVE', ?, ?, ?, ?)",
                id, name, type, encounter, action, bonusAction, reaction);
        return id;
    }

    // A participant inserted after V30, which names none of the dropped columns
    private UUID v30NewParticipant(UUID encounter, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id) "
                + "VALUES (?, ?, 'PLAYER', 20, 7, 12, 'ALIVE', ?)", id, name, encounter);
        return id;
    }

    private String v30TurnEconomy(UUID participant) {
        return jdbcTemplate.queryForObject("SELECT turn_economy::text FROM " + V30_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, participant);
    }

    // The fresh turn economy as the preview prints it: jsonb's key order, without spaces
    private static final String FRESH_ECONOMY = "{\"dashed\":false,\"actions\":1,\"disengaged\":false,"
            + "\"actionsUsed\":0,\"attacksLeft\":0,\"reactionUsed\":false,\"otherSpellCast\":false,"
            + "\"bonusActionUsed\":false,\"attackActionTaken\":false,\"bonusActionSpellCast\":false,"
            + "\"objectInteractionUsed\":false}";

    @Test
    @DisplayName("V30 gives every participant a turn economy keeping its flags, drops the flags, defaults new rows to the fresh record and widens the combat log's types")
    void appliesV30() {
        Map<String, UUID> rows = v30Rows();

        Flyway.configure().dataSource(dataSource).schemas(V30_SCHEMA).locations("classpath:db/migration")
                .target("30").load().migrate();

        String fresh = FRESH_ECONOMY.replace(":", ": ").replace(",", ", ");
        assertThat(v30TurnEconomy(rows.get("Aria"))).isEqualTo(fresh);
        assertThat(v30TurnEconomy(rows.get("Goblin")))
                .isEqualTo(fresh.replace("\"actionsUsed\": 0", "\"actionsUsed\": 1")
                        .replace("\"reactionUsed\": false", "\"reactionUsed\": true"));
        assertThat(v30TurnEconomy(rows.get("Ogre")))
                .isEqualTo(fresh.replace("\"bonusActionUsed\": false", "\"bonusActionUsed\": true"));
        assertThat(v30TurnEconomy(v30NewParticipant(rows.get("ACTIVE"), "Bram"))).isEqualTo(fresh);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND column_name IN ('action_used', 'bonus_action_used', 'reaction_used')", Integer.class, V30_SCHEMA))
                .isZero();
        // The participant's other columns stay as they were
        assertThat(jdbcTemplate.queryForObject("SELECT hp_current FROM " + V30_SCHEMA
                + ".encounter_participants WHERE id = ?", Integer.class, rows.get("Aria"))).isEqualTo(7);
        for (String type : List.of("POOL_USE", "STANDARD_ACTION", "OBJECT_INTERACTION", "TURN_ECONOMY_SET",
                "REACTION_TAKEN", "REPEAT_SAVE")) {
            jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".combat_logs (id, encounter_id, action_type, "
                    + "description, round_number) VALUES (?, ?, ?, 'logged', 1)", UUID.randomUUID(),
                    rows.get("ACTIVE"), type);
        }
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO " + V30_SCHEMA + ".combat_logs (id, encounter_id, "
                + "action_type, description, round_number) VALUES (?, ?, 'DASH', 'logged', 1)", UUID.randomUUID(),
                rows.get("ACTIVE"))).hasMessageContaining("combat_logs_action_type_check");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V30_SCHEMA + ".encounter_participants "
                + "SET turn_economy = NULL WHERE id = ?", rows.get("Aria"))).hasMessageContaining("turn_economy");
    }

    // ── V33: Spell Effects (M15) ─────────────────────────────────

    /**
     * Takes a schema of its own to V32 and fills it as M15 found it, in an encounter in round 4: a cleric with Spiritual
     * Weapon as its active spell, cast with a 3rd-level slot, and concentrating on Moonbeam; a wizard concentrating on
     * Melf's Minute Meteors cast with a 5th-level slot; a bard concentrating on Bless, which has no lasting effect; and
     * a goblin with neither.
     */
    private Map<String, UUID> v33Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V33_SCHEMA).locations("classpath:db/migration")
                .target("32").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V33_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V33_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);
        jdbcTemplate.update("INSERT INTO " + V33_SCHEMA + ".encounters (id, name, status, campaign_id, round_number) "
                + "VALUES (?, 'Crypt', 'ACTIVE', ?, 4)", encounter, campaign);

        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("Cleric", v33Participant(encounter, "Cleric", "Spiritual Weapon", 3, concentration(MOONBEAM_ID,
                "Moonbeam", 2, 3, 10)));
        rows.put("Wizard", v33Participant(encounter, "Wizard", null, null, concentration(METEORS_ID,
                "Melf's Minute Meteors", 5, 2, 100)));
        rows.put("Bard", v33Participant(encounter, "Bard", null, null, concentration(UUID.randomUUID(), "Bless", 1, 4,
                10)));
        rows.put("Goblin", v33Participant(encounter, "Goblin", null, null, null));
        return rows;
    }

    private static final UUID MOONBEAM_ID = UUID.fromString("0f6b1f8e-1d2c-4c1a-9a51-6c1f0e2d3a4b");
    private static final UUID METEORS_ID = UUID.fromString("7a2e4c9d-5b3f-4e8a-8c1d-2f6a9b0e1c3d");

    private static String concentration(UUID id, String spell, int slotLevel, int startedRound, int durationRounds) {
        return "{\"id\": \"" + id + "\", \"spell\": \"" + spell + "\", \"slotLevel\": " + slotLevel
                + ", \"startedRound\": " + startedRound + ", \"durationRounds\": " + durationRounds + "}";
    }

    private UUID v33Participant(UUID encounter, String name, String activeSpell, Integer slotLevel,
                                String concentration) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V33_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id, active_spell, "
                + "active_spell_slot_level, concentration) VALUES (?, ?, 'PLAYER', 20, 7, 12, 'ALIVE', ?, ?, ?, "
                + "?::jsonb)", id, name, encounter, activeSpell, slotLevel, concentration);
        return id;
    }

    private List<Map<String, Object>> v33Effects(UUID participant) {
        return jdbcTemplate.queryForList("SELECT e ->> 'spell' AS spell, e ->> 'slotLevel' AS slot_level, "
                + "e ->> 'concentrationInstanceId' AS instance, e ->> 'startedRound' AS started, "
                + "e ->> 'durationRounds' AS duration, e ->> 'usesLeft' AS uses, e ->> 'id' AS id "
                + "FROM " + V33_SCHEMA + ".encounter_participants p, jsonb_array_elements(p.active_effects) e "
                + "WHERE p.id = ? ORDER BY e ->> 'spell'", participant);
    }

    @Test
    @DisplayName("V33 moves the active spell into a Spell Effect lasting Spiritual Weapon's 10 rounds from the encounter's round, gives each lasting concentration spell an effect linked to its instance, drops the old columns and widens the combat log's types")
    void appliesV33() {
        Map<String, UUID> rows = v33Rows();

        Flyway.configure().dataSource(dataSource).schemas(V33_SCHEMA).locations("classpath:db/migration")
                .target("33").load().migrate();

        List<Map<String, Object>> cleric = v33Effects(rows.get("Cleric"));
        assertThat(cleric).hasSize(2);
        assertThat(cleric.get(0)).containsEntry("spell", "Moonbeam").containsEntry("slot_level", "2")
                .containsEntry("instance", MOONBEAM_ID.toString()).containsEntry("started", "3")
                .containsEntry("duration", "10").containsEntry("uses", null);
        assertThat(cleric.get(1)).containsEntry("spell", "Spiritual Weapon").containsEntry("slot_level", "3")
                .containsEntry("instance", null).containsEntry("started", "4").containsEntry("duration", "10");
        assertThat(cleric.get(0).get("id")).isNotNull().isNotEqualTo(cleric.get(1).get("id"));
        assertThat(v33Effects(rows.get("Wizard"))).singleElement()
                .satisfies(effect -> assertThat(effect).containsEntry("spell", "Melf's Minute Meteors")
                        .containsEntry("instance", METEORS_ID.toString()).containsEntry("uses", "10")
                        .containsEntry("duration", "100"));
        assertThat(v33Effects(rows.get("Bard"))).isEmpty();
        assertThat(v33Effects(rows.get("Goblin"))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND column_name IN ('active_spell', 'active_spell_slot_level')", Integer.class, V33_SCHEMA))
                .isZero();
        // The concentration itself stays
        assertThat(jdbcTemplate.queryForObject("SELECT concentration ->> 'spell' FROM " + V33_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, rows.get("Cleric"))).isEqualTo("Moonbeam");
        jdbcTemplate.update("INSERT INTO " + V33_SCHEMA + ".combat_logs (id, encounter_id, action_type, "
                + "description, round_number) SELECT ?, encounter_id, 'SPELL_EFFECT_END', 'ends', 4 FROM " + V33_SCHEMA
                + ".encounter_participants WHERE id = ?", UUID.randomUUID(), rows.get("Cleric"));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V33_SCHEMA + ".encounter_participants "
                + "SET active_effects = NULL WHERE id = ?", rows.get("Goblin"))).hasMessageContaining("active_effects");
    }

    // ── V34: Summoning (D182) ────────────────────────────────────

    /**
     * Takes a schema of its own to V33 and fills it as M26 found it: a druid with a Spell Effect written before V34,
     * without a summon field, a wolf companion added by hand, and a goblin.
     */
    private Map<String, UUID> v34Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V34_SCHEMA).locations("classpath:db/migration")
                .target("33").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V34_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V34_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);
        jdbcTemplate.update("INSERT INTO " + V34_SCHEMA + ".encounters (id, name, status, campaign_id, round_number) "
                + "VALUES (?, 'Glade', 'ACTIVE', ?, 2)", encounter, campaign);
        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("encounter", encounter);
        rows.put("campaign", campaign);
        rows.put("Druid", v34Participant(encounter, "Druid", "PLAYER", "[{\"id\": \"" + UUID.randomUUID()
                + "\", \"spell\": \"Spiritual Weapon\", \"slotLevel\": 2, \"concentrationInstanceId\": null, "
                + "\"startedRound\": 1, \"durationRounds\": 10, \"usesLeft\": null}]"));
        rows.put("Wolf", v34Participant(encounter, "Wolf", "COMPANION", "[]"));
        rows.put("Goblin", v34Participant(encounter, "Goblin", "MONSTER", "[]"));
        return rows;
    }

    private UUID v34Participant(UUID encounter, String name, String type, String effects) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V34_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id, active_effects) "
                + "VALUES (?, ?, ?, 20, 7, 12, 'ALIVE', ?, ?::jsonb)", id, name, type, encounter, effects);
        return id;
    }

    @Test
    @DisplayName("V34 adds the summoning columns, null or false on every row, a summoner link that empties when its caster's row goes, the campaigns' setting, and SUMMON and SUMMON_END")
    void appliesV34() {
        Map<String, UUID> rows = v34Rows();
        String effectsBefore = jdbcTemplate.queryForObject("SELECT active_effects::text FROM " + V34_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, rows.get("Druid"));

        Flyway.configure().dataSource(dataSource).schemas(V34_SCHEMA).locations("classpath:db/migration")
                .target("34").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT summon::text AS summon, action_templates::text AS templates, "
                + "dismissed, summoned_by_participant_id FROM " + V34_SCHEMA + ".encounter_participants"))
                .hasSize(3)
                .allSatisfy(row -> assertThat(row).containsEntry("summon", null).containsEntry("templates", null)
                        .containsEntry("dismissed", false).containsEntry("summoned_by_participant_id", null));
        assertThat(jdbcTemplate.queryForObject("SELECT active_effects::text FROM " + V34_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, rows.get("Druid"))).isEqualTo(effectsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT dm_chooses_conjured_creatures FROM " + V34_SCHEMA
                + ".campaigns WHERE id = ?", Boolean.class, rows.get("campaign"))).isFalse();

        // The wolf is the druid's; when the druid's row goes, the link empties
        jdbcTemplate.update("UPDATE " + V34_SCHEMA + ".encounter_participants SET summoned_by_participant_id = ?, "
                + "summon = '{\"spell\": \"Conjure Animals\"}'::jsonb WHERE id = ?", rows.get("Druid"),
                rows.get("Wolf"));
        jdbcTemplate.update("DELETE FROM " + V34_SCHEMA + ".encounter_participants WHERE id = ?", rows.get("Druid"));
        assertThat(jdbcTemplate.queryForObject("SELECT summoned_by_participant_id FROM " + V34_SCHEMA
                + ".encounter_participants WHERE id = ?", UUID.class, rows.get("Wolf"))).isNull();

        jdbcTemplate.update("INSERT INTO " + V34_SCHEMA + ".combat_logs (id, encounter_id, action_type, description, "
                + "round_number) VALUES (?, ?, 'SUMMON', 'summons', 2), (?, ?, 'SUMMON_END', 'disappears', 2)",
                UUID.randomUUID(), rows.get("encounter"), UUID.randomUUID(), rows.get("encounter"));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V34_SCHEMA + ".encounter_participants "
                + "SET dismissed = NULL WHERE id = ?", rows.get("Goblin"))).hasMessageContaining("dismissed");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? "
                + "AND indexname = 'idx_encounter_participants_summoned_by'", Integer.class, V34_SCHEMA)).isEqualTo(1);
    }

    // ── V35: Exhaustion (D183) ───────────────────────────────────

    /**
     * Takes a schema of its own to V34 and fills it as D183 found it: Aria, a character at 30 of 38 hit points, in an
     * Active encounter whose participant joined with totals written before exhaustion existed, beside a goblin.
     */
    private Map<String, UUID> v35Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V35_SCHEMA).locations("classpath:db/migration")
                .target("34").load().migrate();
        UUID owner = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID race = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        UUID aria = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'aria', 'aria@example.com', 'hash')", owner);
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, owner);
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".races (id, name) VALUES (?, 'Human')", race);
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".player_characters (id, user_id, race_id, name, strength, "
                + "dexterity, constitution, intelligence, wisdom, charisma, hp_max, hp_current, hp_temp, armour_class) "
                + "VALUES (?, ?, ?, 'Aria', 10, 10, 14, 10, 10, 10, 38, 30, 0, 10)", aria, owner, race);
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".encounters (id, name, status, campaign_id, round_number) "
                + "VALUES (?, 'Cave', 'ACTIVE', ?, 2)", encounter, campaign);
        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        rows.put("encounter", encounter);
        rows.put("character", aria);
        rows.put("Aria", v35Participant(encounter, "Aria", "PLAYER", aria,
                "{\"hpCurrent\": 30, \"hpTemp\": 0, \"slotsUsed\": {}, \"poolsCurrentUses\": {}}"));
        rows.put("Goblin", v35Participant(encounter, "Goblin", "MONSTER", null, null));
        return rows;
    }

    private UUID v35Participant(UUID encounter, String name, String type, UUID character, String joinedWith) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V35_SCHEMA + ".encounter_participants (id, display_name, "
                + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id, character_id, "
                + "joined_with) VALUES (?, ?, ?, 38, 30, 12, 'ALIVE', ?, ?, ?::jsonb)", id, name, type, encounter,
                character, joinedWith);
        return id;
    }

    @Test
    @DisplayName("V35 gives every character and participant exhaustion level 0, kept from 0 to 6 and never null, and "
            + "leaves what a participant joined with as it was")
    void appliesV35() {
        Map<String, UUID> rows = v35Rows();
        String joinedBefore = jdbcTemplate.queryForObject("SELECT joined_with::text FROM " + V35_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, rows.get("Aria"));

        Flyway.configure().dataSource(dataSource).schemas(V35_SCHEMA).locations("classpath:db/migration")
                .target("35").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT exhaustion_level FROM " + V35_SCHEMA
                + ".encounter_participants", Integer.class)).containsExactly(0, 0);
        assertThat(jdbcTemplate.queryForObject("SELECT exhaustion_level FROM " + V35_SCHEMA
                + ".player_characters WHERE id = ?", Integer.class, rows.get("character"))).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT joined_with::text FROM " + V35_SCHEMA
                + ".encounter_participants WHERE id = ?", String.class, rows.get("Aria"))).isEqualTo(joinedBefore);

        jdbcTemplate.update("UPDATE " + V35_SCHEMA + ".player_characters SET exhaustion_level = 6 WHERE id = ?",
                rows.get("character"));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V35_SCHEMA + ".player_characters "
                + "SET exhaustion_level = 7 WHERE id = ?", rows.get("character")))
                .hasMessageContaining("chk_player_characters_exhaustion_level");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V35_SCHEMA + ".encounter_participants "
                + "SET exhaustion_level = -1 WHERE id = ?", rows.get("Goblin")))
                .hasMessageContaining("chk_encounter_participants_exhaustion_level");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V35_SCHEMA + ".encounter_participants "
                + "SET exhaustion_level = NULL WHERE id = ?", rows.get("Goblin")))
                .hasMessageContaining("exhaustion_level");
    }

    // ── V36: Spell Consent (D198) ────────────────────────────────

    /**
     * Takes a schema of its own to V35 and fills it as D198 found it: a rakshasa and a goblin in an Active encounter,
     * neither with a standing choice of which spells it lets through.
     */
    private Map<String, UUID> v36Rows() {
        Flyway.configure().dataSource(dataSource).schemas(V36_SCHEMA).locations("classpath:db/migration")
                .target("35").load().migrate();
        UUID dm = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + V36_SCHEMA + ".users (id, username, email, password_hash) "
                + "VALUES (?, 'dm', 'dm@example.com', 'hash')", dm);
        jdbcTemplate.update("INSERT INTO " + V36_SCHEMA + ".campaigns (id, name, invite_code, dm_user_id) "
                + "VALUES (?, 'Lost Mine', 'ABCD2345', ?)", campaign, dm);
        jdbcTemplate.update("INSERT INTO " + V36_SCHEMA + ".encounters (id, name, status, campaign_id, round_number) "
                + "VALUES (?, 'Palace', 'ACTIVE', ?, 2)", encounter, campaign);
        Map<String, UUID> rows = new java.util.LinkedHashMap<>();
        for (String name : List.of("Rakshasa", "Goblin")) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + V36_SCHEMA + ".encounter_participants (id, display_name, "
                    + "participant_type, hp_max, hp_current, armour_class, life_state, encounter_id) "
                    + "VALUES (?, ?, 'MONSTER', 110, 110, 16, 'ALIVE', ?)", id, name, encounter);
            rows.put(name, id);
        }
        return rows;
    }

    @Test
    @DisplayName("Previews V36 on a database at V35: every participant loads with HELPFUL, and nothing is written")
    void previewsV36() {
        v36Rows();
        DataSource v36 = schemaDataSource(V36_SCHEMA);

        MaintenanceReport report = new PreviewMigrationJob(v36, new JdbcTemplate(v36), entityManagerFactory,
                objectMapper, "36").run(false);

        assertThat(report.warnings()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND column_name = 'spell_consent'", Integer.class, V36_SCHEMA)).isZero();
    }

    @Test
    @DisplayName("V36 gives every participant HELPFUL, never null and only NEVER, HELPFUL or ALWAYS")
    void appliesV36() {
        Map<String, UUID> rows = v36Rows();

        Flyway.configure().dataSource(dataSource).schemas(V36_SCHEMA).locations("classpath:db/migration")
                .target("36").load().migrate();

        assertThat(jdbcTemplate.queryForList("SELECT spell_consent FROM " + V36_SCHEMA
                + ".encounter_participants", String.class)).containsExactly("HELPFUL", "HELPFUL");
        jdbcTemplate.update("UPDATE " + V36_SCHEMA + ".encounter_participants SET spell_consent = 'NEVER' "
                + "WHERE id = ?", rows.get("Rakshasa"));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V36_SCHEMA + ".encounter_participants "
                + "SET spell_consent = 'SOMETIMES' WHERE id = ?", rows.get("Goblin")))
                .hasMessageContaining("chk_encounter_participants_spell_consent");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + V36_SCHEMA + ".encounter_participants "
                + "SET spell_consent = NULL WHERE id = ?", rows.get("Goblin")))
                .hasMessageContaining("spell_consent");
    }
}
