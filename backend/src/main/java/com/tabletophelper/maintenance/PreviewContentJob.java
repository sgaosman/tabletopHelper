package com.tabletophelper.maintenance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.content.ContentLoading;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dry-runs the content loading the next normal start would do (D169). In a transaction it always rolls back, it takes
 * a snapshot of the 13 reference tables, runs the loaders whose content changed as {@code ContentLoading} would at
 * startup, takes another snapshot and reports every row added, deleted or changed, column by column, after what each
 * loader did. It never writes: a maintenance start loads no content ({@code content.mode} is off for it), and the
 * next normal start applies what this reports. For example:
 * {@code ./gradlew bootRun --args="--maintenance.job=preview-content --server.port=0"}
 */
@Component
public class PreviewContentJob implements MaintenanceJob {

    private final ContentLoading contentLoading;
    private final DataSource dataSource;
    private final TableSnapshot tableSnapshot;
    private final TransactionTemplate rolledBack;

    public PreviewContentJob(ContentLoading contentLoading, DataSource dataSource, ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.contentLoading = contentLoading;
        this.dataSource = dataSource;
        this.tableSnapshot = new TableSnapshot(objectMapper);
        this.rolledBack = new TransactionTemplate(transactionManager);
        this.rolledBack.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String name() {
        return "preview-content";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        if (apply) {
            throw new IllegalArgumentException(
                    "preview-content never writes: the next normal start loads the content it reports");
        }
        return rolledBack.execute(status -> {
            status.setRollbackOnly();
            // The connection the loaders' transaction runs on, so the second snapshot sees their writes
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                Map<String, Map<String, JsonNode>> before = snapshot(connection);
                // A failed loader is reported as a warning, even where content.fail-on-error would stop a start
                List<ContentLoading.Outcome> outcomes = contentLoading.load(false);
                Map<String, Map<String, JsonNode>> after = snapshot(connection);

                List<String> changes = new ArrayList<>();
                outcomes.stream().filter(o -> o.status() != ContentLoading.Status.UNCHANGED)
                        .forEach(o -> changes.add("loader " + o));
                changes.addAll(TableSnapshot.compare(before, after, true));
                List<String> warnings = outcomes.stream()
                        .filter(o -> o.status() == ContentLoading.Status.FAILED
                                || o.status() == ContentLoading.Status.SKIPPED)
                        .map(o -> "The next start would not load " + o)
                        .toList();
                int examined = before.values().stream().mapToInt(Map::size).sum();
                return new MaintenanceReport(name(), false, "reference rows", examined, changes, warnings,
                        "The next normal start loads this content; back up the database first (D150).");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read the reference tables: " + e.getMessage(), e);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    // Every reference row as to_jsonb gives it, by its primary key
    private Map<String, Map<String, JsonNode>> snapshot(Connection connection) throws SQLException {
        Map<String, Map<String, JsonNode>> snapshot = new LinkedHashMap<>();
        for (var table : ContentSnapshotJob.referenceTables().entrySet()) {
            Map<String, JsonNode> rows = new TreeMap<>();
            for (TableSnapshot.Row row : tableSnapshot.rows(connection, "SELECT t." + table.getValue()
                    + "::text, to_jsonb(t)::text FROM " + table.getKey() + " t")) {
                rows.put(row.key(), row.columns());
            }
            snapshot.put(table.getKey(), rows);
        }
        return snapshot;
    }
}
