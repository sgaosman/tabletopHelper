package com.tabletophelper.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tables' rows as PostgreSQL's {@code to_jsonb} gives them, keyed by ID or by another key, and the differences
 * between two such snapshots, column by column. {@code preview-migration} compares its tables before and after a
 * migration with it, and {@code content-snapshot} compares the reference tables with a snapshot taken earlier.
 */
final class TableSnapshot {

    // The columns that name a row in a report
    private static final List<String> LABEL_COLUMNS = List.of("display_name", "name", "action_type", "pool_id");

    private final ObjectMapper objectMapper;

    TableSnapshot(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** One row: its key and its columns. */
    record Row(String key, JsonNode columns) {}

    /** Every row of each table as {@code to_jsonb}, by ID, in ID order. */
    Map<String, Map<String, JsonNode>> byId(Connection connection, Collection<String> tables) throws SQLException {
        Map<String, Map<String, JsonNode>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            Map<String, JsonNode> rows = new LinkedHashMap<>();
            for (Row row : rows(connection, "SELECT id::text, to_jsonb(t)::text FROM " + table + " t ORDER BY id")) {
                rows.put(row.key(), row.columns());
            }
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    /** The rows a query returns, in its order: the key in its first column and the JSON text of the row in its second. */
    List<Row> rows(Connection connection, String sql) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(new Row(resultSet.getString(1), readTree(resultSet.getString(2))));
            }
        }
        return rows;
    }

    JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PostgreSQL returned JSON that doesn't parse: " + json, e);
        }
    }

    /**
     * Each row deleted, added or changed between two snapshots, table by table: a changed row lists each column
     * whose value differs, before and after. With {@code labelled}, a row is named by its key and, in brackets, the
     * first of its name columns it has; without, by its key alone, for keys that already name the row.
     */
    static List<String> compare(Map<String, Map<String, JsonNode>> before, Map<String, Map<String, JsonNode>> after,
                                boolean labelled) {
        List<String> changes = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>(before.keySet());
        tables.addAll(after.keySet());
        for (String table : tables) {
            Map<String, JsonNode> oldRows = before.getOrDefault(table, Map.of());
            Map<String, JsonNode> newRows = after.getOrDefault(table, Map.of());
            oldRows.forEach((key, oldRow) -> {
                JsonNode newRow = newRows.get(key);
                if (newRow == null) {
                    changes.add(describe(table, key, oldRow, labelled) + ": deleted");
                } else {
                    String changed = changedColumns(oldRow, newRow);
                    if (!changed.isEmpty()) {
                        changes.add(describe(table, key, oldRow, labelled) + changed);
                    }
                }
            });
            newRows.forEach((key, newRow) -> {
                if (!oldRows.containsKey(key)) {
                    changes.add(describe(table, key, newRow, labelled) + ": added");
                }
            });
        }
        return changes;
    }

    // Each column whose value differs. A column added as NULL, or dropped while NULL, changes nothing in the row.
    static String changedColumns(JsonNode oldRow, JsonNode newRow) {
        Set<String> columns = new TreeSet<>();
        oldRow.fieldNames().forEachRemaining(columns::add);
        newRow.fieldNames().forEachRemaining(columns::add);
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            JsonNode oldValue = oldRow.path(column);
            JsonNode newValue = newRow.path(column);
            boolean bothAbsent = (oldValue.isMissingNode() || oldValue.isNull())
                    && (newValue.isMissingNode() || newValue.isNull());
            if (!oldValue.equals(newValue) && !bothAbsent) {
                sb.append("\n      ").append(column).append(": ").append(oldValue).append(" → ").append(newValue);
            }
        }
        return sb.toString();
    }

    /** A row as a report names it: its table, its key and, in brackets, the first of its name columns it has. */
    static String describe(String table, String key, JsonNode row) {
        return describe(table, key, row, true);
    }

    private static String describe(String table, String key, JsonNode row, boolean labelled) {
        String label = !labelled ? null : LABEL_COLUMNS.stream()
                .map(column -> row.path(column).asText(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return table + " " + key + (label != null ? " (" + label + ")" : "");
    }
}
