package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the action_templates JSONB column of the monsters from monster-action-definitions.json, matching each
 * entry to a monster by name and source, whatever their case and surrounding spaces. The first entry for a monster
 * wins, and a monster the file doesn't name keeps the templates it has.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonsterActionLoader implements ContentLoader {

    private static final String FILE = "data/monster-actions/monster-action-definitions.json";

    private final MonsterRepository monsterRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "monster-actions";
    }

    @Override
    public List<String> files() {
        return List.of(FILE);
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<String> dependsOn() {
        return List.of("monsters");
    }

    @Override
    public void load(LoadContext context) throws Exception {
        JsonNode actionDefinitions;
        try (InputStream is = new ClassPathResource(FILE).getInputStream()) {
            actionDefinitions = objectMapper.readTree(is);
        }

        // Build lookup map from the JSON: key = lowercase "name|source"
        Map<String, JsonNode> definitionMap = new LinkedHashMap<>();
        for (JsonNode entry : actionDefinitions) {
            String name = entry.has("monsterName") ? entry.get("monsterName").asText().trim() : "";
            String source = entry.has("source") ? entry.get("source").asText().trim() : "";
            if (!name.isEmpty() && !source.isEmpty()) {
                String key = name.toLowerCase() + "|" + source.toLowerCase();
                // If duplicate, first entry wins
                definitionMap.putIfAbsent(key, entry);
            }
        }

        // Every monster keyed by "name|source", the first of any that share one
        Map<String, Monster> monsterMap = monsterRepository.findAll().stream()
                .collect(Collectors.toMap(
                        m -> (m.getName() != null ? m.getName().trim().toLowerCase() : "") + "|"
                                + (m.getSource() != null ? m.getSource().trim().toLowerCase() : ""),
                        Function.identity(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new));

        int changed = 0;
        int unchanged = 0;
        int unmatched = 0;
        for (var monsterEntry : monsterMap.entrySet()) {
            JsonNode definition = definitionMap.get(monsterEntry.getKey());
            if (definition == null) {
                unmatched++;
                continue;
            }
            Monster monster = monsterEntry.getValue();
            String json = objectMapper.writeValueAsString(definition);
            if (context.sameJson(monster.getActionTemplates(), json)) {
                unchanged++;
            } else {
                monster.setActionTemplates(json);
                changed++;
            }
        }
        log.info("monster-actions: {} monsters have no definition", unmatched);
        context.count("monsters.action_templates", changed, unchanged, 0);
    }
}
