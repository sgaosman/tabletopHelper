package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
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
    public void load(LoadContext context) {
        JsonNode features = LoadContext.array(context.read(FILE), "optionalfeature", FILE);

        List<OptionalFeature> batch = new ArrayList<>();
        for (JsonNode feature : features) {
            String name = feature.path("name").asString(null);
            JsonNode featureTypes = feature.get("featureType");
            if (name == null) {
                context.skip("optional features with no name");
                continue;
            }
            if (featureTypes == null || !featureTypes.isArray()) {
                context.skip("optional features with no featureType");
                continue;
            }
            List<OptionalFeature> entities = LoadContext.reading(FILE + ": optional feature " + name + "|"
                    + feature.path("source").asString(""), () -> parseFeature(name, featureTypes, feature));
            if (entities.isEmpty()) {
                context.skip("optional features of a type not loaded");
            }
            batch.addAll(entities);
        }

        context.upsert("optional_features", repository, batch,
                f -> LoadContext.nameAndSource(f.getName(), f.getSource()));
    }

    // One row for each of RELEVANT_TYPES the feature has
    private List<OptionalFeature> parseFeature(String name, JsonNode featureTypes, JsonNode feature)
            throws Exception {
        // A style listed for several classes (FS:F, FS:R, FS:P) is one Fighting Style, so each type is built once
        List<OptionalFeature> results = new ArrayList<>();
        Set<String> built = new HashSet<>();
        for (JsonNode ft : featureTypes) {
            String type = ft.asString("");
            if (!RELEVANT_TYPES.contains(type)) continue;

            String normalizedType = normalizeType(type);
            if (!built.add(normalizedType)) continue;

            results.add(OptionalFeature.builder()
                    .name(name)
                    .source(feature.path("source").asString(null))
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
            if (entry.isString()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject()) {
                String type = entry.path("type").asString("");
                if ("entries".equals(type)) {
                    String entryName = entry.path("name").asString("");
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
                            if (item.isString()) {
                                sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
                            }
                        }
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
