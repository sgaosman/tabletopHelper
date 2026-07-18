package com.questkeeper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questkeeper.reference.Condition;
import com.questkeeper.reference.ConditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConditionSeeder {

    private final ConditionRepository conditionRepository;
    private final ObjectMapper objectMapper;

    public void seed() throws Exception {
        if (conditionRepository.count() > 0) {
            log.info("Conditions already seeded, skipping");
            return;
        }

        ClassPathResource resource = new ClassPathResource("data/5etools/conditionsdiseases.json");
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode conditions = root.get("condition");
            if (conditions == null || !conditions.isArray()) return;

            List<Condition> batch = new ArrayList<>();
            for (JsonNode c : conditions) {
                Condition condition = parseCondition(c);
                if (condition != null) batch.add(condition);
            }

            conditionRepository.saveAll(batch);
            log.info("Condition seeding complete: {} conditions", batch.size());
        }
    }

    private Condition parseCondition(JsonNode c) {
        try {
            String description = flattenEntries(c.get("entries"));
            List<String> effects = extractEffects(c.get("entries"));

            return Condition.builder()
                    .name(c.path("name").asText())
                    .description(description != null ? description : "")
                    .effects(effects.isEmpty() ? null : objectMapper.writeValueAsString(effects))
                    .source(c.path("source").asText(null))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse condition {}: {}", c.path("name").asText("unknown"), e.getMessage());
            return null;
        }
    }

    private List<String> extractEffects(JsonNode entries) {
        List<String> effects = new ArrayList<>();
        if (entries == null) return effects;
        for (JsonNode entry : entries) {
            if (entry.isObject() && "list".equals(entry.path("type").asText())) {
                JsonNode items = entry.get("items");
                if (items != null) {
                    for (JsonNode item : items) {
                        if (item.isTextual()) {
                            effects.add(FiveEToolsMarkupParser.parse(item.asText()));
                        }
                    }
                }
            }
        }
        return effects;
    }

    private String flattenEntries(JsonNode entries) {
        if (entries == null) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            if (entry.isTextual()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asText()));
            } else if (entry.isObject()) {
                String type = entry.path("type").asText("");
                if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (sb.length() > 0) sb.append("\n");
                        if (item.isTextual()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                        }
                    }
                } else if ("table".equals(type) && entry.has("rows")) {
                    JsonNode rows = entry.get("rows");
                    for (JsonNode row : rows) {
                        if (row.isArray() && row.size() >= 2) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(row.get(0).asText()))
                              .append(": ").append(FiveEToolsMarkupParser.parse(row.get(1).asText()));
                        }
                    }
                } else if ("entries".equals(type) && entry.has("entries")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(flattenEntries(entry.get("entries")));
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
