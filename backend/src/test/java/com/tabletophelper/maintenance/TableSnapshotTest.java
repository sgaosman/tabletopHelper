package com.tabletophelper.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TableSnapshotTest {

    private final TableSnapshot snapshot = new TableSnapshot(new ObjectMapper());

    private Map<String, Map<String, JsonNode>> tables(String table, Map<String, String> rows) {
        Map<String, JsonNode> parsed = new TreeMap<>();
        rows.forEach((key, json) -> parsed.put(key, snapshot.readTree(json)));
        return Map.of(table, parsed);
    }

    @Test
    @DisplayName("Reports each row deleted, added and changed, a changed row with each differing column before and after")
    void reportsDeletedAddedAndChangedRows() {
        var before = tables("spells", Map.of(
                "1", "{\"name\": \"Fireball\", \"level\": 3}",
                "2", "{\"name\": \"Shield\", \"level\": 1}"));
        var after = tables("spells", Map.of(
                "1", "{\"name\": \"Fireball\", \"level\": 4}",
                "3", "{\"name\": \"Light\", \"level\": 0}"));

        List<String> changes = TableSnapshot.compare(before, after, true);

        assertEquals(List.of("spells 1 (Fireball)\n      level: 3 → 4", "spells 2 (Shield): deleted",
                "spells 3 (Light): added"), changes);
    }

    @Test
    @DisplayName("A column added as NULL, or dropped while NULL, changes nothing; unlabelled rows are named by their key alone")
    void nullColumnsChangeNothing() {
        var before = tables("races", Map.of("Elf|PHB", "{\"name\": \"Elf\", \"darkvision\": null}"));
        var after = tables("races", Map.of("Elf|PHB", "{\"name\": \"Elf\", \"effects\": null, \"size\": \"M\"}"));

        // A column the old row lacks shows as nothing before the arrow
        assertEquals(List.of("races Elf|PHB\n      size:  → \"M\""), TableSnapshot.compare(before, after, false));
    }
}
