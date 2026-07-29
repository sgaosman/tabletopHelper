package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seeds the action_templates JSONB column on Monster entities by loading
 * monster-action-definitions.json and matching entries to existing monsters
 * by name and source. Follows the pattern of SpellSeeder.seedEffectTemplates().
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonsterActionSeeder {

    private final MonsterRepository monsterRepository;
    private final ObjectMapper objectMapper;

    /**
     * Seeds action templates if not already populated.
     * Idempotent — only updates monsters where actionTemplates is null.
     */
    public void seed() {
        int batchSize = 200;
        int totalUpdated = 0;

        // Count how many monsters still need seeding
        var countPage = monsterRepository.findAllWithoutActionTemplates(PageRequest.of(0, 1));
        if (countPage.isEmpty()) {
            log.info("MonsterActionSeeder: all monsters already have action_templates, skipping");
            return;
        }

        log.info("MonsterActionSeeder: loading monster-action-definitions.json");
        JsonNode actionDefinitions;
        try {
            actionDefinitions = objectMapper.readTree(
                    new ClassPathResource("data/monster-actions/monster-action-definitions.json").getInputStream());
        } catch (Exception e) {
            log.error("MonsterActionSeeder: failed to read monster-action-definitions.json: {}", e.getMessage(), e);
            return;
        }

        // Build lookup map from the JSON: key = lowercase "name|source"
        Map<String, JsonNode> definitionMap = new java.util.LinkedHashMap<>();
        for (JsonNode entry : actionDefinitions) {
            String name = entry.has("monsterName") ? entry.get("monsterName").asText().trim() : "";
            String source = entry.has("source") ? entry.get("source").asText().trim() : "";
            if (!name.isEmpty() && !source.isEmpty()) {
                String key = name.toLowerCase() + "|" + source.toLowerCase();
                // If duplicate, first entry wins
                definitionMap.putIfAbsent(key, entry);
            }
        }
        log.info("MonsterActionSeeder: loaded {} unique action definitions", definitionMap.size());

        // Build a map of all monsters keyed by "name|source" for fast lookup
        List<Monster> allMonsters = monsterRepository.findAll();
        Map<String, Monster> monsterMap = allMonsters.stream()
                .filter(m -> m.getActionTemplates() == null)
                .collect(Collectors.toMap(
                        m -> (m.getName() != null ? m.getName().trim().toLowerCase() : "") + "|"
                                + (m.getSource() != null ? m.getSource().trim().toLowerCase() : ""),
                        Function.identity(),
                        (existing, replacement) -> existing)); // first wins

        log.info("MonsterActionSeeder: {} monsters without action_templates out of {} total",
                monsterMap.size(), allMonsters.size());

        // Match and set
        int unmatched = 0;
        java.util.List<Monster> batch = new java.util.ArrayList<>();

        for (var monsterEntry : monsterMap.entrySet()) {
            String lookupKey = monsterEntry.getKey();
            Monster monster = monsterEntry.getValue();
            JsonNode definition = definitionMap.get(lookupKey);

            if (definition != null) {
                try {
                    String json = objectMapper.writeValueAsString(definition);
                    monster.setActionTemplates(json);
                    batch.add(monster);

                    if (batch.size() >= batchSize) {
                        monsterRepository.saveAll(batch);
                        totalUpdated += batch.size();
                        batch.clear();
                        log.info("MonsterActionSeeder: saved batch, total updated so far: {}", totalUpdated);
                    }
                } catch (Exception e) {
                    log.warn("MonsterActionSeeder: failed to serialize action template for {}: {}",
                            monster.getName(), e.getMessage());
                }
            } else {
                unmatched++;
            }
        }

        // Save remaining batch
        if (!batch.isEmpty()) {
            monsterRepository.saveAll(batch);
            totalUpdated += batch.size();
        }

        log.info("MonsterActionSeeder: complete — {} monsters updated with action_templates, {} unmatched (no definition found)",
                totalUpdated, unmatched);
    }
}
