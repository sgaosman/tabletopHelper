package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Condition;
import com.tabletophelper.reference.ConditionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ConditionLoader implements ContentLoader {

    private static final String FILE = "data/5etools/conditionsdiseases.json";

    private final ConditionRepository conditionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "conditions";
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
        JsonNode conditions = LoadContext.array(context.read(FILE), "condition", FILE);

        List<Condition> batch = new ArrayList<>();
        for (JsonNode c : conditions) {
            batch.add(LoadContext.reading(FILE + ": condition " + c.path("name").asString("?"), () -> parseCondition(c)));
        }

        // A condition is known by its name alone, as content-snapshot reads it
        context.upsert("conditions", conditionRepository, batch, Condition::getName);
    }

    private Condition parseCondition(JsonNode c) throws Exception {
        String description = flattenEntries(c.get("entries"));
        List<String> effects = extractEffects(c.get("entries"));

        return Condition.builder()
                .name(c.path("name").asString(""))
                .description(description != null ? description : "")
                .effects(effects.isEmpty() ? null : objectMapper.writeValueAsString(effects))
                .source(c.path("source").asString(null))
                .build();
    }

    private List<String> extractEffects(JsonNode entries) {
        List<String> effects = new ArrayList<>();
        if (entries == null) return effects;
        for (JsonNode entry : entries) {
            if (entry.isObject() && "list".equals(entry.path("type").asString(""))) {
                JsonNode items = entry.get("items");
                if (items != null) {
                    for (JsonNode item : items) {
                        if (item.isString()) {
                            effects.add(FiveEToolsMarkupParser.parse(item.asString("")));
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
            if (entry.isString()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject()) {
                String type = entry.path("type").asString("");
                if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (sb.length() > 0) sb.append("\n");
                        if (item.isString()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
                        }
                    }
                } else if ("table".equals(type) && entry.has("rows")) {
                    JsonNode rows = entry.get("rows");
                    for (JsonNode row : rows) {
                        if (row.isArray() && row.size() >= 2) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(row.get(0).asString("")))
                              .append(": ").append(FiveEToolsMarkupParser.parse(row.get(1).asString("")));
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
