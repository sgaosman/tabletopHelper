package com.tabletophelper.content.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What one loader run writes, table by table, and the upsert by natural key every loader uses (D169). Existing rows
 * keep their IDs and creation times: each column whose value differs is copied onto the row, a JSON column only when
 * its JSON differs rather than its text, so a row whose content is the same isn't written at all. Rows the data no
 * longer has are kept and logged.
 */
@Slf4j
public class LoadContext {

    // How many of the keys a row missing from the data is logged with
    private static final int MISSING_LOGGED = 10;

    /** What the run did to one table. */
    public record TableCounts(String table, int added, int changed, int unchanged, int missing, int duplicates) {
        int rows() {
            return added + changed + unchanged;
        }

        @Override
        public String toString() {
            return table + ": " + added + " added, " + changed + " changed, " + unchanged + " unchanged"
                    + (missing > 0 ? ", " + missing + " missing from the data (kept)" : "")
                    + (duplicates > 0 ? ", " + duplicates + " repeated keys in the data skipped" : "");
        }
    }

    /** One of a loader's files: the name it is reported by, and where it is read from. */
    public record DataFile(String name, Resource resource) {
    }

    /** Reads a row, or a part of one, and may throw. */
    @FunctionalInterface
    public interface RowReader<T> {
        T read() throws Exception;
    }

    private final ObjectMapper objectMapper;
    private final Function<String, String> location;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final List<TableCounts> counts = new ArrayList<>();
    private final Map<String, Integer> skips = new LinkedHashMap<>();

    public LoadContext(ObjectMapper objectMapper) {
        this(objectMapper, path -> "classpath:" + path);
    }

    /**
     * @param location where a file or pattern the loaders name, such as {@code data/5etools/spells/spells-*.json},
     *                 is read from; the bundled files on the classpath unless a test reads others
     */
    public LoadContext(ObjectMapper objectMapper, Function<String, String> location) {
        this.objectMapper = objectMapper;
        this.location = location;
    }

    public List<TableCounts> counts() {
        return List.copyOf(counts);
    }

