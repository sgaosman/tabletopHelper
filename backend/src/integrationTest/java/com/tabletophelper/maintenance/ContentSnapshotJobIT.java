package com.tabletophelper.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The content-snapshot job (D169): its files for each of the 13 reference tables, by ID and by natural key, and its
 * comparison with a snapshot taken earlier, against reference rows inserted here.
 */
class ContentSnapshotJobIT extends IntegrationTest {

    private static final List<String> TABLES = List.of("races", "character_classes", "subclasses", "backgrounds",
            "spells", "monsters", "conditions", "items", "feats", "optional_features", "resource_pool_definitions",
            "class_feature_pools", "monster_pool_triggers");

    @Autowired private DataSource dataSource;

    @TempDir private Path directory;

    private UUID elf;
    private UUID dwarf;
    private UUID fireball;

    private ContentSnapshotJob job(String output, String compare, String compareBy) {
        return new ContentSnapshotJob(dataSource, objectMapper, output, compare, compareBy);
    }

    private MaintenanceReport snapshot(Path output) {
        return job(output.toString(), "", "both").run(false);
    }

    private MaintenanceReport compareWith(Path snapshot, String compareBy) {
        return job("", snapshot.toString(), compareBy).run(false);
    }

    @BeforeEach
    void insertReferenceRows() {
        elf = insert("INSERT INTO races (id, name, source, size) VALUES (?, 'Elf', 'PHB', 'M')");
        dwarf = insert("INSERT INTO races (id, name, source, size) VALUES (?, 'Dwarf', 'PHB', 'M')");
        UUID fighter = insert("INSERT INTO character_classes (id, name, source, hit_dice) VALUES (?, 'Fighter', 'PHB', 10)");
        UUID battleMaster = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO subclasses (id, name, source, character_class_id) "
                + "VALUES (?, 'Battle Master', 'PHB', ?)", battleMaster, fighter);
        fireball = insert("INSERT INTO spells (id, name, level, source, description) "
                + "VALUES (?, 'Fireball', 3, 'PHB', 'A bright streak')");
        jdbcTemplate.update("INSERT INTO conditions (id, name, description, source) "
                + "VALUES (gen_random_uuid(), 'Prone', 'Lying down', 'PHB')");
        jdbcTemplate.update("INSERT INTO resource_pool_definitions (pool_id, display_name, source_type, "
                + "max_uses_formula, reset_on) VALUES ('subclass:battle-master-superiority-dice', 'Superiority Dice', "
                + "'CLASS', '4', 'shortRest')");
        jdbcTemplate.update("INSERT INTO class_feature_pools (id, class_id, subclass_id, min_level, pool_id) "
                + "VALUES (gen_random_uuid(), ?, ?, 3, 'subclass:battle-master-superiority-dice')", fighter, battleMaster);
        jdbcTemplate.update("INSERT INTO monster_pool_triggers (id, pool_id, trigger_condition, priority) "
                + "VALUES (gen_random_uuid(), 'subclass:battle-master-superiority-dice', '{\"hasField\": \"legendary\"}', 1)");
    }

    private UUID insert(String sql) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(sql, id);
        return id;
    }

    private List<JsonNode> lines(Path file) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lines.add(objectMapper.readTree(line));
        }
        return lines;
    }

    @Test
    @DisplayName("Writes each reference table by ID and by natural key, sorted, the by-key rows without IDs or timestamps")
    void writesEveryTable() throws IOException {
        Path output = directory.resolve("before");

        MaintenanceReport report = snapshot(output);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(9);
        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        for (String table : TABLES) {
            assertThat(output.resolve(table + ".by-id.jsonl")).exists();
            assertThat(output.resolve(table + ".by-key.jsonl")).exists();
        }

        List<JsonNode> racesById = lines(output.resolve("races.by-id.jsonl"));
        assertThat(racesById).extracting(line -> line.path("id").asText())
                .containsExactlyElementsOf(List.of(elf.toString(), dwarf.toString()).stream().sorted().toList());
        assertThat(racesById.get(0).path("row").path("id").asText()).isEqualTo(racesById.get(0).path("id").asText());

        List<JsonNode> racesByKey = lines(output.resolve("races.by-key.jsonl"));
        assertThat(racesByKey).extracting(line -> line.path("key").asText()).containsExactly("Dwarf|PHB", "Elf|PHB");
        JsonNode dwarfRow = racesByKey.get(0);
        assertThat(dwarfRow.path("id").asText()).isEqualTo(dwarf.toString());
        assertThat(dwarfRow.path("row").has("id")).isFalse();
        assertThat(dwarfRow.path("row").has("created_at")).isFalse();
        assertThat(dwarfRow.path("row").path("name").asText()).isEqualTo("Dwarf");

        JsonNode subclass = lines(output.resolve("subclasses.by-key.jsonl")).get(0);
        assertThat(subclass.path("key").asText()).isEqualTo("Fighter|PHB|Battle Master|PHB");
        assertThat(subclass.path("row").has("character_class_id")).isFalse();
        assertThat(subclass.path("row").path("class").asText()).isEqualTo("Fighter|PHB");

        JsonNode pool = lines(output.resolve("class_feature_pools.by-key.jsonl")).get(0);
        assertThat(pool.path("key").asText())
                .isEqualTo("Fighter|PHB|Battle Master|PHB|subclass:battle-master-superiority-dice");
        assertThat(pool.path("row").has("class_id")).isFalse();
        assertThat(pool.path("row").has("subclass_id")).isFalse();
        assertThat(pool.path("row").path("subclass").asText()).isEqualTo("Battle Master|PHB");
        assertThat(pool.path("row").path("min_level").asInt()).isEqualTo(3);

        JsonNode definition = lines(output.resolve("resource_pool_definitions.by-id.jsonl")).get(0);
        assertThat(definition.path("id").asText()).isEqualTo("subclass:battle-master-superiority-dice");

        assertThat(lines(output.resolve("monster_pool_triggers.by-key.jsonl")).get(0).path("key").asText())
                .isEqualTo("subclass:battle-master-superiority-dice|{\"hasField\": \"legendary\"}");
        assertThat(lines(output.resolve("conditions.by-key.jsonl")).get(0).path("key").asText()).isEqualTo("Prone");
        assertThat(Files.readAllLines(output.resolve("monsters.by-id.jsonl"))).isEmpty();
    }

    @Test
    @DisplayName("Compares with an earlier snapshot: rows added, deleted and changed by ID and by natural key, and keys whose ID changed")
    void comparesWithAnEarlierSnapshot() {
        Path before = directory.resolve("before");
        snapshot(before);
        jdbcTemplate.update("UPDATE spells SET description = 'A brighter streak' WHERE id = ?", fireball);
        jdbcTemplate.update("DELETE FROM races WHERE id = ?", dwarf);
        UUID gnome = insert("INSERT INTO races (id, name, source, size) VALUES (?, 'Gnome', 'PHB', 'S')");
        // Elf again, the same in every column but its ID
        jdbcTemplate.update("DELETE FROM races WHERE id = ?", elf);
        UUID newElf = insert("INSERT INTO races (id, name, source, size) VALUES (?, 'Elf', 'PHB', 'M')");

        MaintenanceReport report = compareWith(before, "both");

        assertThat(report.examined()).isEqualTo(9);
        assertThat(report.changes()).containsExactlyInAnyOrder(
                "by ID: races " + dwarf + " (Dwarf): deleted",
                "by ID: races " + elf + " (Elf): deleted",
                "by ID: races " + gnome + " (Gnome): added",
                "by ID: races " + newElf + " (Elf): added",
                "by ID: spells " + fireball + " (Fireball)\n      description: \"A bright streak\" → \"A brighter streak\"",
                "by natural key: races Dwarf|PHB: deleted",
                "by natural key: races Gnome|PHB: added",
                "by natural key: spells Fireball|PHB\n      description: \"A bright streak\" → \"A brighter streak\"",
                "by natural key: races Elf|PHB: ID " + elf + " → " + newElf);
        assertThat(report.render()).contains("these are the differences from the snapshot in " + before);

        assertThat(compareWith(before, "key").changes()).allMatch(change -> change.startsWith("by natural key: "))
                .hasSize(4);
        assertThat(compareWith(before, "id").changes()).allMatch(change -> change.startsWith("by ID: ")).hasSize(5);
    }

    @Test
    @DisplayName("Writes a snapshot and compares with another in the same run, and an unchanged database has no differences")
    void writesAndCompares() {
        Path before = directory.resolve("before");
        snapshot(before);
        Path after = directory.resolve("after");

        MaintenanceReport report = job(after.toString(), before.toString(), "both").run(false);

        assertThat(report.changes()).isEmpty();
        assertThat(after.resolve("spells.by-key.jsonl")).hasSameTextualContentAs(before.resolve("spells.by-key.jsonl"));
    }

    @Test
    @DisplayName("A natural key that two rows share is a warning, and both rows are kept, told apart by a number")
    void sharedNaturalKeyIsAWarning() throws IOException {
        UUID secondElf = insert("INSERT INTO races (id, name, source, size) VALUES (?, 'Elf', 'PHB', 'M')");
        Path output = directory.resolve("shared");

        MaintenanceReport report = snapshot(output);

        List<String> ids = List.of(elf.toString(), secondElf.toString()).stream().sorted().toList();
        assertThat(report.warnings()).containsExactly(
                "races Elf|PHB: 2 rows share this natural key (" + ids.get(0) + ", " + ids.get(1) + ")");
        assertThat(lines(output.resolve("races.by-key.jsonl")))
                .extracting(line -> line.path("key").asText() + " " + line.path("id").asText())
                .containsExactly("Dwarf|PHB " + dwarf, "Elf|PHB " + ids.get(0), "Elf|PHB #2 " + ids.get(1));
    }

    @Test
    @DisplayName("Refuses to apply, to run with neither directory, to overwrite a snapshot, or to compare with a directory that has none")
    void refusals() {
        Path before = directory.resolve("before");
        snapshot(before);

        assertThatThrownBy(() -> job(directory.resolve("other").toString(), "", "both").run(true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never writes to the database");
        assertThatThrownBy(() -> job("", "", "both").run(false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--maintenance.output=<dir>");
        assertThatThrownBy(() -> snapshot(before))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already holds a snapshot");
        assertThatThrownBy(() -> compareWith(directory.resolve("empty"), "both"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is missing");
        assertThatThrownBy(() -> compareWith(before, "name"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--maintenance.compare-by");
    }
}
