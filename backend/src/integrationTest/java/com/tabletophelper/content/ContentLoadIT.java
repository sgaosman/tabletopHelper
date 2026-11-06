package com.tabletophelper.content;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.content.internal.ContentLoader;
import com.tabletophelper.content.internal.ContentVersionRepository;
import com.tabletophelper.maintenance.MaintenanceReport;
import com.tabletophelper.maintenance.PreviewContentJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The content loaders against PostgreSQL (D169): the full load from the bundled files, a second load that changes
 * nothing, a load of one loader and those that depend on it, a loader that fails on a broken file (D155), the
 * preview-content job, and V26's natural keys.
 */
class ContentLoadIT extends IntegrationTest {

    private static final List<String> TABLES = List.of("conditions", "spells", "items", "monsters", "races",
            "character_classes", "subclasses", "backgrounds", "feats", "optional_features", "class_feature_pools",
            "monster_pool_triggers");

    @Autowired private ContentLoading contentLoading;
    @Autowired private PreviewContentJob previewContentJob;
    @Autowired private DataSource dataSource;
    @Autowired private List<ContentLoader> loaders;
    @Autowired private ContentVersionRepository versions;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    // The spells' files replaced by one that isn't JSON; every other file is the bundled one
    private ContentLoading withBrokenSpellsFile(boolean failOnError) {
        return new ContentLoading(loaders, versions, entityManager, objectMapper, transactionManager, failOnError,
                path -> path.equals("data/5etools/spells/spells-*.json")
                        ? "classpath:content-fixtures/broken-spells/spells-*.json" : "classpath:" + path);
    }

