package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class MonsterLoader implements ContentLoader {

    private final MonsterRepository monsterRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, Integer> CR_TO_XP = Map.ofEntries(
            Map.entry("0", 10), Map.entry("1/8", 25), Map.entry("1/4", 50),
            Map.entry("1/2", 100), Map.entry("1", 200), Map.entry("2", 450),
            Map.entry("3", 700), Map.entry("4", 1100), Map.entry("5", 1800),
            Map.entry("6", 2300), Map.entry("7", 2900), Map.entry("8", 3900),
            Map.entry("9", 5000), Map.entry("10", 5900), Map.entry("11", 7200),
            Map.entry("12", 8400), Map.entry("13", 10000), Map.entry("14", 11500),
            Map.entry("15", 13000), Map.entry("16", 15000), Map.entry("17", 18000),
            Map.entry("18", 20000), Map.entry("19", 22000), Map.entry("20", 25000),
            Map.entry("21", 33000), Map.entry("22", 41000), Map.entry("23", 50000),
            Map.entry("24", 62000), Map.entry("25", 75000), Map.entry("26", 90000),
            Map.entry("27", 105000), Map.entry("28", 120000), Map.entry("29", 135000),
            Map.entry("30", 155000)
    );

    private static final Map<String, String> SIZE_MAP = Map.of(
            "T", "Tiny", "S", "Small", "M", "Medium",
            "L", "Large", "H", "Huge", "G", "Gargantuan"
    );

    @Override
    public String name() {
        return "monsters";
    }

    @Override
    public List<String> files() {
        return List.of("data/5etools/bestiary/bestiary-*.json");
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public void load(LoadContext context) {
        List<Monster> batch = new ArrayList<>();

        for (LoadContext.DataFile file : context.files("data/5etools/bestiary/bestiary-*.json")) {
            String filename = file.name();
            if (filename != null && (filename.contains("fluff") || filename.contains("index"))) {
                context.skip("fluff and index files");
                continue;
            }

            JsonNode root = context.read(file);
            JsonNode monsters = root.get("monster");
            if (monsters == null || !monsters.isArray()) {
                context.skip("files with no monster array");
                continue;
            }

            for (JsonNode m : monsters) {
                if (m.has("_copy")) {
                    context.skip("_copy monsters");
                    continue;
                }
                batch.add(LoadContext.reading(filename + ": monster " + m.path("name").asString("?") + "|"
                        + m.path("source").asString(""), () -> parseMonster(m)));
            }
        }

        // The action templates are monster-actions' to write
        context.upsert("monsters", monsterRepository, batch,
                m -> LoadContext.nameAndSource(m.getName(), m.getSource()), "actionTemplates");
    }

    private Monster parseMonster(JsonNode m) throws Exception {
        String cr = parseCr(m.get("cr"));
        String size = parseSize(m.get("size"));
        String type = parseType(m.get("type"));
        String subtype = parseSubtype(m.get("type"));

        int ac = parseAc(m.get("ac"));
        String acType = parseAcType(m.get("ac"));

        return Monster.builder()
                .name(m.path("name").asString(""))
                .size(size)
                .type(type)
                .subtype(subtype)
                .alignment(parseAlignment(m.get("alignment")))
                .armourClass(ac)
                .acType(acType)
                .hitPoints(m.path("hp").path("average").asInt(0))
                .hitDice(m.path("hp").path("formula").asString(null))
                .speed(nodeToJson(m.get("speed")))
                .strength(m.path("str").asInt(10))
                .dexterity(m.path("dex").asInt(10))
                .constitution(m.path("con").asInt(10))
                .intelligence(m.path("int").asInt(10))
                .wisdom(m.path("wis").asInt(10))
                .charisma(m.path("cha").asInt(10))
                .savingThrows(nodeToJson(m.get("save")))
                .skills(nodeToJson(m.get("skill")))
                .damageResistances(arrayToJson(m.get("resist")))
                .damageImmunities(arrayToJson(m.get("immune")))
                .damageVulnerabilities(arrayToJson(m.get("vulnerable")))
                .conditionImmunities(arrayToJson(m.get("conditionImmune")))
                .senses(parseSenses(m))
                .languages(parseLanguages(m.get("languages")))
                .challengeRating(cr)
                .experiencePoints(cr != null ? CR_TO_XP.getOrDefault(cr, 0) : 0)
                .traits(parseEntryList(m.get("trait")))
                .actions(parseEntryList(m.get("action")))
                .reactions(parseEntryList(m.get("reaction")))
                .legendaryActions(parseEntryList(m.get("legendary")))
                .lairActions(parseEntryList(m.get("legendaryGroup")))
                .source(m.path("source").asString(null))
                .isHomebrew(false)
                .build();
    }

    private String parseCr(JsonNode crNode) {
        if (crNode == null) return null;
        if (crNode.isString()) return crNode.asString("");
        if (crNode.isObject()) return crNode.path("cr").asString(null);
        return null;
    }

    private String parseSize(JsonNode sizeNode) {
        if (sizeNode == null) return null;
        if (sizeNode.isArray() && sizeNode.size() > 0) {
            String code = sizeNode.get(0).asString("");
            return SIZE_MAP.getOrDefault(code, code);
        }
        return SIZE_MAP.getOrDefault(sizeNode.asString(""), sizeNode.asString(""));
    }

    private String parseType(JsonNode typeNode) {
        if (typeNode == null) return null;
        if (typeNode.isString()) return typeNode.asString("");
        if (typeNode.isObject()) {
            JsonNode inner = typeNode.get("type");
            if (inner == null) return null;
            if (inner.isString()) return inner.asString("");
            if (inner.isObject() && inner.has("choose")) {
                JsonNode choices = inner.get("choose");
                if (choices.isArray()) {
                    List<String> types = new ArrayList<>();
                    for (JsonNode c : choices) types.add(c.asString(""));
                    return String.join(" or ", types);
                }
            }
        }
        return null;
    }

    private String parseSubtype(JsonNode typeNode) {
        if (typeNode == null || !typeNode.isObject()) return null;
        JsonNode tags = typeNode.get("tags");
        if (tags != null && tags.isArray() && tags.size() > 0) {
            List<String> subtypes = new ArrayList<>();
            for (JsonNode tag : tags) {
                if (tag.isString()) subtypes.add(tag.asString(""));
                else if (tag.isObject()) subtypes.add(tag.path("tag").asString(""));
            }
            return String.join(", ", subtypes);
        }
        return null;
    }

    private int parseAc(JsonNode acNode) {
        if (acNode == null) return 10;
        if (acNode.isInt()) return acNode.asInt(0);
        if (acNode.isArray() && acNode.size() > 0) {
            JsonNode first = acNode.get(0);
            if (first.isInt()) return first.asInt(0);
            if (first.isObject()) return first.path("ac").asInt(10);
        }
        return 10;
    }

    private String parseAcType(JsonNode acNode) {
        if (acNode == null || !acNode.isArray() || acNode.isEmpty()) return null;
        JsonNode first = acNode.get(0);
        if (!first.isObject()) return null;
        JsonNode from = first.get("from");
        if (from != null && from.isArray()) {
            List<String> sources = new ArrayList<>();
            for (JsonNode f : from) {
                sources.add(FiveEToolsMarkupParser.parse(f.asString("")));
            }
            return String.join(", ", sources);
        }
        return null;
    }

    private String parseAlignment(JsonNode alignNode) {
        if (alignNode == null || !alignNode.isArray()) return null;
        Map<String, String> alignMap = Map.of(
                "L", "Lawful", "N", "Neutral", "C", "Chaotic",
                "G", "Good", "E", "Evil", "U", "Unaligned",
                "A", "Any"
        );
        List<String> parts = new ArrayList<>();
        for (JsonNode a : alignNode) {
            if (a.isString()) {
                parts.add(alignMap.getOrDefault(a.asString(""), a.asString("")));
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private String parseSenses(JsonNode m) throws Exception {
        Map<String, Object> sensesMap = new LinkedHashMap<>();
        JsonNode senses = m.get("senses");
        if (senses != null && senses.isArray()) {
            List<String> senseList = new ArrayList<>();
            for (JsonNode s : senses) senseList.add(s.asString(""));
            sensesMap.put("special", senseList);
        }
        int passive = m.path("passive").asInt(0);
        if (passive > 0) sensesMap.put("passive_perception", passive);
        return sensesMap.isEmpty() ? null : objectMapper.writeValueAsString(sensesMap);
    }

    private String parseLanguages(JsonNode langNode) {
        if (langNode == null) return null;
        if (langNode.isArray()) {
            List<String> langs = new ArrayList<>();
            for (JsonNode l : langNode) langs.add(l.asString(""));
            return String.join(", ", langs);
        }
        return langNode.asString(null);
    }

    private String parseEntryList(JsonNode entriesNode) throws Exception {
        if (entriesNode == null || !entriesNode.isArray()) return null;
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode entry : entriesNode) {
            var obj = objectMapper.createObjectNode();
            obj.put("name", FiveEToolsMarkupParser.parse(entry.path("name").asString("")));
            obj.put("description", flattenEntries(entry.get("entries")));
            result.add(obj);
        }
        return objectMapper.writeValueAsString(result);
    }

    private String flattenEntries(JsonNode entries) {
        if (entries == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            if (entry.isString()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject()) {
                if (entry.has("entries")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(flattenEntries(entry.get("entries")));
                } else if (entry.has("items")) {
                    JsonNode items = entry.get("items");
                    for (JsonNode item : items) {
                        if (sb.length() > 0) sb.append("\n");
                        if (item.isString()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
                        } else if (item.isObject() && item.has("entries")) {
                            sb.append("- ").append(flattenEntries(item.get("entries")));
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private String nodeToJson(JsonNode node) throws Exception {
        if (node == null || node.isNull()) return null;
        return objectMapper.writeValueAsString(node);
    }

    private String arrayToJson(JsonNode node) throws Exception {
        if (node == null || !node.isArray()) return null;
        List<String> items = new ArrayList<>();
        for (JsonNode n : node) {
            if (n.isString()) {
                items.add(n.asString(""));
            } else if (n.isObject()) {
                items.add(objectMapper.writeValueAsString(n));
            }
        }
        return objectMapper.writeValueAsString(items);
    }
}