    /** How many rows each skip rule left out, by the rule's name, in the order the rules were first used. */
    public Map<String, Integer> skips() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(skips));
    }

    /**
     * Counts a row (or file) the data has but the loader leaves out on purpose, under the name of the rule that
     * leaves it out, such as "_copy subraces". A row that can't be read is never skipped: it fails the load.
     */
    public void skip(String rule) {
        skips.merge(rule, 1, Integer::sum);
    }

    // ── Reading files ────────────────────────────────────────────

    /** The files matching a pattern, in the order the classpath lists them. A pattern that matches none fails. */
    public List<DataFile> files(String pattern) {
        Resource[] resources;
        try {
            resources = resolver.getResources(location.apply(pattern));
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + pattern + ": " + e.getMessage(), e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("No file matches " + pattern);
        }
        return Arrays.stream(resources).map(r -> new DataFile(r.getFilename(), r)).toList();
    }

    /** Reads one file as JSON; a file that is missing or isn't JSON fails the load, naming the file. */
    public JsonNode read(String file) {
        return read(new DataFile(file, resolver.getResource(location.apply(file))));
    }

    /** Reads one of {@link #files}' files as JSON; a file that isn't JSON fails the load, naming the file. */
    public JsonNode read(DataFile file) {
        try (InputStream in = file.resource().getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(file.name() + " could not be read: " + e.getMessage(), e);
        }
    }

    /** The array a file keeps its rows under; a file without it fails the load. */
    public static JsonNode array(JsonNode root, String key, String file) {
        JsonNode rows = root.get(key);
        if (rows == null || !rows.isArray()) {
            throw new IllegalStateException(file + " has no \"" + key + "\" array");
        }
        return rows;
    }

    /**
     * Reads one row, or a part of one. If it throws, the load fails with what was being read named first, such as
     * {@code spell Fireball|PHB: ...}, so the log says which row of which file couldn't be read.
     */
    public static <T> T reading(String what, RowReader<T> reader) {
        try {
            return reader.read();
        } catch (Exception e) {
            throw new IllegalStateException(what + ": " + e.getMessage(), e);
        }
    }

    /** The rows the data gave, in every table the run wrote. */
    public int rowCount() {
        return counts.stream().mapToInt(TableCounts::rows).sum();
    }

    /** Records the counts of a table that a loader updated without {@link #upsert}. */
    public void count(String table, int changed, int unchanged, int missing) {
        counts.add(new TableCounts(table, 0, changed, unchanged, missing, 0));
    }

    /**
     * Writes the parsed rows over the table's rows by natural key, and returns every row the data gave by that key,
     * as now stored. The first parsed row with a key wins; a later one is counted and skipped.
     *
     * @param ownedElsewhere columns another loader writes, which this one leaves alone
     */
    public <T> Map<String, T> upsert(String table, JpaRepository<T, ?> repository, List<T> parsed,
                                     Function<T, String> key, String... ownedElsewhere) {
        Map<String, T> existing = new LinkedHashMap<>();
        for (T row : repository.findAll()) {
            existing.putIfAbsent(key.apply(row), row);
        }
        Set<String> skipped = Set.of(ownedElsewhere);

        // A stored row is managed, so the transaction writes the columns copied onto it; a new row is saved
        Map<String, T> result = new LinkedHashMap<>();
        Map<String, T> added = new LinkedHashMap<>();
        int changed = 0;
        int unchanged = 0;
        int duplicates = 0;
        for (T row : parsed) {
            String rowKey = key.apply(row);
            if (result.containsKey(rowKey) || added.containsKey(rowKey)) {
                duplicates++;
                continue;
            }
            T stored = existing.get(rowKey);
            if (stored == null) {
                added.put(rowKey, row);
            } else {
                if (copyChanges(row, stored, skipped)) {
                    changed++;
                } else {
                    unchanged++;
                }
                result.put(rowKey, stored);
            }
        }
        if (!added.isEmpty()) {
            // The rows as saved, since a row with an assigned ID is merged into a new instance
            List<T> saved = repository.saveAll(added.values());
            List<String> addedKeys = new ArrayList<>(added.keySet());
            for (int i = 0; i < addedKeys.size(); i++) {
                result.put(addedKeys.get(i), saved.get(i));
            }
        }

        List<String> missing = existing.keySet().stream().filter(k -> !result.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            log.warn("{}: {} rows are no longer in the data and are kept: {}{}", table, missing.size(),
                    missing.subList(0, Math.min(MISSING_LOGGED, missing.size())),
                    missing.size() > MISSING_LOGGED ? " and " + (missing.size() - MISSING_LOGGED) + " more" : "");
        }
        if (duplicates > 0) {
            log.info("{}: the data repeats {} natural keys; the first row of each was loaded", table, duplicates);
        }
        counts.add(new TableCounts(table, added.size(), changed, unchanged, missing.size(), duplicates));
        return result;
    }

    /** The natural key of a row known by its name and source, as V26's indexes and content-snapshot read it. */
    public static String nameAndSource(String name, String source) {
        return name + "|" + (source != null ? source : "");
    }

    /** Whether two JSON texts hold the same JSON, whatever their spacing and key order; null equals only null. */
    public boolean sameJson(String a, String b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        return readTree(a).equals(readTree(b));
    }

    /** The JSON text with object keys sorted, so that two texts of the same JSON give the same key. */
    public String canonicalJson(String json) {
        try {
            Object value = objectMapper.treeToValue(readTree(json), Object.class);
            return objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write JSON back: " + json, e);
        }
    }

    // ── Copying a row's columns ──────────────────────────────────

    // Copies each content column whose value differs from the parsed row onto the stored one
    private boolean copyChanges(Object parsed, Object stored, Set<String> skipped) {
        BeanWrapper from = new BeanWrapperImpl(parsed);
        BeanWrapper to = new BeanWrapperImpl(stored);
        boolean changed = false;
        for (Field field : contentFields(stored.getClass())) {
            if (skipped.contains(field.getName())) continue;
            Object newValue = from.getPropertyValue(field.getName());
            Object oldValue = to.getPropertyValue(field.getName());
            if (!same(field, oldValue, newValue)) {
                to.setPropertyValue(field.getName(), newValue);
                changed = true;
            }
        }
        return changed;
    }

    // The persistent fields other than the ID, the timestamps and the version
    private static List<Field> contentFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
                .filter(f -> !f.isAnnotationPresent(Id.class) && !f.isAnnotationPresent(Transient.class)
                        && !f.isAnnotationPresent(Version.class) && !f.isAnnotationPresent(CreationTimestamp.class)
                        && !f.isAnnotationPresent(UpdateTimestamp.class))
                .toList();
    }

    private boolean same(Field field, Object oldValue, Object newValue) {
        JdbcTypeCode typeCode = field.getAnnotation(JdbcTypeCode.class);
        if (typeCode != null && typeCode.value() == SqlTypes.JSON && field.getType() == String.class) {
            return sameJson((String) oldValue, (String) newValue);
        }
        if (field.getType().isAnnotationPresent(Entity.class)) {
            return Objects.equals(idOf(oldValue), idOf(newValue));
        }
        return Objects.equals(oldValue, newValue);
    }

    // A related row's ID, read through its getter, which a lazy proxy answers without loading the row
    private static Object idOf(Object entity) {
        if (entity == null) return null;
        Class<?> type = entity.getClass();
        while (type != null && !type.isAnnotationPresent(Entity.class)) {
            type = type.getSuperclass();
        }
        if (type == null) {
            throw new IllegalStateException(entity.getClass() + " is not an entity");
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return new BeanWrapperImpl(entity).getPropertyValue(field.getName());
            }
        }
        throw new IllegalStateException(type + " has no @Id field");
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A JSON column holds text that isn't JSON: " + json, e);
        }
    }
}
