package com.tabletophelper.content.internal;

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
public class OptionalFeatureLoader implements ContentLoader {

    private static final String FILE = "data/5etools/optionalfeatures.json";

    private static final Set<String> RELEVANT_TYPES = Set.of(
            "EI", "MM", "MV:B",
            "FS:F", "FS:R", "FS:P", "FS:B"
    );

    private final OptionalFeatureRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "optional-features";
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
    public void load(LoadContext context) throws Exception {
        ClassPathResource resource = new ClassPathResource(FILE);
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

            context.upsert("optional_features", repository, batch,
                    f -> LoadContext.nameAndSource(f.getName(), f.getSource()));
        }
    }

    private List<OptionalFeature> parseFeature(JsonNode feature) throws Exception {
        String name = feature.path("name").asText(null);
        if (name == null) return List.of();

        JsonNode featureTypes = feature.get("featureType");
        if (featureTypes == null || !featureTypes.isArray()) return List.of();

        // A style listed for several classes (FS:F, FS:R, FS:P) is one Fighting Style, so each type is built once
        List<OptionalFeature> results = new ArrayList<>();
        Set<String> built = new HashSet<>();
        for (JsonNode ft : featureTypes) {
            String type = ft.asText();
            if (!RELEVANT_TYPES.contains(type)) continue;

            String normalizedType = normalizeType(type);
            if (!built.add(normalizedType)) continue;

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