    private Map<String, String> storedHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT loader, content_hash FROM content_versions ORDER BY loader")
                .forEach(row -> hashes.put((String) row.get("loader"), (String) row.get("content_hash")));
        return hashes;
    }

    private static Map<String, ContentLoading.Status> statuses(List<ContentLoading.Outcome> outcomes) {
        Map<String, ContentLoading.Status> statuses = new LinkedHashMap<>();
        outcomes.forEach(outcome -> statuses.put(outcome.loader(), outcome.status()));
        return statuses;
    }

    // Every reference row's ID and updated_at where it has one, table by table
    private Map<String, List<String>> rows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("SELECT id::text || ' ' || coalesce(to_jsonb(t)->>'updated_at', '') "
                    + "FROM " + table + " t ORDER BY id", String.class));
        }
        rows.put("resource_pool_definitions", jdbcTemplate.queryForList(
                "SELECT pool_id FROM resource_pool_definitions ORDER BY pool_id", String.class));
        return rows;
    }

    @Test
    @DisplayName("A first load runs every loader; a second runs none; and a forced reload changes no row and keeps every ID")
    void loadingAgainChangesNothing() {
        List<ContentLoading.Outcome> first = contentLoading.load();

        assertThat(statuses(first)).containsOnlyKeys(ContentLoading.ORDER)
                .allSatisfy((loader, status) -> assertThat(status).as(loader).isEqualTo(ContentLoading.Status.LOADED));
        Map<String, List<String>> loaded = rows();
        assertThat(loaded.get("monsters")).hasSizeGreaterThan(2500);
        assertThat(loaded.get("class_feature_pools")).hasSize(74);
        assertThat(loaded.get("resource_pool_definitions")).hasSize(78);
        // V27's level tables (D169): Rage's uses by barbarian level, unlimited at 20th
        assertThat(jdbcTemplate.queryForObject("SELECT level_table::text FROM resource_pool_definitions "
                + "WHERE pool_id = 'class:barbarian-rage'", String.class))
                .contains("\"levelOf\": \"Barbarian\"").contains("\"unlimited\": true");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resource_pool_definitions "
                + "WHERE level_table IS NOT NULL", Integer.class)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM monsters WHERE action_templates IS NOT NULL",
                Integer.class)).isGreaterThan(2000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spells WHERE effect_template IS NOT NULL",
                Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM content_versions", Integer.class)).isEqualTo(12);

        assertThat(statuses(contentLoading.load()))
                .allSatisfy((loader, status) -> assertThat(status).as(loader).isEqualTo(ContentLoading.Status.UNCHANGED));

        // With no hash stored every loader reads its files again, and finds every row as it is
        jdbcTemplate.update("DELETE FROM content_versions");
        List<ContentLoading.Outcome> forced = contentLoading.load();
        assertThat(forced).allSatisfy(outcome -> {
            assertThat(outcome.status()).as(outcome.loader()).isEqualTo(ContentLoading.Status.LOADED);
            assertThat(outcome.counts()).as(outcome.toString())
                    .allSatisfy(counts -> assertThat(counts.added() + counts.changed()).isZero());
        });
        assertThat(rows()).isEqualTo(loaded);
    }

    @Test
    @DisplayName("When one loader's content changes, only it and the loaders that depend on it run, and an edited row is corrected in place")
    void onlyTheChangedLoaderAndItsDependentsRun() {
        contentLoading.load();
        String rageBefore = jdbcTemplate.queryForObject(
                "SELECT display_name FROM resource_pool_definitions WHERE pool_id = 'class:barbarian-rage'", String.class);
        UUID fighterId = jdbcTemplate.queryForObject(
                "SELECT id FROM character_classes WHERE name = 'Fighter' AND source = 'PHB'", UUID.class);
        jdbcTemplate.update("UPDATE resource_pool_definitions SET display_name = 'Edited' "
                + "WHERE pool_id = 'class:barbarian-rage'");
        jdbcTemplate.update("UPDATE content_versions SET content_hash = repeat('0', 64) WHERE loader = 'resource-pools'");

        assertThat(statuses(contentLoading.load())).allSatisfy((loader, status) -> assertThat(status).as(loader)
                .isEqualTo(loader.equals("resource-pools") ? ContentLoading.Status.LOADED : ContentLoading.Status.UNCHANGED));
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM resource_pool_definitions "
                + "WHERE pool_id = 'class:barbarian-rage'", String.class)).isEqualTo(rageBefore);

        // The classes' content changing runs the pools too, which read them
        jdbcTemplate.update("UPDATE content_versions SET content_hash = repeat('0', 64) WHERE loader = 'classes'");
        assertThat(contentLoading.plan()).filteredOn(ContentLoading.Planned::runs)
                .extracting(ContentLoading.Planned::loader).containsExactly("classes", "resource-pools");
        assertThat(contentLoading.load()).filteredOn(outcome -> outcome.status() == ContentLoading.Status.LOADED)
                .extracting(ContentLoading.Outcome::loader).containsExactly("classes", "resource-pools");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM character_classes WHERE name = 'Fighter' AND source = 'PHB'", UUID.class))
                .isEqualTo(fighterId);
    }

    @Test
    @DisplayName("A broken spells file fails the spells loader, which rolls back and keeps its hash; the loaders that depend on it are skipped and keep theirs; the rest run")
    void aBrokenFileFailsItsLoaderAndSkipsItsDependents() {
        contentLoading.load();
        jdbcTemplate.update("UPDATE content_versions SET content_hash = repeat('0', 64) "
                + "WHERE loader IN ('spells', 'races', 'feats')");
        Map<String, String> hashesBefore = storedHashes();
        Map<String, List<String>> rowsBefore = rows();

        List<ContentLoading.Outcome> outcomes = withBrokenSpellsFile(false).load();

        assertThat(statuses(outcomes)).containsEntry("spells", ContentLoading.Status.FAILED)
                .containsEntry("spell-effects", ContentLoading.Status.SKIPPED)
                .containsEntry("races", ContentLoading.Status.SKIPPED)
                .containsEntry("feats", ContentLoading.Status.LOADED)
                .containsEntry("conditions", ContentLoading.Status.UNCHANGED);
        assertThat(outcomes).filteredOn(outcome -> outcome.loader().equals("spells"))
                .singleElement().satisfies(outcome -> assertThat(outcome.detail()).contains("spells-broken.json"));
        Map<String, String> hashesAfter = storedHashes();
        assertThat(hashesAfter).as("only feats stored a new hash")
                .containsAllEntriesOf(Map.of("spells", hashesBefore.get("spells"),
                        "spell-effects", hashesBefore.get("spell-effects"), "races", hashesBefore.get("races")));
        assertThat(hashesAfter.get("feats")).isNotEqualTo(hashesBefore.get("feats"));
        assertThat(rows()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("With content.fail-on-error, as the prod profile sets it, a loader that fails stops the load: it rolls back, and no later loader runs")
    void failOnErrorStopsTheLoad() {
        assertThatThrownBy(() -> withBrokenSpellsFile(true).load())
                .isInstanceOf(ContentLoading.ContentLoadFailedException.class)
                .hasMessageContaining("spells failed").hasMessageContaining("spells-broken.json");

        assertThat(storedHashes()).containsOnlyKeys("conditions");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spells", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM items", Integer.class)).isZero();
    }

    @Test
    @DisplayName("preview-content reports the rows a load would add and writes nothing, not even the content versions")
    void previewContentWritesNothing() {
        insertRace("Elf");

        MaintenanceReport report = previewContentJob.run(false);

        assertThat(report.changes()).anyMatch(change -> change.startsWith("loader spells: loaded"));
        assertThat(report.changes()).anyMatch(change -> change.startsWith("races ") && change.endsWith("(Dwarf): added"));
        assertThat(report.warnings()).isEmpty();
        assertThat(report.examined()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM spells", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM races", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM content_versions", Integer.class)).isZero();
        assertThatThrownBy(() -> previewContentJob.run(true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("V26's unique natural keys refuse a second race of the same name and source, and a second pool of a class")
    void naturalKeysAreUnique() {
        insertRace("Elf");
        assertThatThrownBy(() -> insertRace("Elf")).isInstanceOf(DataIntegrityViolationException.class);

        UUID fighter = insertClass("Fighter", 10);
        jdbcTemplate.update("INSERT INTO resource_pool_definitions (pool_id, display_name, source_type, reset_on) "
                + "VALUES ('class:fighter-second-wind', 'Second Wind', 'CLASS', 'shortRest')");
        String pool = "INSERT INTO class_feature_pools (id, class_id, subclass_id, min_level, pool_id) "
                + "VALUES (gen_random_uuid(), ?, NULL, 1, 'class:fighter-second-wind')";
        jdbcTemplate.update(pool, fighter);
        assertThatThrownBy(() -> jdbcTemplate.update(pool, fighter)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V26 keeps the oldest copy of each optional feature, points a feat's recorded choice of a copy at it, and deletes the rest")
    void v26MergesOptionalFeatureCopies() throws Exception {
        UUID characterId = createCharacter(register("ayla").accessToken(), insertRace("Elf"), insertClass("Fighter", 10));
        UUID oldest = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // As the database stood before V26: no unique key, and three copies of a style
                statement.execute("DROP INDEX uq_optional_features_name_source");
                statement.execute("INSERT INTO optional_features (id, name, feature_type, source, created_at) VALUES "
                        + "('" + copy + "', 'Dueling', 'FightingStyle', 'PHB', '2026-01-02'), "
                        + "('" + oldest + "', 'Dueling', 'FightingStyle', 'PHB', '2026-01-01'), "
                        + "('" + other + "', 'Dueling', 'FightingStyle', 'PHB', '2026-01-03'), "
                        + "(gen_random_uuid(), 'Archery', 'FightingStyle', 'PHB', '2026-01-01')");
                statement.execute("UPDATE player_characters SET level_history = '[{\"level\": 4, \"choices\": {\"asi\": "
                        + "{\"appliedEffects\": {\"optionalFeaturesAdded\": [{\"id\": \"" + copy + "\"}, {\"id\": \""
                        + other + "\"}]}}}}]' WHERE id = '" + characterId + "'");

                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new ClassPathResource("db/migration/V26__natural_key_indexes.sql"), StandardCharsets.UTF_8));

                assertThat(query(statement, "SELECT id::text FROM optional_features WHERE name = 'Dueling'"))
                        .containsExactly(oldest.toString());
                assertThat(query(statement, "SELECT count(*)::text FROM optional_features")).containsExactly("2");
                assertThat(query(statement, "SELECT jsonb_path_query_array(level_history, '$[*].choices.asi."
                        + "appliedEffects.optionalFeaturesAdded[*].id')::text FROM player_characters WHERE id = '"
                        + characterId + "'"))
                        .containsExactly("[\"" + oldest + "\", \"" + oldest + "\"]");
                assertThat(query(statement, "SELECT count(*)::text FROM pg_indexes "
                        + "WHERE indexname = 'uq_optional_features_name_source'")).containsExactly("1");
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<String> query(Statement statement, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
