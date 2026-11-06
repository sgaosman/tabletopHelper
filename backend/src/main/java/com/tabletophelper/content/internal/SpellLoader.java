package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class SpellLoader implements ContentLoader {

    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> SCHOOL_MAP = Map.of(
            "A", "Abjuration", "C", "Conjuration", "D", "Divination",
            "E", "Enchantment", "I", "Illusion", "N", "Necromancy",
            "T", "Transmutation", "V", "Evocation"
    );

    @Override
    public String name() {
        return "spells";
    }

    @Override
    public List<String> files() {
        return List.of("data/5etools/spells/spells-*.json", "data/5etools/spells/sources.json",
                "data/5etools/class/class-*.json");
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public void load(LoadContext context) {
        Map<String, Set<String>> classMap = loadSpellClassMap(context);
        log.info("Loaded spell-class associations for {} spells", classMap.size());

        List<Spell> allSpells = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (LoadContext.DataFile file : context.files("data/5etools/spells/spells-*.json")) {
            String filename = file.name();
            if (filename != null && filename.contains("fluff")) {
                context.skip("fluff files");
                continue;
            }

            JsonNode root = context.read(file);
            JsonNode spells = root.get("spell");
            if (spells == null || !spells.isArray()) {
                context.skip("files with no spell array");
                continue;
            }

            for (JsonNode s : spells) {
                if (s.has("_copy")) {
                    context.skip("_copy spells");
                    continue;
                }
                String key = s.path("name").asText() + "|" + s.path("source").asText();
                if (!seen.add(key)) {
                    context.skip("spells repeated in another file");
                    continue;
                }
                allSpells.add(LoadContext.reading(filename + ": spell " + key, () -> parseSpell(s, classMap)));
            }
        }

        // The effect templates are spell-effects' to write
        context.upsert("spells", spellRepository, allSpells,
                spell -> LoadContext.nameAndSource(spell.getName(), spell.getSource()), "effectTemplate");
    }

    private Map<String, Set<String>> loadSpellClassMap(LoadContext context) {
        Map<String, Set<String>> map = new HashMap<>();

        JsonNode sources = context.read("data/5etools/spells/sources.json");
        Iterator<Map.Entry<String, JsonNode>> sourceEntries = sources.properties().iterator();
        while (sourceEntries.hasNext()) {
            Map.Entry<String, JsonNode> sourceEntry = sourceEntries.next();
            String sourceCode = sourceEntry.getKey();
            JsonNode spellsInSource = sourceEntry.getValue();

            Iterator<Map.Entry<String, JsonNode>> spellEntries = spellsInSource.properties().iterator();
            while (spellEntries.hasNext()) {
                Map.Entry<String, JsonNode> spellEntry = spellEntries.next();
                String spellName = spellEntry.getKey();
                JsonNode spellData = spellEntry.getValue();
                String key = spellName + "|" + sourceCode;

                Set<String> classes = map.computeIfAbsent(key, k -> new LinkedHashSet<>());

                JsonNode classArray = spellData.get("class");
                if (classArray != null && classArray.isArray()) {
                    for (JsonNode c : classArray) {
                        classes.add(c.path("name").asText());
                    }
                }
                JsonNode classVariantArray = spellData.get("classVariant");
                if (classVariantArray != null && classVariantArray.isArray()) {
                    for (JsonNode c : classVariantArray) {
                        classes.add(c.path("name").asText());
                    }
                }
            }
        }

        for (LoadContext.DataFile classFile : context.files("data/5etools/class/class-*.json")) {
            String filename = classFile.name();
            if (filename != null && filename.contains("fluff")) {
                context.skip("fluff class files");
                continue;
            }

            JsonNode root = context.read(classFile);
            String className = null;
            JsonNode classArray = root.get("class");
            if (classArray != null && classArray.isArray() && classArray.size() > 0) {
                className = classArray.get(0).path("name").asText(null);
            }
            if (className == null) continue;

            JsonNode subclasses = root.get("subclass");
            if (subclasses == null || !subclasses.isArray()) continue;

            for (JsonNode sc : subclasses) {
                String subclassName = sc.path("name").asText(null);
                if (subclassName == null) continue;

                JsonNode additionalSpells = sc.get("additionalSpells");
                if (additionalSpells == null || !additionalSpells.isArray()) continue;

                String label = className + " (" + subclassName + ")";

                for (JsonNode asp : additionalSpells) {
                    collectSubclassSpells(asp.get("prepared"), label, map);
                    collectSubclassSpells(asp.get("expanded"), label, map);
                    collectSubclassSpells(asp.get("known"), label, map);
                    collectSubclassSpells(asp.get("innate"), label, map);
                }
            }
        }

        return map;
    }

    private void collectSubclassSpells(JsonNode node, String label, Map<String, Set<String>> target) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode spellNode : node) {
                if (!spellNode.isTextual()) continue;
                String spellName = spellNode.asText().replace("#c", "");
                for (Map.Entry<String, Set<String>> entry : target.entrySet()) {
                    if (entry.getKey().toLowerCase().startsWith(spellName.toLowerCase() + "|")) {
                        entry.getValue().add(label);
                    }
                }
                target.computeIfAbsent("*" + spellName.toLowerCase(), k -> new LinkedHashSet<>()).add(label);
            }
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                collectSubclassSpells(fields.next().getValue(), label, target);
            }
        }
    }

    private Spell parseSpell(JsonNode s, Map<String, Set<String>> classMap) throws Exception {
        String schoolCode = s.path("school").asText("");
        String school = SCHOOL_MAP.getOrDefault(schoolCode, schoolCode);
        String spellName = s.path("name").asText();
        String source = s.path("source").asText(null);

        Set<String> spellClasses = new LinkedHashSet<>();
        String key = spellName + "|" + source;
        if (classMap.containsKey(key)) {
            spellClasses.addAll(classMap.get(key));
        }
        String wildcardKey = "*" + spellName.toLowerCase();
        if (classMap.containsKey(wildcardKey)) {
            spellClasses.addAll(classMap.get(wildcardKey));
        }

        String classesJson = null;
        if (!spellClasses.isEmpty()) {
            classesJson = objectMapper.writeValueAsString(new ArrayList<>(spellClasses));
        }

        return Spell.builder()
                .name(spellName)
                .level(s.path("level").asInt(0))
                .school(school)
                .castingTime(parseCastingTime(s.get("time")))
                .rangeDistance(parseRange(s.get("range")))
                .components(parseComponents(s.get("components")))
                .duration(parseDuration(s.get("duration")))
                .concentration(isConcentration(s.get("duration")))
                .ritual(s.path("meta").path("ritual").asBoolean(false))
                .description(flattenEntries(s.get("entries")))
                .higherLevels(flattenEntries(s.get("entriesHigherLevel")))
                .damageType(parseDamageType(s.get("damageInflict")))
                .saveAbility(parseSaveAbility(s.get("savingThrow")))
                .source(source)
                .classes(classesJson)
                .build();
    }

    private String parseCastingTime(JsonNode timeNode) {
        if (timeNode == null || !timeNode.isArray() || timeNode.isEmpty()) return null;
        JsonNode first = timeNode.get(0);
        int number = first.path("number").asInt(1);
        String unit = first.path("unit").asText("action");
        if (number == 1 && "action".equals(unit)) return "1 action";
        if (number == 1 && "bonus".equals(unit)) return "1 bonus action";
        if (number == 1 && "reaction".equals(unit)) {
            String condition = first.path("condition").asText("");
            return "1 reaction" + (condition.isEmpty() ? "" : ", " + FiveEToolsMarkupParser.parse(condition));
        }
        return number + " " + unit + (number > 1 ? "s" : "");
    }

    private String parseRange(JsonNode rangeNode) {
        if (rangeNode == null) return null;
        String type = rangeNode.path("type").asText("");
        JsonNode distance = rangeNode.get("distance");

        if ("special".equals(type)) return "Special";
        if (distance == null) return type;

        String distType = distance.path("type").asText("");
        if ("self".equals(distType)) {
            if (rangeNode.has("distance") && distance.has("amount")) {
                return "Self (" + distance.path("amount").asInt() + "-foot " + type + ")";
            }
            return "Self";
        }
        if ("touch".equals(distType)) return "Touch";
        if ("sight".equals(distType)) return "Sight";
        if ("unlimited".equals(distType)) return "Unlimited";

        int amount = distance.path("amount").asInt(0);
        return amount + " " + distType;
    }

    private String parseComponents(JsonNode compNode) throws Exception {
        if (compNode == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verbal", compNode.path("v").asBoolean(false));
        result.put("somatic", compNode.path("s").asBoolean(false));
        JsonNode material = compNode.get("m");
        if (material != null) {
            if (material.isTextual()) {
                result.put("material", material.asText());
            } else if (material.isObject()) {
                result.put("material", material.path("text").asText(""));
            } else {
                result.put("material", true);
            }
        }
        return objectMapper.writeValueAsString(result);
    }

    private String parseDuration(JsonNode durNode) {
        if (durNode == null || !durNode.isArray() || durNode.isEmpty()) return null;
        JsonNode first = durNode.get(0);
        String type = first.path("type").asText("");

        return switch (type) {
            case "instant" -> "Instantaneous";
            case "permanent" -> "Until dispelled";
            case "special" -> "Special";
            case "timed" -> {
                int amount = first.path("duration").path("amount").asInt(0);
                String unit = first.path("duration").path("type").asText("");
                boolean concentration = first.path("concentration").asBoolean(false);
                String prefix = concentration ? "Concentration, up to " : "";
                yield prefix + amount + " " + unit + (amount > 1 ? "s" : "");
            }
            default -> type;
        };
    }

    private boolean isConcentration(JsonNode durNode) {
        if (durNode == null || !durNode.isArray() || durNode.isEmpty()) return false;
        return durNode.get(0).path("concentration").asBoolean(false);
    }

    private String parseDamageType(JsonNode dmgNode) {
        if (dmgNode == null || !dmgNode.isArray() || dmgNode.isEmpty()) return null;
        return dmgNode.get(0).asText(null);
    }

    private String parseSaveAbility(JsonNode saveNode) {
        if (saveNode == null || !saveNode.isArray() || saveNode.isEmpty()) return null;
        return saveNode.get(0).asText(null);
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
                if ("entries".equals(type) && entry.has("entries")) {
                    if (sb.length() > 0) sb.append("\n");
                    String name = entry.path("name").asText("");
                    if (!name.isEmpty()) sb.append(name).append(": ");
                    sb.append(flattenEntries(entry.get("entries")));
                } else if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (sb.length() > 0) sb.append("\n");
                        if (item.isTextual()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                        }
                    }
                } else if ("table".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("[Table]");
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
