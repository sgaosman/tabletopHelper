package com.questkeeper.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final MonsterSeeder monsterSeeder;
    private final SpellSeeder spellSeeder;
    private final ConditionSeeder conditionSeeder;
    private final ItemSeeder itemSeeder;

    @Override
    public void run(String... args) {
        log.info("=== Starting 5e data seeding ===");
        long start = System.currentTimeMillis();

        try {
            conditionSeeder.seed();
        } catch (Exception e) {
            log.error("Failed to seed conditions: {}", e.getMessage(), e);
        }

        try {
            spellSeeder.seed();
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

        long elapsed = System.currentTimeMillis() - start;
        log.info("=== 5e data seeding complete in {}s ===", elapsed / 1000);
    }
}
