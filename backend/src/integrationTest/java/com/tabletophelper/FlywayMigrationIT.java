package com.tabletophelper;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FlywayMigrationIT extends IntegrationTest {

    // The foreign-key indexes D170 settles, created by V12
    private static final Map<String, String> D170_INDEXES = Map.of(
            "idx_pc_user_id", "player_characters.user_id",
            "idx_pc_campaign_id", "player_characters.campaign_id",
            "idx_ep_encounter_id", "encounter_participants.encounter_id",
            "idx_ep_character_id", "encounter_participants.character_id",
            "idx_cl_encounter_id", "combat_logs.encounter_id",
            "idx_enc_campaign_id", "encounters.campaign_id",
            "idx_cm_user_id", "campaign_members.user_id");

    // Foreign keys left without an index (D170): nothing filters on them, or they sit on small
    // reference tables. A new foreign key needs an index or an entry here. V26's natural keys index
    // subclasses.character_class_id and class_feature_pools.class_id, their first columns (R7).
    private static final Set<String> UNINDEXED_FOREIGN_KEYS = Set.of(
            "campaigns.dm_user_id",
            "encounter_participants.monster_id",
            "player_characters.race_id",
            "player_characters.class_id",
            "player_characters.subclass_id",
            "player_characters.background_id",
            "class_feature_pools.subclass_id",
            "class_feature_pools.pool_id",
            "monster_pool_triggers.pool_id");

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("Flyway applied V0–V6 and V8–V34 to the empty database; V7 was never used")
    void appliedVersionsMatchMigrationFiles() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions).containsExactly("0", "1", "2", "3", "4", "5", "6", "8", "9", "10", "11", "12", "13", "14", "15",
                "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33",
                "34");
    }

    @Test
    @DisplayName("V27 adds resource_pool_definitions.level_table, a nullable JSONB column")
    void levelTableColumn() {
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'resource_pool_definitions' AND column_name = 'level_table'"))
                .isEqualTo(Map.of("data_type", "jsonb", "is_nullable", "YES"));
    }

    @Test
    @DisplayName("V28 adds races.effects and subclasses.effects, nullable JSONB columns")
    void effectsColumns() {
        for (String table : List.of("races", "subclasses")) {
            assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = ? AND column_name = 'effects'", table))
                    .as(table).isEqualTo(Map.of("data_type", "jsonb", "is_nullable", "YES"));
        }
    }

    @Test
    @DisplayName("V29 drops the seven deprecated player_characters columns")
    void deprecatedCharacterColumnsDropped() {
        assertThat(jdbcTemplate.queryForList("SELECT column_name::text FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'player_characters' AND column_name IN "
                + "('hit_dice_total', 'hit_dice_remaining', 'hit_dice_map', 'feat_resources', 'prepared_spells', "
                + "'attuned_items', 'equipped_items')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE "
                + "table_schema = 'public' AND table_name = 'player_characters' AND column_name = 'hit_dice'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("V30 replaces the three action flags with encounter_participants.turn_economy, NOT NULL with the fresh record as its default, and the combat log takes the action economy's types")
    void turnEconomyColumn() {
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'encounter_participants' AND column_name = 'turn_economy'"))
                .isEqualTo(Map.of("data_type", "jsonb", "is_nullable", "NO"));
        assertThat(jdbcTemplate.queryForObject("SELECT column_default::text FROM information_schema.columns "
                + "WHERE table_name = 'encounter_participants' AND column_name = 'turn_economy'", String.class))
                .contains("\"actions\": 1").contains("\"actionsUsed\": 0").contains("\"reactionUsed\": false");
        assertThat(jdbcTemplate.queryForList("SELECT column_name::text FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'encounter_participants' AND column_name IN "
                + "('action_used', 'bonus_action_used', 'reaction_used')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'combat_logs_action_type_check'", String.class))
                .contains("'HP_SET'", "'REPEAT_SAVE'", "'POOL_USE'", "'STANDARD_ACTION'", "'OBJECT_INTERACTION'",
                        "'TURN_ECONOMY_SET'", "'REACTION_TAKEN'");
    }

    @Test
    @DisplayName("V31 adds the items' armour columns, stealth_disadvantage NOT NULL and false by default, and limits armour_category to the four categories")
    void armourColumns() {
        Map<String, String> expected = Map.of("armour_category", "character varying", "armour_class_base", "integer",
                "strength_requirement", "integer", "stealth_disadvantage", "boolean", "bonus_ac", "integer");
        expected.forEach((column, type) -> assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable "
                + "FROM information_schema.columns WHERE table_name = 'items' AND column_name = ?", column))
                .as(column).isEqualTo(Map.of("data_type", type,
                        "is_nullable", "stealth_disadvantage".equals(column) ? "NO" : "YES")));
        assertThat(jdbcTemplate.queryForObject("SELECT column_default::text FROM information_schema.columns "
                + "WHERE table_name = 'items' AND column_name = 'stealth_disadvantage'", String.class)).isEqualTo("false");
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'items_armour_category_check'", String.class))
                .contains("'LIGHT'", "'MEDIUM'", "'HEAVY'", "'SHIELD'");
    }

    @Test
    @DisplayName("V32 adds combat_commands, numbered per encounter, and the combat log's command_id, undoes_command_id and UNDO type")
    void combatCommands() {
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'combat_commands' AND column_name = 'before_state'"))
                .isEqualTo(Map.of("data_type", "jsonb", "is_nullable", "YES"));
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'combat_commands' AND column_name = 'barrier'"))
                .isEqualTo(Map.of("data_type", "boolean", "is_nullable", "NO"));
        for (String column : List.of("command_id", "undoes_command_id")) {
            assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'combat_logs' AND column_name = ?", column))
                    .as(column).isEqualTo(Map.of("data_type", "uuid", "is_nullable", "YES"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'uq_combat_commands_encounter_sequence'", String.class))
                .isEqualTo("UNIQUE (encounter_id, sequence)");
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'combat_logs_action_type_check'", String.class))
                .contains("'REACTION_TAKEN'", "'UNDO'");
    }

    @Test
    @DisplayName("V33 replaces active_spell and active_spell_slot_level with encounter_participants.active_effects, NOT NULL and an empty list by default, and the combat log takes SPELL_EFFECT_END")
    void spellEffectsColumn() {
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'encounter_participants' AND column_name = 'active_effects'"))
                .isEqualTo(Map.of("data_type", "jsonb", "is_nullable", "NO"));
        assertThat(jdbcTemplate.queryForObject("SELECT column_default::text FROM information_schema.columns "
                + "WHERE table_name = 'encounter_participants' AND column_name = 'active_effects'", String.class))
                .isEqualTo("'[]'::jsonb");
        assertThat(jdbcTemplate.queryForList("SELECT column_name::text FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'encounter_participants' AND column_name IN "
                + "('active_spell', 'active_spell_slot_level')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'combat_logs_action_type_check'", String.class))
                .contains("'SPELL_EFFECT_REPEAT'", "'SPELL_EFFECT_END'", "'UNDO'");
    }

    @Test
    @DisplayName("V34 adds the summoning columns to encounter_participants, the campaigns' setting, and SUMMON and SUMMON_END")
    void summoningColumns() {
        assertThat(jdbcTemplate.queryForList("SELECT column_name::text AS name, data_type::text AS type, "
                + "is_nullable::text AS nullable FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'encounter_participants' AND column_name IN ('summoned_by_participant_id', "
                + "'summon', 'action_templates', 'dismissed') ORDER BY column_name"))
                .containsExactly(
                        Map.of("name", "action_templates", "type", "jsonb", "nullable", "YES"),
                        Map.of("name", "dismissed", "type", "boolean", "nullable", "NO"),
                        Map.of("name", "summon", "type", "jsonb", "nullable", "YES"),
                        Map.of("name", "summoned_by_participant_id", "type", "uuid", "nullable", "YES"));
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'fk_encounter_participants_summoned_by'", String.class))
                .contains("REFERENCES encounter_participants(id) ON DELETE SET NULL");
        assertThat(jdbcTemplate.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'campaigns' "
                + "AND column_name = 'dm_chooses_conjured_creatures'"))
                .isEqualTo(Map.of("data_type", "boolean", "is_nullable", "NO"));
        assertThat(jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'combat_logs_action_type_check'", String.class))
                .contains("'SUMMON'", "'SUMMON_END'", "'SPELL_EFFECT_END'");
    }

    @Test
    @DisplayName("Hibernate's schema validation passes against the migrated schema")
    void hibernateSchemaValidationPasses() {
        var schemaManager = entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager();

        assertThatCode(schemaManager::validateMappedObjects).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V12 creates the seven D170 foreign-key indexes on the right columns")
    void foreignKeyIndexesExist() {
        Map<String, String> indexes = jdbcTemplate.queryForList("""
                        SELECT i.relname::text AS index_name, t.relname::text || '.' || a.attname::text AS first_column
                        FROM pg_index x
                        JOIN pg_class i ON i.oid = x.indexrelid
                        JOIN pg_class t ON t.oid = x.indrelid
                        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.indkey[0]
                        WHERE t.relnamespace = 'public'::regnamespace
                        """).stream()
                .collect(Collectors.toMap(row -> (String) row.get("index_name"), row -> (String) row.get("first_column")));

        assertThat(indexes).containsAllEntriesOf(D170_INDEXES);
    }

    @Test
    @DisplayName("Every foreign key has an index, unless it is on the allow-list")
    void everyForeignKeyIsIndexedOrAllowListed() {
        // An index covers a foreign key when the key's columns are its leading columns
        Set<String> unindexed = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT c.conrelid::regclass::text || '.' || a.attname::text
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f'
                  AND c.connamespace = 'public'::regnamespace
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index x
                      WHERE x.indrelid = c.conrelid
                        AND (x.indkey::int2[])[0:cardinality(c.conkey) - 1] = c.conkey)
                """, String.class));

        assertThat(unindexed).as("foreign keys without an index").isSubsetOf(UNINDEXED_FOREIGN_KEYS);
        assertThat(UNINDEXED_FOREIGN_KEYS).as("allow-listed foreign keys that now have an index").isSubsetOf(unindexed);
    }
}
