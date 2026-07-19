package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.BackgroundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackgroundSeeder {

    private final BackgroundRepository backgroundRepository;
    private final ObjectMapper objectMapper;

    public void seed() throws Exception {
        if (backgroundRepository.count() > 0) {
            log.info("Backgrounds already seeded, skipping");
            return;
        }

        ClassPathResource resource = new ClassPathResource("data/5etools/backgrounds.json");
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode backgrounds = root.get("background");
            if (backgrounds == null || !backgrounds.isArray()) return;

            List<Background> batch = new ArrayList<>();
            for (JsonNode bg : backgrounds) {
                try {
                    Background background = parseBackground(bg);
                    if (background != null) batch.add(background);
                } catch (Exception e) {
                    log.warn("Failed to parse background {}: {}", bg.path("name").asText("unknown"), e.getMessage());
                }
            }

            backgroundRepository.saveAll(batch);
            log.info("Background seeding complete: {} backgrounds", batch.size());
        }
    }

    private Background parseBackground(JsonNode bg) throws Exception {
        String name = bg.path("name").asText(null);
        if (name == null) return null;

        return Background.builder()
                .name(name)
                .source(bg.path("source").asText(null))
                .skillProficiencies(extractSkillProficiencies(bg))
                .toolProficiencies(extractToolProficiencies(bg))
                .languageProficiencies(extractLanguageProficiencies(bg))
                .startingEquipment(extractStartingEquipment(bg))
                .feature(extractFeature(bg))
                .description(flattenEntries(bg.get("entries")))
                .build();
    }

    private String extractSkillProficiencies(JsonNode bg) throws Exception {
        JsonNode profs = bg.get("skillProficiencies");
        if (profs == null || !profs.isArray() || profs.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode profSet : profs) {
            if (!profSet.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> fields = profSet.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("choose".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    ObjectNode inner = objectMapper.createObjectNode();
                    if (value.has("from")) {
                        ArrayNode from = objectMapper.createArrayNode();
                        for (JsonNode f : value.get("from")) {
                            from.add(titleCase(f.asText()));
                        }
                        inner.set("from", from);
                    }
                    inner.put("count", value.path("count").asInt(1));
                    chooseObj.set("choose", inner);
                    result.add(chooseObj);
                } else if (value.asBoolean(false)) {
                    result.add(titleCase(key));
                }
            }
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractToolProficiencies(JsonNode bg) throws Exception {
        JsonNode profs = bg.get("toolProficiencies");
        if (profs == null || !profs.isArray() || profs.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode profSet : profs) {
            if (!profSet.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> fields = profSet.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("choose".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    ObjectNode inner = objectMapper.createObjectNode();
                    if (value.has("from")) {
                        ArrayNode from = objectMapper.createArrayNode();
                        for (JsonNode f : value.get("from")) {
                            from.add(titleCase(f.asText()));
                        }
                        inner.set("from", from);
                    }
                    inner.put("count", value.path("count").asInt(1));
                    chooseObj.set("choose", inner);
                    result.add(chooseObj);
                } else if (value.asBoolean(false)) {
                    result.add(titleCase(key));
                }
            }
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractLanguageProficiencies(JsonNode bg) throws Exception {
        JsonNode profs = bg.get("languageProficiencies");
        if (profs == null || !profs.isArray() || profs.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode profSet : profs) {
            if (!profSet.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> fields = profSet.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("anyStandard".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    chooseObj.put("anyStandard", value.asInt());
                    result.add(chooseObj);
                } else if ("choose".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    chooseObj.set("choose", value);
                    result.add(chooseObj);
                } else if (value.asBoolean(false)) {
                    result.add(titleCase(key));
                }
            }
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractStartingEquipment(JsonNode bg) throws Exception {
        JsonNode equip = bg.get("startingEquipment");
        if (equip == null) return null;
        return objectMapper.writeValueAsString(equip);
    }

    private String extractFeature(JsonNode bg) throws Exception {
        JsonNode entries = bg.get("entries");
        if (entries == null || !entries.isArray()) return null;

        for (JsonNode entry : entries) {
            if (!entry.isObject()) continue;
            if (!"entries".equals(entry.path("type").asText())) continue;
            String entryName = entry.path("name").asText("");
            if (entryName.startsWith("Feature:") || entryName.startsWith("feature:")) {
                ObjectNode feature = objectMapper.createObjectNode();
                feature.put("name", entryName.replaceFirst("^[Ff]eature:\\s*", "").trim());
                feature.put("description", flattenEntries(entry.get("entries")));
                return objectMapper.writeValueAsString(feature);
            }
        }

        // Some backgrounds have the feature without the "Feature:" prefix —
        // look for the first entries block that isn't a proficiency summary or suggested characteristics
        for (JsonNode entry : entries) {
            if (!entry.isObject()) continue;
            if (!"entries".equals(entry.path("type").asText())) continue;
            String entryName = entry.path("name").asText("");
            if (entryName.isEmpty()) continue;
            if (entryName.contains("Proficiencies:") || entryName.contains("Languages:")
                    || entryName.contains("Equipment:") || entryName.contains("Suggested Characteristics")) continue;
            // Skip the list-hang-notitle summary at the top
            if ("list".equals(entry.path("type").asText())) continue;

            ObjectNode feature = objectMapper.createObjectNode();
            feature.put("name", entryName);
            feature.put("description", flattenEntries(entry.get("entries")));
            return objectMapper.writeValueAsString(feature);
        }

        return null;
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
                switch (type) {
                    case "entries" -> {
                        String name = entry.path("name").asText("");
                        if (!name.isEmpty()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            sb.append(name).append(": ");
                        }
                        String inner = flattenEntries(entry.get("entries"));
                        if (inner != null) {
                            if (!sb.isEmpty() && !name.isEmpty()) sb.append(inner);
                            else {
                                if (!sb.isEmpty()) sb.append("\n");
                                sb.append(inner);
                            }
                        }
                    }
                    case "list" -> {
                        JsonNode items = entry.get("items");
                        if (items != null) {
                            for (JsonNode item : items) {
                                if (!sb.isEmpty()) sb.append("\n");
                                if (item.isTextual()) {
                                    sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                                } else if (item.isObject()) {
                                    String itemName = item.path("name").asText("");
                                    String itemEntry = item.path("entry").asText("");
                                    if (!itemName.isEmpty()) {
                                        sb.append("- ").append(itemName).append(" ").append(FiveEToolsMarkupParser.parse(itemEntry));
                                    } else {
                                        String inner = flattenEntries(item.get("entries"));
                                        if (inner != null) sb.append("- ").append(inner);
                                    }
                                }
                            }
                        }
                    }
                    case "table" -> {
                        JsonNode rows = entry.get("rows");
                        if (rows != null) {
                            for (JsonNode row : rows) {
                                if (row.isArray() && !row.isEmpty()) {
                                    if (!sb.isEmpty()) sb.append("\n");
                                    StringBuilder rowStr = new StringBuilder();
                                    for (JsonNode cell : row) {
                                        if (!rowStr.isEmpty()) rowStr.append(" | ");
                                        rowStr.append(FiveEToolsMarkupParser.parse(cell.asText()));
                                    }
                                    sb.append(rowStr);
                                }
                            }
                        }
                    }
                    case "item" -> {
                        if (!sb.isEmpty()) sb.append("\n");
                        String itemName = entry.path("name").asText("");
                        String itemEntry = entry.path("entry").asText("");
                        sb.append(itemName).append(" ").append(FiveEToolsMarkupParser.parse(itemEntry));
                    }
                    default -> {
                        if (entry.has("entries")) {
                            String inner = flattenEntries(entry.get("entries"));
                            if (inner != null) {
                                if (!sb.isEmpty()) sb.append("\n");
                                sb.append(inner);
                            }
                        }
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
