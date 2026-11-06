package com.tabletophelper.maintenance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.MonsterPoolTrigger;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dry-runs a pending Flyway migration (D150, D168). It runs {@code db/migration/V<N>__*.sql} in a
 * transaction it always rolls back, reports each encounter, participant, combat log, monster pool trigger and
 * player character row the migration would change, before and after, and checks that every row then loads with
 * the current entity mappings. It never writes: Flyway applies the migration at the next normal start.
 * <p>
 * Start the app with Flyway's target below the migration, so it stays pending, and with
 * {@code --server.port=0} to stay clear of a running backend. For V15:
 * {@code ./gradlew bootRun --args="--maintenance.job=preview-migration --maintenance.version=15
 * --spring.flyway.target=14 --spring.jpa.hibernate.ddl-auto=none --server.port=0"}
 */
@Component
public class PreviewMigrationJob implements MaintenanceJob {

    // The tables compared, each with the entity its rows must load as
    private static final Map<String, Class<?>> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("encounters", Encounter.class);
        TABLES.put("encounter_participants", EncounterParticipant.class);
        TABLES.put("combat_logs", CombatLog.class);
        TABLES.put("monster_pool_triggers", MonsterPoolTrigger.class);
        TABLES.put("player_characters", PlayerCharacter.class);
    }

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManagerFactory entityManagerFactory;
    private final TableSnapshot tableSnapshot;
    private final String version;

    public PreviewMigrationJob(DataSource dataSource, JdbcTemplate jdbcTemplate,
                               EntityManagerFactory entityManagerFactory, ObjectMapper objectMapper,
                               @Value("${maintenance.version:}") String version) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManagerFactory = entityManagerFactory;
        this.tableSnapshot = new TableSnapshot(objectMapper);
        this.version = version;
    }

    @Override
    public String name() {
        return "preview-migration";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        if (apply) {
            throw new IllegalArgumentException(
                    "preview-migration never writes: Flyway applies the migration at the next normal start");
        }
        if (version == null || !version.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "preview-migration needs the migration's version, such as --maintenance.version=15");
        }
        int target = Integer.parseInt(version);
        requirePending(target);
        return preview(target);
    }

    /**
     * Runs V{@code target} against the current data and rolls it back, whatever happens. Package-private
     * so that tests can preview a migration Flyway has already applied to their database.
     */
    MaintenanceReport preview(int target) {
        Resource script = migrationScripts().get(target);
        if (script == null) {
            throw new IllegalArgumentException("There is no migration V" + target + " in db/migration");
        }

        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int examined;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Map<String, JsonNode>> before = tableSnapshot.byId(connection, TABLES.keySet());
                examined = before.values().stream().mapToInt(Map::size).sum();
                try {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(script, StandardCharsets.UTF_8));
                } catch (ScriptException e) {
                    warnings.add("Flyway would fail too: " + e.getMessage() + ": " + rootMessage(e));
                    return report(target, examined, changes, warnings);
                }
                Map<String, Map<String, JsonNode>> after = tableSnapshot.byId(connection, TABLES.keySet());
                changes.addAll(TableSnapshot.compare(before, after, true));
                checkRowsLoad(connection, after, warnings);
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not preview V" + target + ": " + e.getMessage(), e);
        }
        return report(target, examined, changes, warnings);
    }

    private MaintenanceReport report(int target, int examined, List<String> changes, List<String> warnings) {
        return new MaintenanceReport(name() + " V" + target, false, "rows", examined, changes, warnings,
                "Flyway applies V" + target + " at the next normal start; back up the database first (D150).");
    }

    // ── Which migration ──────────────────────────────────────────

    private void requirePending(int target) {
        Set<Integer> applied = jdbcTemplate.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL", String.class)
                .stream()
                .filter(v -> v.matches("\\d+"))
                .map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
        if (applied.contains(target)) {
            throw new IllegalStateException("V" + target + " is already applied, so there is nothing to preview. "
                    + "Preview it before Flyway applies it, starting the app with --spring.flyway.target="
                    + (target - 1));
        }
        migrationScripts().keySet().stream()
                .filter(v -> v < target && !applied.contains(v))
                .findFirst()
                .ifPresent(earlier -> {
                    throw new IllegalStateException("V" + earlier + " isn't applied yet, and Flyway applies it before V"
                            + target + "; preview V" + earlier + " first");
                });
    }

    private static Map<Integer, Resource> migrationScripts() {
        try {
            Map<Integer, Resource> scripts = new TreeMap<>();
            for (Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*__*.sql")) {
                Matcher matcher = MIGRATION_FILE.matcher(Objects.requireNonNull(resource.getFilename()));
                if (matcher.matches()) {
                    scripts.put(Integer.valueOf(matcher.group(1)), resource);
                }
            }
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("Could not list the migrations in db/migration", e);
        }
    }

    // ── Loading ──────────────────────────────────────────────────

    /**
     * Loads every row as its entity through Hibernate, on the connection the migration ran on, so the
     * JSON columns are read into their records exactly as the app will read them. Each row has its own
     * savepoint, so one that fails doesn't stop the rest.
     */
    private void checkRowsLoad(Connection connection, Map<String, Map<String, JsonNode>> rows, List<String> warnings)
            throws SQLException {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        try (Session session = sessionFactory.withOptions().connection(connection).openSession()) {
            session.setDefaultReadOnly(true);
            session.setHibernateFlushMode(FlushMode.MANUAL);
            for (var table : TABLES.entrySet()) {
                for (var row : rows.get(table.getKey()).entrySet()) {
                    Savepoint savepoint = connection.setSavepoint();
                    try {
                        session.find(table.getValue(), UUID.fromString(row.getKey()));
                        connection.releaseSavepoint(savepoint);
                    } catch (RuntimeException e) {
                        connection.rollback(savepoint);
                        warnings.add(TableSnapshot.describe(table.getKey(), row.getKey(), row.getValue())
                                + " does not load: " + rootMessage(e));
                    } finally {
                        session.clear();
                    }
                }
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
