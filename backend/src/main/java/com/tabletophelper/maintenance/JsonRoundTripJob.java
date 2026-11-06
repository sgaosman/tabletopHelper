package com.tabletophelper.maintenance;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellTemplate;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that every typed JSON column (D168) reads back as it is stored: for each value of each column mapped to a
 * Java type, it deserializes the stored text into that type with the application's ObjectMapper, the one
 * {@code config.JsonColumnConfig} gives Hibernate, serializes it again and compares the two as {@code jsonb}, so key
 * order and whitespace don't count. A value that comes back different, or can't be read at all, is reported by table,
 * row and column with an excerpt of each; a change in which only fields that are null differ, such as a null field
 * the records write and the stored JSON leaves out, is marked as such and still counts. A template kept whole whose parts don't fit its records
 * ({@link MonsterTemplates#unreadable()}, {@link SpellTemplate#unreadable()}) reads back unchanged and is a warning.
 * Columns mapped as raw JSON text ({@code @JsonRawValue} Strings) and {@code jsonb} columns no entity maps are skipped
 * and listed. It never writes to the database: it reads in one read-only transaction it rolls back.
 * <p>
 * Run before and after a change to Jackson, Hibernate or the JSON records, for example:
 * {@code ./gradlew bootRun --args="--maintenance.job=json-roundtrip --server.port=0"}
 */
@Component
@Slf4j
public class JsonRoundTripJob implements MaintenanceJob {

    // The entities whose typed JSON columns are checked
    private static final List<Class<?>> ENTITIES = List.of(PlayerCharacter.class, EncounterParticipant.class,
            Encounter.class, Monster.class, Spell.class, Feat.class);

    // The length of each excerpt in a report line
    private static final int EXCERPT = 60;

    /** A typed JSON column and the Java type it maps to. */
    record TypedColumn(String table, String column, JavaType type) {
        String label() {
            return table + "." + column;
        }
    }

    /** The columns checked and the columns skipped, each as table.column. */
    record Columns(List<TypedColumn> typed, List<String> raw, List<String> unmapped) {
    }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JsonRoundTripJob(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "json-roundtrip";
    }

    @Override
    public MaintenanceReport run(boolean apply) {
        if (apply) {
            throw new IllegalArgumentException("json-roundtrip never writes to the database, so it has nothing to apply");
        }
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int examined = 0;
        int changed = 0;
        int unreadable = 0;
        Columns columns;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (PreparedStatement compare = connection.prepareStatement(
                    "SELECT ?::jsonb = ?::jsonb, ?::jsonb::text, jsonb_strip_nulls(?::jsonb) = jsonb_strip_nulls(?::jsonb)")) {
                columns = columns(connection);
                for (TypedColumn column : columns.typed()) {
                    String sql = "SELECT id::text, " + column.column() + "::text FROM " + column.table()
                            + " WHERE " + column.column() + " IS NOT NULL ORDER BY id";
                    try (Statement statement = connection.createStatement();
                         ResultSet rows = statement.executeQuery(sql)) {
                        while (rows.next()) {
                            examined++;
                            String id = rows.getString(1);
                            String stored = rows.getString(2);
                            String where = column.label() + " " + id;
                            Object value;
                            String written;
                            try {
                                value = objectMapper.readValue(stored, column.type());
                                written = objectMapper.writerFor(column.type()).writeValueAsString(value);
                            } catch (Exception e) {
                                unreadable++;
                                changes.add("unreadable: " + where + ": " + firstLine(e.getMessage())
                                        + "; stored " + excerpt(stored, 0));
                                continue;
                            }
                            compare.setString(1, stored);
                            compare.setString(2, written);
                            compare.setString(3, written);
                            compare.setString(4, stored);
                            compare.setString(5, written);
                            try (ResultSet result = compare.executeQuery()) {
                                result.next();
                                if (!result.getBoolean(1)) {
                                    changed++;
                                    String after = result.getString(2);
                                    int at = firstDifference(stored, after);
                                    String only = result.getBoolean(3) ? " (only fields that are null differ)" : "";
                                    changes.add("changed: " + where + only + ": stored " + excerpt(stored, at)
                                            + " → reads back as " + excerpt(after, at));
                                }
                            }
                            String notFitting = recordsNotFitting(value);
                            if (notFitting != null) {
                                warnings.add(where + " reads back unchanged, but its parts don't fit the records: "
                                        + firstLine(notFitting));
                            }
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the typed JSON columns: " + e.getMessage(), e);
        }

        if (!columns.raw().isEmpty()) {
            warnings.add("skipped " + columns.raw().size() + " raw JSON columns, mapped as text: "
                    + String.join(", ", columns.raw()));
        }
        if (!columns.unmapped().isEmpty()) {
            warnings.add("skipped " + columns.unmapped().size() + " jsonb columns no entity maps: "
                    + String.join(", ", columns.unmapped()));
        }
        String summary = "json-roundtrip changes nothing. " + columns.typed().size() + " typed columns, " + examined
                + " values: " + changed + " changed, " + unreadable + " unreadable.";
        log.info(summary);
        return new MaintenanceReport(name(), false, "typed JSON values in " + columns.typed().size() + " columns",
                examined, changes, warnings, summary);
    }

    // ── The columns ──────────────────────────────────────────────

    /**
     * The typed JSON columns of the entities, with the raw ones and the {@code jsonb} columns of their tables that no
     * entity maps. A mapped column the table doesn't have is an error, so a rename can't silently skip a column.
     */
    Columns columns(Connection connection) throws SQLException {
        List<TypedColumn> typed = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (Class<?> entity : ENTITIES) {
            String table = entity.getAnnotation(Table.class).name();
            Set<String> jsonbColumns = jsonbColumns(connection, table);
            for (Field field : entity.getDeclaredFields()) {
                JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                if (code == null || code.value() != SqlTypes.JSON) continue;
                String column = columnName(field);
                if (!jsonbColumns.remove(column)) {
                    throw new IllegalStateException(entity.getSimpleName() + "." + field.getName() + " maps "
                            + table + "." + column + ", which isn't a jsonb column");
                }
                if (field.getType() == String.class) {
                    raw.add(table + "." + column);
                } else {
                    typed.add(new TypedColumn(table, column, objectMapper.constructType(field.getGenericType())));
                }
            }
            jsonbColumns.forEach(column -> unmapped.add(table + "." + column));
        }
        return new Columns(typed, raw, unmapped);
    }

    private static Set<String> jsonbColumns(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ? AND data_type = 'jsonb' ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    // The column's name as the naming strategy gives it: the @Column name, or the field's name in snake case
    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    // ── Reporting ────────────────────────────────────────────────

    private static String recordsNotFitting(Object value) {
        if (value instanceof MonsterTemplates templates) return templates.unreadable();
        if (value instanceof SpellTemplate template) return template.unreadable();
        return null;
    }

    private static int firstDifference(String a, String b) {
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return length;
    }

    // About EXCERPT characters of the text around the given position
    static String excerpt(String text, int at) {
        int start = Math.max(0, Math.min(at - EXCERPT / 3, text.length() - EXCERPT));
        int end = Math.min(text.length(), start + EXCERPT);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    private static String firstLine(String message) {
        if (message == null) return "no message";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
