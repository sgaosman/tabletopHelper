package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.BackgroundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class BackgroundLoader implements ContentLoader {

    private static final String FILE = "data/5etools/backgrounds.json";

    private final BackgroundRepository backgroundRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "backgrounds";
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
        JsonNode backgrounds = LoadContext.array(context.read(FILE), "background", FILE);

        Map<String, JsonNode> bgIndex = new LinkedHashMap<>();
        for (JsonNode bg : backgrounds) {
            String key = bg.path("name").asString("") + "|" + bg.path("source").asString("");
            bgIndex.put(key, bg);
        }

        List<Background> batch = new ArrayList<>();
        for (JsonNode bg : backgrounds) {
            String name = bg.path("name").asString(null);
            if (name == null) {
                context.skip("backgrounds with no name");
                continue;
            }
            batch.add(LoadContext.reading(FILE + ": background " + name + "|" + bg.path("source").asString(""),
                    () -> parseBackground(name, bg, bgIndex)));
        }

        context.upsert("backgrounds", backgroundRepository, batch,
                b -> LoadContext.nameAndSource(b.getName(), b.getSource()));
    }

    private Background parseBackground(String name, JsonNode bg, Map<String, JsonNode> bgIndex) throws Exception {
        JsonNode resolved = resolveCopy(bg, bgIndex);

        return Background.builder()
                .name(name)
                .source(bg.path("source").asString(null))
                .skillProficiencies(extractSkillProficiencies(resolved))
                .toolProficiencies(extractToolProficiencies(resolved))
                .languageProficiencies(extractLanguageProficiencies(resolved))
                .startingEquipment(extractStartingEquipment(resolved))
                .feature(extractFeature(resolved))
                .feats(extractFeats(resolved))
                .additionalSpells(extractAdditionalSpells(resolved))
                .description(flattenEntries(resolved.get("entries")))
                .build();
    }

    private JsonNode resolveCopy(JsonNode bg, Map<String, JsonNode> bgIndex) {
        return resolveCopyRecursive(bg, bgIndex, 0);
    }

    private JsonNode resolveCopyRecursive(JsonNode bg, Map<String, JsonNode> bgIndex, int depth) {
        JsonNode copyNode = bg.get("_copy");
        if (copyNode == null || depth > 5) return bg;

        String parentKey = copyNode.path("name").asString("") + "|" + copyNode.path("source").asString("");
        JsonNode parent = bgIndex.get(parentKey);
        if (parent == null) return bg;

        JsonNode resolvedParent = resolveCopyRecursive(parent, bgIndex, depth + 1);

        ObjectNode merged = (ObjectNode) resolvedParent.deepCopy();
        merged.put("name", bg.path("name").asString(""));
        merged.put("source", bg.path("source").asString(""));

        JsonNode mod = copyNode.get("_mod");
        if (mod != null && mod.has("entries")) {
            applyEntryMods(merged, mod.get("entries"));
        }

        return merged;
    }

    private void applyEntryMods(ObjectNode target, JsonNode mods) {
        JsonNode entries = target.get("entries");
        if (entries == null || !entries.isArray()) return;

        ArrayNode modsArr;
        if (mods.isArray()) {
            modsArr = (ArrayNode) mods;
        } else if (mods.isObject()) {
            modsArr = objectMapper.createArrayNode();
            modsArr.add(mods);
        } else {
            return;
        }

        ArrayNode entriesArr = (ArrayNode) entries;
        for (JsonNode mod : modsArr) {
            String mode = mod.path("mode").asString("");
            switch (mode) {
                case "replaceArr" -> {
                    String replace = mod.path("replace").asString("");
                    JsonNode items = mod.get("items");
                    if (items != null && !replace.isEmpty()) {
                        for (int i = 0; i < entriesArr.size(); i++) {
                            JsonNode entry = entriesArr.get(i);
                            if (entry.isObject() && replace.equals(entry.path("name").asString(""))) {
                                entriesArr.set(i, items);
                                break;
                            }
                        }
                    }
                }
                case "insertArr" -> {
                    int index = mod.path("index").asInt(0);
                    JsonNode items = mod.get("items");
                    if (items != null) {
                        entriesArr.insert(Math.min(index, entriesArr.size()), items);
                    }
                }
                case "removeArr" -> {
                    String removeName = mod.path("names").isArray() && !mod.path("names").isEmpty()
                            ? mod.path("names").get(0).asString("") : "";
                    if (!removeName.isEmpty()) {
                        for (int i = 0; i < entriesArr.size(); i++) {
                            if (removeName.equals(entriesArr.get(i).path("name").asString(""))) {
                                entriesArr.remove(i);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private String extractFeats(JsonNode bg) throws Exception {
        JsonNode feats = bg.get("feats");
        if (feats == null || !feats.isArray() || feats.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode featSet : feats) {
            if (!featSet.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> fields = featSet.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                String featName = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
                result.add(titleCase(featName));
            }
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractAdditionalSpells(JsonNode bg) throws Exception {
        JsonNode spells = bg.get("additionalSpells");
        if (spells == null || !spells.isArray() || spells.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode spellSet : spells) {
            JsonNode expanded = spellSet.get("expanded");
            if (expanded == null) continue;
            ObjectNode spellsByLevel = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = expanded.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String levelKey = field.getKey();
                JsonNode spellList = field.getValue();
                if (spellList.isArray()) {
                    ArrayNode cleaned = objectMapper.createArrayNode();
                    for (JsonNode spell : spellList) {
                        String spellName = spell.asString("");
                        if (spellName.contains("|")) spellName = spellName.substring(0, spellName.indexOf('|'));
                        cleaned.add(titleCase(spellName));
                    }
                    spellsByLevel.set(levelKey, cleaned);
                }
            }
            result.add(spellsByLevel);
        }
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String extractSkillProficiencies(JsonNode bg) throws Exception {
        return extractProficiencies(bg.get("skillProficiencies"), false);
    }

    private String extractToolProficiencies(JsonNode bg) throws Exception {
        return extractProficiencies(bg.get("toolProficiencies"), true);
    }

    private String extractLanguageProficiencies(JsonNode bg) throws Exception {
        JsonNode profs = bg.get("languageProficiencies");
        if (profs == null || !profs.isArray() || profs.isEmpty()) return null;

        if (profs.size() > 1) {
            ArrayNode sets = objectMapper.createArrayNode();
            for (JsonNode profSet : profs) {
                sets.add(parseSingleLangSet(profSet));
            }
            ArrayNode result = objectMapper.createArrayNode();
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("chooseSet", sets);
            result.add(wrapper);
            return objectMapper.writeValueAsString(result);
        }

        return objectMapper.writeValueAsString(parseSingleLangSet(profs.get(0)));
    }

    private ArrayNode parseSingleLangSet(JsonNode profSet) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!profSet.isObject()) return result;
        Iterator<Map.Entry<String, JsonNode>> fields = profSet.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if ("anyStandard".equals(key)) {
                ObjectNode obj = objectMapper.createObjectNode();
                obj.put("anyStandard", value.asInt(0));
                result.add(obj);
            } else if ("choose".equals(key)) {
                ObjectNode chooseObj = objectMapper.createObjectNode();
                chooseObj.set("choose", value);
                result.add(chooseObj);
            } else if (value.asBoolean(false)) {
                result.add(titleCase(key));
            }
        }
        return result;
    }

    private String extractProficiencies(JsonNode profs, boolean handleSpecialTools) throws Exception {
        if (profs == null || !profs.isArray() || profs.isEmpty()) return null;

        if (profs.size() > 1) {
            ArrayNode sets = objectMapper.createArrayNode();
            for (JsonNode profSet : profs) {
                sets.add(parseSingleProfSet(profSet, handleSpecialTools));
            }
            ArrayNode result = objectMapper.createArrayNode();
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("chooseSet", sets);
            result.add(wrapper);
            return objectMapper.writeValueAsString(result);
        }

        ArrayNode result = parseSingleProfSet(profs.get(0), handleSpecialTools);
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private ArrayNode parseSingleProfSet(JsonNode profSet, boolean handleSpecialTools) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!profSet.isObject()) return result;
        Iterator<Map.Entry<String, JsonNode>> fields = profSet.properties().iterator();
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
                        from.add(titleCase(f.asString("")));
                    }
                    inner.set("from", from);
                }
                inner.put("count", value.path("count").asInt(1));
                chooseObj.set("choose", inner);
                result.add(chooseObj);
            } else if (handleSpecialTools && ("anyArtisansTool".equals(key) || "anyGamingSet".equals(key)
                    || "anyMusicalInstrument".equals(key))) {
                result.add(formatSpecialProficiency(key, value.asInt(1)));
            } else if ("any".equals(key) && value.isNumber()) {
                ObjectNode anyObj = objectMapper.createObjectNode();
                anyObj.put("any", value.asInt(1));
                result.add(anyObj);
            } else if (value.asBoolean(false)) {
                result.add(titleCase(key));
            }
        }
        return result;
    }

    private String formatSpecialProficiency(String key, int count) {
        String label = switch (key) {
            case "anyArtisansTool" -> "Any Artisan's Tool";
            case "anyGamingSet" -> "Any Gaming Set";
            case "anyMusicalInstrument" -> "Any Musical Instrument";
            default -> titleCase(key);
        };
        if (count > 1) label += " x" + count;
        return label;
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
            if (!"entries".equals(entry.path("type").asString(""))) continue;
            String entryName = entry.path("name").asString("");
            if (entryName.startsWith("Feature:") || entryName.startsWith("feature:")) {
                ObjectNode feature = objectMapper.createObjectNode();
                feature.put("name", entryName.replaceFirst("^[Ff]eature:\\s*", "").trim());
                feature.put("description", flattenEntries(entry.get("entries")));
                return objectMapper.writeValueAsString(feature);
            }
        }

        for (JsonNode entry : entries) {
            if (!entry.isObject()) continue;
            if (!"entries".equals(entry.path("type").asString(""))) continue;
            String entryName = entry.path("name").asString("");
            if (entryName.isEmpty()) continue;
            if (entryName.contains("Proficiencies:") || entryName.contains("Languages:")
                    || entryName.contains("Equipment:") || entryName.contains("Suggested Characteristics")) continue;
            if ("list".equals(entry.path("type").asString(""))) continue;

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
            if (entry.isString()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject()) {
                String type = entry.path("type").asString("");
                switch (type) {
                    case "entries" -> {
                        String name = entry.path("name").asString("");
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
                                if (item.isString()) {
                                    sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
                                } else if (item.isObject()) {
                                    String itemName = item.path("name").asString("");
                                    String itemEntry = item.path("entry").asString("");
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
                                        rowStr.append(FiveEToolsMarkupParser.parse(cell.asString("")));
                                    }
                                    sb.append(rowStr);
                                }
                            }
                        }
                    }
                    case "item" -> {
                        if (!sb.isEmpty()) sb.append("\n");
                        String itemName = entry.path("name").asString("");
                        String itemEntry = entry.path("entry").asString("");
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
