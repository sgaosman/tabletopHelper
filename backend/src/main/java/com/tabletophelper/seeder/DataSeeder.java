package com.tabletophelper.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final MonsterSeeder monsterSeeder;
    private final SpellSeeder spellSeeder;
    private final ConditionSeeder conditionSeeder;
    private final ItemSeeder itemSeeder;
    private final RaceSeeder raceSeeder;
    private final CharacterClassSeeder characterClassSeeder;
    private final BackgroundSeeder backgroundSeeder;
    private final FeatSeeder featSeeder;
    private final OptionalFeatureSeeder optionalFeatureSeeder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        log.info("=== Starting 5e data seeding ===");
        long start = System.currentTimeMillis();

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS fuzzystrmatch");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_monsters_name_trgm ON monsters USING gin (LOWER(name) gin_trgm_ops)");
        log.info("PostgreSQL extensions and indexes ensured");

        cleanupLegacyCharacters();

        try {
            conditionSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed conditions: {}", e.getMessage(), e);
        }

        try {
            spellSeeder.seed();
            spellSeeder.seedEffectTemplates();
        } catch (Exception e) {
            log.error("Failed to seed spells: {}", e.getMessage(), e);
        }

        try {
            itemSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed items: {}", e.getMessage(), e);
        }

        try {
            monsterSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed monsters: {}", e.getMessage(), e);
        }

        try {
            raceSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed races: {}", e.getMessage(), e);
        }

        try {
            characterClassSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed classes/subclasses: {}", e.getMessage(), e);
        }

        try {
            backgroundSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed backgrounds: {}", e.getMessage(), e);
        }

        try {
            featSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed feats: {}", e.getMessage(), e);
        }

        try {
            optionalFeatureSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed optional features: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("=== 5e data seeding complete in {}s ===", elapsed / 1000);
    }

    private void cleanupLegacyCharacters() {
        try {
            boolean hasRaceIdColumn = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'player_characters' AND column_name = 'race_id')",
                    Boolean.class));

            String countQuery = hasRaceIdColumn
                    ? "SELECT COUNT(*) FROM player_characters WHERE race_id IS NULL"
                    : "SELECT COUNT(*) FROM player_characters";

            Integer charCount = jdbcTemplate.queryForObject(countQuery, Integer.class);
            if (charCount != null && charCount > 0) {
                jdbcTemplate.execute("DELETE FROM encounter_participants WHERE character_id IS NOT NULL");
                jdbcTemplate.execute("DELETE FROM player_characters");
                log.info("Deleted {} legacy free-text characters and their encounter participant references", charCount);
            }
        } catch (Exception e) {
            log.warn("Legacy character cleanup skipped: {}", e.getMessage());
        }
    }
}
