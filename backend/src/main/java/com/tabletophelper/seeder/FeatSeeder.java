package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
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
public class FeatSeeder {

    private final FeatRepository featRepository;
    private final ObjectMapper objectMapper;

    public void seed() throws Exception {
        if (featRepository.count() > 0) {
            log.info("Feats already seeded, skipping");
            return;
        }

        ClassPathResource resource = new ClassPathResource("data/5etools/feats.json");
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode feats = root.get("feat");
            if (feats == null || !feats.isArray()) return;

            List<Feat> batch = new ArrayList<>();
            for (JsonNode feat : feats) {
                try {
                    Feat entity = parseFeat(feat);
                    if (entity != null) batch.add(entity);
                } catch (Exception e) {
                    log.warn("Failed to parse feat {}: {}", feat.path("name").asText("unknown"), e.getMessage());
                }
            }

            featRepository.saveAll(batch);
            log.info("Feat seeding complete: {} feats", batch.size());
        }
    }

    private Feat parseFeat(JsonNode feat) throws Exception {
        String name = feat.path("name").asText(null);
        if (name == null) return null;

        return Feat.builder()
                .name(name)
                .source(feat.path("source").asText(null))
                .prerequisite(extractPrerequisite(feat))
                .description(flattenEntries(feat.get("entries")))
                .abilityScoreIncrease(extractAbilityScoreIncrease(feat))
                .grantsFeatures(extractGrantsFeatures(feat))
                .build();
    }

    private String extractPrerequisite(JsonNode feat) throws Exception {
        JsonNode prereqs = feat.get("prerequisite");
        if (prereqs == null || !prereqs.isArray() || prereqs.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode prereq : prereqs) {
            if (!prereq.isObject()) continue;
            ObjectNode normalized = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = prereq.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();

                switch (key) {
                    case "ability" -> {
                        ArrayNode abilities = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode ab : value) {
                                ab.fields().forEachRemaining(e ->
                                        abilities.add(titleCase(e.getKey()) + " " + e.getValue().asInt()));
                            }
                        }
                        normalized.set("ability", abilities);
                    }
                    case "race" -> {
                        ArrayNode races = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode race : value) {
                                String raceName = race.path("name").asText("");
                                String subrace = race.path("subrace").asText("");
                                races.add(subrace.isEmpty() ? raceName : raceName + " (" + subrace + ")");
                            }
                        }
                        normalized.set("race", races);
                    }
                    case "spellcasting", "spellcasting2020", "spellcastingFeature" -> {
                        if (value.asBoolean(false) || value.isBoolean()) {
                            normalized.put("spellcasting", true);
                        }
                    }
                    case "proficiency" -> {
                        ArrayNode profs = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode prof : value) {
                                if (prof.has("armor")) profs.add("Armor: " + prof.get("armor").asText());
                                if (prof.has("weapon")) profs.add("Weapon: " + prof.get("weapon").asText());
                                if (prof.has("weaponGroup")) profs.add("Weapon Group: " + titleCase(prof.get("weaponGroup").asText()));
                            }
                        }
                        normalized.set("proficiency", profs);
                    }
                    case "feat" -> {
                        ArrayNode featPrereqs = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode f : value) {
                                featPrereqs.add(f.asText());
                            }
                        }
                        normalized.set("feat", featPrereqs);
                    }
                    case "level" -> {
                        if (value.isInt()) {
                            normalized.put("level", value.asInt());
                        } else if (value.isObject()) {
                            int lvl = value.path("level").asInt();
                            String className = value.path("class").path("name").asText("");
                            normalized.put("level", lvl);
                            if (!className.isEmpty()) normalized.put("class", className);
                        }
                    }
                    case "other" -> normalized.put("other", value.asText());
                    case "psionics" -> normalized.put("psionics", value.asBoolean(false));
                    case "campaign" -> {
                        ArrayNode campaigns = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode c : value) campaigns.add(c.asText());
                        }
                        normalized.set("campaign", campaigns);
                    }
                    case "background" -> {
                        ArrayNode bgs = objectMapper.createArrayNode();
                        if (value.isArray()) {
                            for (JsonNode bg : value) {
                                bgs.add(bg.path("name").asText());
                            }
                        }
                        normalized.set("background", bgs);
                    }
                    default -> {
                        // Pass through unknown keys
                    }
                }
            }
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractAbilityScoreIncrease(JsonNode feat) throws Exception {
        JsonNode ability = feat.get("ability");
        if (ability == null || !ability.isArray() || ability.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode ab : ability) {
            if (!ab.isObject()) continue;
            ObjectNode entry = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = ab.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("choose".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    if (value.has("from")) {
                        ArrayNode from = objectMapper.createArrayNode();
                        for (JsonNode f : value.get("from")) {
                            from.add(f.asText().toUpperCase());
                        }
                        chooseObj.set("from", from);
                    }
                    chooseObj.put("amount", value.path("amount").asInt(1));
                    entry.set("choose", chooseObj);
                } else {
                    entry.put(key.toUpperCase(), value.asInt());
                }
            }
            result.add(entry);
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractGrantsFeatures(JsonNode feat) throws Exception {
        JsonNode spells = feat.get("additionalSpells");
        if (spells == null) return null;
        return objectMapper.writeValueAsString(spells);
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
