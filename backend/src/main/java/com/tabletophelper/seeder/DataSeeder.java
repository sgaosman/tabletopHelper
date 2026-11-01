package com.tabletophelper.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Runs before any other startup runner, so maintenance jobs see fully seeded reference data
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
    private final ResourcePoolSeeder resourcePoolSeeder;
    private final MonsterActionSeeder monsterActionSeeder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        log.info("=== Starting 5e data seeding ===");
        long start = System.currentTimeMillis();

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS fuzzystrmatch");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_monsters_name_trgm ON monsters USING gin (LOWER(name) gin_trgm_ops)");
        log.info("PostgreSQL extensions and indexes ensured");

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
            raceSeeder.fixRaceSpellLevels();
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

        try {
            resourcePoolSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed resource pools: {}", e.getMessage(), e);
        }

        try {
            monsterActionSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed monster action templates: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("=== 5e data seeding complete in {}s ===", elapsed / 1000);
    }
}
