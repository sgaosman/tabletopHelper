package com.questkeeper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questkeeper.reference.Spell;
import com.questkeeper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class SpellSeeder {

    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> SCHOOL_MAP = Map.of(
            "A", "Abjuration", "C", "Conjuration", "D", "Divination",
            "E", "Enchantment", "I", "Illusion", "N", "Necromancy",
            "T", "Transmutation", "V", "Evocation"
    );

    public void seed() throws Exception {
        if (spellRepository.count() > 0) {
            log.info("Spells already seeded, skipping");
            return;
        }

        Map<String, Set<String>> classMap = loadSpellClassMap();
        log.info("Loaded spell-class associations for {} spells", classMap.size());

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:data/5etools/spells/spells-*.json");

        List<Spell> allSpells = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename != null && filename.contains("fluff")) continue;

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode spells = root.get("spell");
                if (spells == null || !spells.isArray()) continue;

                for (JsonNode s : spells) {
                    if (s.has("_copy")) continue;
                    String key = s.path("name").asText() + "|" + s.path("source").asText();
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    Spell spell = parseSpell(s, classMap);
                    if (spell != null) allSpells.add(spell);
                }
            } catch (Exception e) {
                log.warn("Error parsing {}: {}", filename, e.getMessage());
            }
        }

        int total = 0;
        List<Spell> batch = new ArrayList<>();
        for (Spell spell : allSpells) {
            batch.add(spell);
            total++;
            if (batch.size() >= 100) {
                spellRepository.saveAll(batch);
                batch.clear();
                log.info("Seeding spells... {}", total);
            }
        }
        if (!batch.isEmpty()) {
            spellRepository.saveAll(batch);
        }
        log.info("Spell seeding complete: {} spells", total);
    }

    private Map<String, Set<String>> loadSpellClassMap() {
        Map<String, Set<String>> map = new HashMap<>();

        try {
            ClassPathResource sourcesResource = new ClassPathResource("data/5etools/spells/sources.json");
            try (InputStream is = sourcesResource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                Iterator<Map.Entry<String, JsonNode>> sourceEntries = root.fields();
                while (sourceEntries.hasNext()) {
                    Map.Entry<String, JsonNode> sourceEntry = sourceEntries.next();
                    String sourceCode = sourceEntry.getKey();
                    JsonNode spellsInSource = sourceEntry.getValue();

                    Iterator<Map.Entry<String, JsonNode>> spellEntries = spellsInSource.fields();
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
            }
        } catch (Exception e) {
            log.warn("Failed to load spell sources.json: {}", e.getMessage());
        }

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] classFiles = resolver.getResources("classpath:data/5etools/class/class-*.json");
            for (Resource classFile : classFiles) {
                String filename = classFile.getFilename();
                if (filename != null && filename.contains("fluff")) continue;

                try (InputStream is = classFile.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
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
                } catch (Exception e) {
                    log.warn("Error reading class file {}: {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load class files for spell associations: {}", e.getMessage());
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
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collectSubclassSpells(fields.next().getValue(), label, target);
            }
        }
    }

    private Spell parseSpell(JsonNode s, Map<String, Set<String>> classMap) {
        try {
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
                try {
                    classesJson = objectMapper.writeValueAsString(new ArrayList<>(spellClasses));
                } catch (Exception e) {
                    log.warn("Failed to serialize classes for spell {}", spellName);
                }
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
        } catch (Exception e) {
            log.warn("Failed to parse spell {}: {}", s.path("name").asText("unknown"), e.getMessage());
            return null;
        }
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

    private String parseComponents(JsonNode compNode) {
        if (compNode == null) return null;
        try {
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
        } catch (Exception e) {
            return null;
        }
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
