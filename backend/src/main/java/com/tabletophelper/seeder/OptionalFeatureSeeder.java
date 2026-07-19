package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class OptionalFeatureSeeder {

    private static final Set<String> RELEVANT_TYPES = Set.of(
            "EI", "MM", "MV:B",
            "FS:F", "FS:R", "FS:P", "FS:B"
    );

    private final OptionalFeatureRepository repository;
    private final ObjectMapper objectMapper;

    public void seed() throws Exception {
        if (repository.count() > 0) {
            log.info("Optional features already seeded, skipping");
            return;
        }

        ClassPathResource resource = new ClassPathResource("data/5etools/optionalfeatures.json");
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode features = root.get("optionalfeature");
            if (features == null || !features.isArray()) return;

            List<OptionalFeature> batch = new ArrayList<>();
            for (JsonNode feature : features) {
                try {
                    List<OptionalFeature> entities = parseFeature(feature);
                    batch.addAll(entities);
                } catch (Exception e) {
                    log.warn("Failed to parse optional feature {}: {}",
                            feature.path("name").asText("unknown"), e.getMessage());
                }
            }

            repository.saveAll(batch);
            log.info("Optional feature seeding complete: {} features", batch.size());
        }
    }

    private List<OptionalFeature> parseFeature(JsonNode feature) throws Exception {
        String name = feature.path("name").asText(null);
        if (name == null) return List.of();

        JsonNode featureTypes = feature.get("featureType");
        if (featureTypes == null || !featureTypes.isArray()) return List.of();

        List<OptionalFeature> results = new ArrayList<>();
        for (JsonNode ft : featureTypes) {
            String type = ft.asText();
            if (!RELEVANT_TYPES.contains(type)) continue;

            String normalizedType = normalizeType(type);

            results.add(OptionalFeature.builder()
                    .name(name)
                    .source(feature.path("source").asText(null))
                    .featureType(normalizedType)
                    .description(flattenEntries(feature.get("entries")))
                    .prerequisite(extractPrerequisite(feature))
                    .build());
        }
        return results;
    }

    private String normalizeType(String type) {
        if (type.startsWith("FS:")) return "FightingStyle";
        return switch (type) {
            case "EI" -> "EldritchInvocation";
            case "MM" -> "Metamagic";
            case "MV:B" -> "BattleManeuver";
            default -> type;
        };
    }

    private String extractPrerequisite(JsonNode feature) throws Exception {
        JsonNode prereqs = feature.get("prerequisite");
        if (prereqs == null || !prereqs.isArray() || prereqs.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode prereq : prereqs) {
            if (prereq.isObject()) {
                result.add(prereq);
            }
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String flattenEntries(JsonNode entries) {
        if (entries == null) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            if (entry.isTextual()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asText()));
            } else if (entry.isObject()) {
                String type = entry.path("type").asText("");
                if ("entries".equals(type)) {
                    String entryName = entry.path("name").asText("");
                    if (!entryName.isEmpty()) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(entryName).append(": ");
                    }
                    String inner = flattenEntries(entry.get("entries"));
                    if (inner != null) {
                        if (!sb.isEmpty() && entryName.isEmpty()) sb.append("\n");
                        sb.append(inner);
                    }
                } else if ("list".equals(type)) {
                    JsonNode items = entry.get("items");
                    if (items != null) {
                        for (JsonNode item : items) {
                            if (!sb.isEmpty()) sb.append("\n");
                            if (item.isTextual()) {
                                sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                            }
                        }
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
