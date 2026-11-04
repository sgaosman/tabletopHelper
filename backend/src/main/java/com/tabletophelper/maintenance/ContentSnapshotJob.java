package com.tabletophelper.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Takes a snapshot of the 13 reference tables, and compares them with one taken earlier (D169). It never writes to
 * the database: it reads every table in one read-only transaction, so the tables agree with each other.
 * <p>
 * With {@code --maintenance.output=<dir>} it writes two JSON Lines files a table: {@code <table>.by-id.jsonl}, each
 * row as {@code to_jsonb} gives it, in ID order, and {@code <table>.by-key.jsonl}, each row by its natural key, in
 * key order, without its ID and timestamps and with the IDs of the rows it refers to replaced by their natural keys,
 * so that two databases seeded separately can be compared. It refuses to overwrite a snapshot already there.
 * <p>
 * With {@code --maintenance.compare=<dir>} it reports, against the snapshot in that directory, the rows added,
 * deleted and changed by ID and by natural key, and each natural key whose ID changed;
 * {@code --maintenance.compare-by=id} or {@code key} reports only one of the two. Natural keys that two rows share
 * are warnings. Both options can be given at once. For example:
 * {@code ./gradlew bootRun --args="--maintenance.job=content-snapshot --maintenance.output=../snapshots/before
 * --server.port=0"}
 */
@Component
@Slf4j
public class ContentSnapshotJob implements MaintenanceJob {

    /**
     * A reference table and its natural key, as SQL over the table aliased {@code t}.
     *
     * @param idColumn    the primary key
     * @param key         the natural key
     * @param joins       the joins the key and references need
     * @param foreignKeys the columns holding other rows' IDs, which the by-key rows leave out
     * @param references  the {@code jsonb_build_object} arguments that put those rows' natural keys in their place
     */
    private record ReferenceTable(String table, String idColumn, String key, String joins, List<String> foreignKeys,
                                  String references) {
    }

    private static final String CLASS_KEY = "c.name || '|' || coalesce(c.source, '')";
    private static final String SUBCLASS_KEY = "s.name || '|' || coalesce(s.source, '')";

    // The reference tables. A race, class, background, spell, monster, item, feat and optional feature is known by
    // its name and source, a subclass by its class's too, a condition by its name, a pool definition by its pool ID,
    // a class's pool by its class, subclass and pool, and a monster trigger by its pool and condition.
    private static final List<ReferenceTable> TABLES = List.of(
            byNameAndSource("races"),
            byNameAndSource("character_classes"),
            new ReferenceTable("subclasses", "id", CLASS_KEY + " || '|' || t.name || '|' || coalesce(t.source, '')",
                    "JOIN character_classes c ON c.id = t.character_class_id", List.of("character_class_id"),
                    "'class', " + CLASS_KEY),
            byNameAndSource("backgrounds"),
            byNameAndSource("spells"),
            byNameAndSource("monsters"),
            new ReferenceTable("conditions", "id", "t.name", "", List.of(), null),
            byNameAndSource("items"),
            byNameAndSource("feats"),
            byNameAndSource("optional_features"),
            new ReferenceTable("resource_pool_definitions", "pool_id", "t.pool_id", "", List.of(), null),
            new ReferenceTable("class_feature_pools", "id",
                    CLASS_KEY + " || '|' || coalesce(" + SUBCLASS_KEY + ", '') || '|' || t.pool_id",
                    "JOIN character_classes c ON c.id = t.class_id LEFT JOIN subclasses s ON s.id = t.subclass_id",
                    List.of("class_id", "subclass_id"), "'class', " + CLASS_KEY + ", 'subclass', " + SUBCLASS_KEY),
            new ReferenceTable("monster_pool_triggers", "id", "t.pool_id || '|' || t.trigger_condition::text", "",
                    List.of(), null));

    private static ReferenceTable byNameAndSource(String table) {
        return new ReferenceTable(table, "id", "t.name || '|' || coalesce(t.source, '')", "", List.of(), null);
    }

    private static final String BY_ID = ".by-id.jsonl";
    private static final String BY_KEY = ".by-key.jsonl";

    /** A row by its natural key: its ID, and its columns without the ID. */
    private record Keyed(String id, JsonNode row) {
    }

    /** Every table's rows by ID and by natural key, and the natural keys rows share. */
    private record Snapshot(Map<String, Map<String, JsonNode>> byId, Map<String, Map<String, Keyed>> byKey,
                            List<String> sharedKeys) {
        int rows() {
            return byId.values().stream().mapToInt(Map::size).sum();
        }
    }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TableSnapshot tableSnapshot;
    private final String output;
    private final String compare;
    private final String compareBy;

    public ContentSnapshotJob(DataSource dataSource, ObjectMapper objectMapper,
                              @Value("${maintenance.output:}") String output,
                              @Value("${maintenance.compare:}") String compare,
                              @Value("${maintenance.compare-by:both}") String compareBy) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tableSnapshot = new TableSnapshot(objectMapper);
        this.output = output;
        this.compare = compare;
        this.compareBy = compareBy;
    }

    @Override
    public String name() {
        return "content-snapshot";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        if (apply) {
            throw new IllegalArgumentException("content-snapshot never writes to the database, so it has nothing to apply");
        }
        boolean writing = output != null && !output.isBlank();
        boolean comparing = compare != null && !compare.isBlank();
        if (!writing && !comparing) {
            throw new IllegalArgumentException("content-snapshot needs --maintenance.output=<dir> to write a snapshot, "
                    + "--maintenance.compare=<dir> to compare with one, or both");
        }
        if (!List.of("both", "id", "key").contains(compareBy)) {
            throw new IllegalArgumentException("--maintenance.compare-by is both, id or key, not '" + compareBy + "'");
        }
        if (writing) {
            requireNoSnapshot(Path.of(output));
        }
        Snapshot before = comparing ? read(Path.of(compare)) : null;

        Snapshot now = take();
        if (writing) {
            write(now, Path.of(output));
        }

        List<String> changes = new ArrayList<>();
        if (comparing) {
            if (!compareBy.equals("key")) {
                TableSnapshot.compare(before.byId(), now.byId(), true).forEach(change -> changes.add("by ID: " + change));
            }
            if (!compareBy.equals("id")) {
                changes.addAll(compareByKey(before.byKey(), now.byKey()));
            }
        }
        String nextStep = comparing
                ? "content-snapshot changes nothing; these are the differences from the snapshot in " + compare + "."
                : "content-snapshot changes nothing.";
        return new MaintenanceReport(name(), false, "reference rows", now.rows(), changes, now.sharedKeys(), nextStep);
    }

    // ── Taking a snapshot ────────────────────────────────────────

    private Snapshot take() {
        Map<String, Map<String, JsonNode>> byId = new LinkedHashMap<>();
        Map<String, Map<String, Keyed>> byKey = new LinkedHashMap<>();
        List<String> sharedKeys = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                for (ReferenceTable table : TABLES) {
                    Map<String, JsonNode> rows = new TreeMap<>();
                    for (TableSnapshot.Row row : tableSnapshot.rows(connection, "SELECT t." + table.idColumn()
                            + "::text, to_jsonb(t)::text FROM " + table.table() + " t")) {
                        rows.put(row.key(), row.columns());
                    }
                    byId.put(table.table(), rows);
                    byKey.put(table.table(), keyed(connection, table, sharedKeys));
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the reference tables: " + e.getMessage(), e);
        }
        return new Snapshot(byId, byKey, sharedKeys);
    }

    // The table's rows by natural key. Rows that share a key are told apart by a number after it, in ID order, and
    // the key is a warning, because the unique natural-key indexes of V26 would refuse them.
    private Map<String, Keyed> keyed(Connection connection, ReferenceTable table, List<String> sharedKeys)
            throws SQLException {
        StringBuilder row = new StringBuilder("to_jsonb(t) - 'id' - 'created_at' - 'updated_at'");
        table.foreignKeys().forEach(column -> row.append(" - '").append(column).append('\''));
        String columns = table.references() == null ? "(" + row + ")"
                : "((" + row + ") || jsonb_build_object(" + table.references() + "))";
        String sql = "SELECT " + table.key() + ", jsonb_build_object('id', t." + table.idColumn() + "::text, 'row', "
                + columns + ")::text FROM " + table.table() + " t " + table.joins();

        Map<String, List<Keyed>> byKey = new TreeMap<>();
        for (TableSnapshot.Row found : tableSnapshot.rows(connection, sql)) {
            byKey.computeIfAbsent(found.key(), k -> new ArrayList<>())
                    .add(new Keyed(found.columns().path("id").asText(), found.columns().path("row")));
        }
        Map<String, Keyed> rows = new TreeMap<>();
        byKey.forEach((key, sharing) -> {
            if (sharing.size() > 1) {
                sharing.sort((a, b) -> a.id().compareTo(b.id()));
                sharedKeys.add(table.table() + " " + key + ": " + sharing.size() + " rows share this natural key ("
                        + String.join(", ", sharing.stream().map(Keyed::id).toList()) + ")");
            }
            for (int i = 0; i < sharing.size(); i++) {
                rows.put(i == 0 ? key : key + " #" + (i + 1), sharing.get(i));
            }
        });
        return rows;
    }

    // ── Writing and reading ──────────────────────────────────────

    private static void requireNoSnapshot(Path directory) {
        for (ReferenceTable table : TABLES) {
            for (String suffix : List.of(BY_ID, BY_KEY)) {
                if (Files.exists(directory.resolve(table.table() + suffix))) {
                    throw new IllegalArgumentException(directory.toAbsolutePath() + " already holds a snapshot ("
                            + table.table() + suffix + "); write the new one to another directory");
                }
            }
        }
    }

    private void write(Snapshot snapshot, Path directory) {
        try {
            Files.createDirectories(directory);
            for (ReferenceTable table : TABLES) {
                try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(table.table() + BY_ID),
                        StandardCharsets.UTF_8)) {
                    for (var row : snapshot.byId().get(table.table()).entrySet()) {
                        ObjectNode line = objectMapper.createObjectNode().put("id", row.getKey());
                        line.set("row", row.getValue());
                        writeLine(out, line);
                    }
                }
                try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(table.table() + BY_KEY),
                        StandardCharsets.UTF_8)) {
                    for (var row : snapshot.byKey().get(table.table()).entrySet()) {
                        ObjectNode line = objectMapper.createObjectNode()
                                .put("key", row.getKey()).put("id", row.getValue().id());
                        line.set("row", row.getValue().row());
                        writeLine(out, line);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the snapshot to " + directory.toAbsolutePath(), e);
        }
        log.info("Wrote a snapshot of {} reference rows in {} tables to {}", snapshot.rows(), TABLES.size(),
                directory.toAbsolutePath());
    }

    private void writeLine(BufferedWriter out, JsonNode line) throws IOException {
        out.write(objectMapper.writeValueAsString(line));
        out.write('\n');
    }

    private Snapshot read(Path directory) {
        Map<String, Map<String, JsonNode>> byId = new LinkedHashMap<>();
        Map<String, Map<String, Keyed>> byKey = new LinkedHashMap<>();
        for (ReferenceTable table : TABLES) {
            Map<String, JsonNode> rows = new TreeMap<>();
            for (JsonNode line : lines(directory.resolve(table.table() + BY_ID))) {
                rows.put(line.path("id").asText(), line.path("row"));
            }
            byId.put(table.table(), rows);
            Map<String, Keyed> keyed = new TreeMap<>();
            for (JsonNode line : lines(directory.resolve(table.table() + BY_KEY))) {
                keyed.put(line.path("key").asText(), new Keyed(line.path("id").asText(), line.path("row")));
            }
            byKey.put(table.table(), keyed);
        }
        return new Snapshot(byId, byKey, List.of());
    }

    private List<JsonNode> lines(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(file.toAbsolutePath() + " is missing: compare with a directory that "
                    + "content-snapshot wrote with --maintenance.output");
        }
        try {
            List<JsonNode> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(objectMapper.readTree(line));
                }
            }
            return lines;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(file.toAbsolutePath() + " has a line that isn't JSON: "
                    + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        }
    }

    // ── Comparing by natural key ─────────────────────────────────

    // The rows added, deleted and changed by natural key, then each natural key whose row has a new ID
    private static List<String> compareByKey(Map<String, Map<String, Keyed>> before,
                                             Map<String, Map<String, Keyed>> after) {
        List<String> changes = new ArrayList<>();
        TableSnapshot.compare(rows(before), rows(after), false)
                .forEach(change -> changes.add("by natural key: " + change));
        for (ReferenceTable table : TABLES) {
            Map<String, Keyed> newRows = after.getOrDefault(table.table(), Map.of());
            before.getOrDefault(table.table(), Map.of()).forEach((key, old) -> {
                Keyed now = newRows.get(key);
                if (now != null && !now.id().equals(old.id())) {
                    changes.add("by natural key: " + table.table() + " " + key + ": ID " + old.id() + " → " + now.id());
                }
            });
        }
        return changes;
    }

    private static Map<String, Map<String, JsonNode>> rows(Map<String, Map<String, Keyed>> byKey) {
        Map<String, Map<String, JsonNode>> rows = new LinkedHashMap<>();
        byKey.forEach((table, keyed) -> {
            Map<String, JsonNode> columns = new TreeMap<>();
            keyed.forEach((key, row) -> columns.put(key, row.row()));
            rows.put(table, columns);
        });
        return rows;
    }
}
