package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CharacterClassSeeder {

    private final CharacterClassRepository classRepository;
    private final SubclassRepository subclassRepository;
    private final ObjectMapper objectMapper;

    private static final Set<String> SKIP_FILES = Set.of("class-mystic.json", "class-sidekick.json");

    private static final Set<String> PREPARED_CASTERS = Set.of("Cleric", "Druid", "Paladin", "Wizard", "Artificer");
    private static final Set<String> KNOWN_CASTERS = Set.of("Bard", "Ranger", "Sorcerer", "Warlock");

    private static final Map<String, Integer> SUBCLASS_LEVELS = Map.ofEntries(
            Map.entry("Cleric", 1), Map.entry("Sorcerer", 1), Map.entry("Warlock", 1),
            Map.entry("Druid", 2), Map.entry("Wizard", 2),
            Map.entry("Artificer", 3), Map.entry("Barbarian", 3), Map.entry("Bard", 3),
            Map.entry("Fighter", 3), Map.entry("Monk", 3), Map.entry("Paladin", 3),
            Map.entry("Ranger", 3), Map.entry("Rogue", 3)
    );

    public void seed() throws Exception {
        if (classRepository.count() > 0) {
            log.info("Classes already seeded, skipping");
            return;
        }

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:data/5etools/class/class-*.json");

        List<CharacterClass> allClasses = new ArrayList<>();
        List<SubclassData> allSubclassData = new ArrayList<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || SKIP_FILES.contains(filename) || filename.startsWith("fluff-")) continue;

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode classArray = root.get("class");
                if (classArray == null || !classArray.isArray()) continue;

                Map<String, JsonNode> classFeatureMap = buildFeatureMap(root.get("classFeature"));
                Map<String, JsonNode> subclassFeatureMap = buildSubclassFeatureMap(root.get("subclassFeature"));

                for (JsonNode classNode : classArray) {
                    CharacterClass cc = parseClass(classNode, classFeatureMap);
                    if (cc != null) allClasses.add(cc);
                }

                JsonNode subclassArray = root.get("subclass");
                if (subclassArray != null && subclassArray.isArray()) {
                    for (JsonNode scNode : subclassArray) {
                        allSubclassData.add(new SubclassData(scNode, subclassFeatureMap));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", filename, e.getMessage());
            }
        }

        classRepository.saveAll(allClasses);
        log.info("Seeded {} classes", allClasses.size());

        Map<String, CharacterClass> classLookup = new HashMap<>();
        for (CharacterClass cc : allClasses) {
            classLookup.put(cc.getName() + "|" + cc.getSource(), cc);
        }

        List<Subclass> allSubclasses = new ArrayList<>();
        for (SubclassData scd : allSubclassData) {
            Subclass sc = parseSubclass(scd, classLookup);
            if (sc != null) allSubclasses.add(sc);
        }

        subclassRepository.saveAll(allSubclasses);
        log.info("Seeded {} subclasses", allSubclasses.size());
        log.info("Class/subclass seeding complete: {} classes, {} subclasses", allClasses.size(), allSubclasses.size());
    }

    private CharacterClass parseClass(JsonNode node, Map<String, JsonNode> featureMap) {
        try {
            String name = node.path("name").asText();
            String source = node.path("source").asText();
            int hitDice = node.path("hd").path("faces").asInt(8);

            String spellcastingAbility = node.has("spellcastingAbility")
                    ? node.get("spellcastingAbility").asText().toUpperCase() : null;
            String casterProgression = node.path("casterProgression").asText(null);

            boolean isSpellcaster = spellcastingAbility != null;
            boolean isPreparedCaster = PREPARED_CASTERS.contains(name);
            boolean isKnownCaster = KNOWN_CASTERS.contains(name);
            boolean isPactMagic = "Warlock".equals(name);

            JsonNode profNode = node.get("proficiency");
            String savingThrows = parseSavingThrows(profNode);

            JsonNode startProf = node.path("startingProficiencies");
            String armorProf = parseStringArray(startProf.get("armor"));
            String weaponProf = parseStringArray(startProf.get("weapons"));
            String toolProf = parseToolProficiencies(startProf.get("tools"));
            String skillChoices = parseSkillChoices(startProf.get("skills"));

            String primaryAbility = inferPrimaryAbility(name, node);
            String spellSlotProgression = buildSpellSlotProgression(name, casterProgression);
            String features = parseClassFeatures(node, featureMap);
            String startingEquipment = node.has("startingEquipment")
                    ? objectMapper.writeValueAsString(node.get("startingEquipment")) : null;

            return CharacterClass.builder()
                    .name(name)
                    .source(source)
                    .hitDice(hitDice)
                    .primaryAbility(primaryAbility)
                    .savingThrowProficiencies(savingThrows)
                    .armorProficiencies(armorProf)
                    .weaponProficiencies(weaponProf)
                    .toolProficiencies(toolProf)
                    .skillChoices(skillChoices)
                    .spellcastingAbility(spellcastingAbility)
                    .isSpellcaster(isSpellcaster)
                    .isPreparedCaster(isPreparedCaster)
                    .isKnownCaster(isKnownCaster)
                    .isPactMagic(isPactMagic)
                    .spellSlotProgression(spellSlotProgression)
                    .features(features)
                    .startingEquipment(startingEquipment)
                    .subclassLevel(SUBCLASS_LEVELS.getOrDefault(name, 3))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse class {}: {}", node.path("name").asText("?"), e.getMessage());
            return null;
        }
    }

    private Subclass parseSubclass(SubclassData scd, Map<String, CharacterClass> classLookup) {
        try {
            JsonNode node = scd.node;
            String name = node.path("name").asText();
            String source = node.path("source").asText();
            String className = node.path("className").asText();
            String classSource = node.path("classSource").asText();

            CharacterClass parent = classLookup.get(className + "|" + classSource);
            if (parent == null) {
                log.warn("Parent class not found for subclass {}: {}|{}", name, className, classSource);
                return null;
            }

            String features = parseSubclassFeatures(node, scd.featureMap);
            String alwaysPreparedSpells = parseAlwaysPreparedSpells(node);

            return Subclass.builder()
                    .name(name)
                    .source(source)
                    .characterClass(parent)
                    .features(features)
                    .alwaysPreparedSpells(alwaysPreparedSpells)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse subclass {}: {}", scd.node.path("name").asText("?"), e.getMessage());
            return null;
        }
    }

    private String parseSavingThrows(JsonNode profNode) {
        if (profNode == null || !profNode.isArray()) return null;
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode p : profNode) {
                arr.add(p.asText().toUpperCase());
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    arr.add(FiveEToolsMarkupParser.parse(item.asText()));
                } else if (item.isObject() && item.has("proficiency")) {
                    for (JsonNode p : item.get("proficiency")) {
                        arr.add(FiveEToolsMarkupParser.parse(p.asText()));
                    }
                }
            }
            return arr.isEmpty() ? null : objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseToolProficiencies(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(FiveEToolsMarkupParser.parse(item.asText()));
            }
            return arr.isEmpty() ? null : objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseSkillChoices(JsonNode skillsNode) {
        if (skillsNode == null || !skillsNode.isArray() || skillsNode.isEmpty()) return null;
        try {
            JsonNode first = skillsNode.get(0);
            ObjectNode result = objectMapper.createObjectNode();

            if (first.has("any")) {
                result.put("count", first.get("any").asInt());
                ArrayNode allSkills = objectMapper.createArrayNode();
                for (String skill : ALL_SKILLS) allSkills.add(skill);
                result.set("from", allSkills);
            } else if (first.has("choose")) {
                JsonNode choose = first.get("choose");
                result.put("count", choose.path("count").asInt(2));
                ArrayNode from = objectMapper.createArrayNode();
                JsonNode fromArr = choose.get("from");
                if (fromArr != null && fromArr.isArray()) {
                    for (JsonNode s : fromArr) {
                        from.add(titleCase(s.asText()));
                    }
                }
                result.set("from", from);
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return null;
        }
    }

    private String inferPrimaryAbility(String className, JsonNode node) {
        JsonNode mc = node.path("multiclassing").path("requirements");
        if (mc.isMissingNode() || mc.isEmpty()) {
            return switch (className) {
                case "Barbarian" -> "STR";
                case "Fighter" -> "STR or DEX";
                case "Monk" -> "DEX and WIS";
                case "Rogue" -> "DEX";
                default -> null;
            };
        }
        List<String> abilities = new ArrayList<>();
        mc.fieldNames().forEachRemaining(f -> abilities.add(f.toUpperCase()));
        return String.join(" and ", abilities);
    }

    private String parseClassFeatures(JsonNode classNode, Map<String, JsonNode> featureMap) {
        try {
            JsonNode refs = classNode.get("classFeatures");
            if (refs == null || !refs.isArray()) return null;

            String className = classNode.path("name").asText();
            ArrayNode features = objectMapper.createArrayNode();

            for (JsonNode ref : refs) {
                String refStr;
                if (ref.isTextual()) {
                    refStr = ref.asText();
                } else if (ref.isObject() && ref.has("classFeature")) {
                    refStr = ref.get("classFeature").asText();
                } else {
                    continue;
                }

                JsonNode featureNode = featureMap.get(refStr);
                if (featureNode == null) continue;

                int level = featureNode.path("level").asInt(0);
                String featureName = featureNode.path("name").asText();
                String description = flattenEntries(featureNode.get("entries"));

                ObjectNode feat = objectMapper.createObjectNode();
                feat.put("level", level);
                feat.put("name", featureName);
                feat.put("description", description != null ? description : "");
                features.add(feat);
            }

            return features.isEmpty() ? null : objectMapper.writeValueAsString(features);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseSubclassFeatures(JsonNode scNode, Map<String, JsonNode> featureMap) {
        try {
            JsonNode refs = scNode.get("subclassFeatures");
            if (refs == null || !refs.isArray()) return null;

            ArrayNode features = objectMapper.createArrayNode();

            for (JsonNode ref : refs) {
                String refStr = ref.asText();
                JsonNode featureNode = featureMap.get(refStr);
                if (featureNode == null) continue;

                int level = featureNode.path("level").asInt(0);
                String featureName = featureNode.path("name").asText();
                String description = flattenEntries(featureNode.get("entries"));

                ObjectNode feat = objectMapper.createObjectNode();
                feat.put("level", level);
                feat.put("name", featureName);
                feat.put("description", description != null ? description : "");
                features.add(feat);
            }

            return features.isEmpty() ? null : objectMapper.writeValueAsString(features);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseAlwaysPreparedSpells(JsonNode scNode) {
        try {
            JsonNode addSpells = scNode.get("additionalSpells");
            if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;

            ObjectNode result = objectMapper.createObjectNode();
            for (JsonNode spellBlock : addSpells) {
                JsonNode prep = spellBlock.get("prepared");
                if (prep == null) {
                    prep = spellBlock.get("expanded");
                }
                if (prep == null) continue;

                final JsonNode prepared = prep;
                prepared.fieldNames().forEachRemaining(level -> {
                    JsonNode spells = prepared.get(level);
                    if (spells != null && spells.isArray()) {
                        ArrayNode arr = objectMapper.createArrayNode();
                        for (JsonNode s : spells) {
                            if (s.isTextual()) {
                                arr.add(FiveEToolsMarkupParser.parse(s.asText()));
                            }
                        }
                        if (!arr.isEmpty()) result.set(level, arr);
                    }
                });
            }

            return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, JsonNode> buildFeatureMap(JsonNode features) {
        Map<String, JsonNode> map = new HashMap<>();
        if (features == null || !features.isArray()) return map;
        for (JsonNode f : features) {
            String name = f.path("name").asText();
            String className = f.path("className").asText();
            String classSource = f.path("classSource").asText();
            String source = f.path("source").asText();
            int level = f.path("level").asInt();
            String key = name + "|" + className + "|" + classSource + "|" + level;
            if (!source.equals(classSource)) {
                key = name + "|" + className + "|" + classSource + "|" + level + "|" + source;
            }
            map.put(key, f);
            map.put(name + "|" + className + "||" + level, f);
            if (!source.equals(classSource)) {
                map.put(name + "|" + className + "||" + level + "|" + source, f);
            }
        }
        return map;
    }

    private Map<String, JsonNode> buildSubclassFeatureMap(JsonNode features) {
        Map<String, JsonNode> map = new HashMap<>();
        if (features == null || !features.isArray()) return map;
        for (JsonNode f : features) {
            String name = f.path("name").asText();
            String className = f.path("className").asText();
            String classSource = f.path("classSource").asText();
            String scShort = f.path("subclassShortName").asText();
            String scSource = f.path("subclassSource").asText();
            String source = f.path("source").asText();
            int level = f.path("level").asInt();
            String key = name + "|" + className + "||" + scShort + "||" + level;
            map.put(key, f);
            if (!source.equals(scSource)) {
                map.put(key + "|" + source, f);
            }
            String key2 = name + "|" + className + "|" + classSource + "|" + scShort + "|" + scSource + "|" + level;
            map.put(key2, f);
        }
        return map;
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
                if ("entries".equals(type) && entry.has("entries")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    String nested = flattenEntries(entry.get("entries"));
                    if (nested != null) sb.append(nested);
                } else if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (!sb.isEmpty()) sb.append("\n");
                        if (item.isTextual()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                        } else if (item.isObject() && item.has("entry")) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.path("entry").asText()));
                        } else if (item.isObject() && item.has("entries")) {
                            String nested = flattenEntries(item.get("entries"));
                            if (nested != null) sb.append("- ").append(nested);
                        }
                    }
                } else if ("table".equals(type) && entry.has("rows")) {
                    for (JsonNode row : entry.get("rows")) {
                        if (row.isArray()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            List<String> cells = new ArrayList<>();
                            for (JsonNode cell : row) {
                                cells.add(FiveEToolsMarkupParser.parse(cell.asText()));
                            }
                            sb.append("| ").append(String.join(" | ", cells)).append(" |");
                        }
                    }
                } else if ("options".equals(type) && entry.has("entries")) {
                    String nested = flattenEntries(entry.get("entries"));
                    if (nested != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(nested);
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String buildSpellSlotProgression(String className, String casterProgression) {
        if (casterProgression == null) return null;
        try {
            Map<String, Map<String, Integer>> table = switch (casterProgression) {
                case "full" -> buildFullCasterTable();
                case "1/2" -> buildHalfCasterTable();
                case "pact" -> buildPactMagicTable();
                case "artificer" -> buildArtificerTable();
                default -> null;
            };
            if (table == null) return null;
            return objectMapper.writeValueAsString(table);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Map<String, Integer>> buildFullCasterTable() {
        Map<String, Map<String, Integer>> t = new LinkedHashMap<>();
        t.put("1",  Map.of("1",2));
        t.put("2",  Map.of("1",3));
        t.put("3",  Map.of("1",4, "2",2));
        t.put("4",  Map.of("1",4, "2",3));
        t.put("5",  Map.of("1",4, "2",3, "3",2));
        t.put("6",  Map.of("1",4, "2",3, "3",3));
        t.put("7",  Map.of("1",4, "2",3, "3",3, "4",1));
        t.put("8",  Map.of("1",4, "2",3, "3",3, "4",2));
        t.put("9",  Map.of("1",4, "2",3, "3",3, "4",3, "5",1));
        t.put("10", Map.of("1",4, "2",3, "3",3, "4",3, "5",2));
        t.put("11", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1));
        t.put("12", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1));
        t.put("13", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1, "7",1));
        t.put("14", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1, "7",1));
        t.put("15", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1, "7",1, "8",1));
        t.put("16", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1, "7",1, "8",1));
        t.put("17", Map.of("1",4, "2",3, "3",3, "4",3, "5",2, "6",1, "7",1, "8",1, "9",1));
        t.put("18", Map.of("1",4, "2",3, "3",3, "4",3, "5",3, "6",1, "7",1, "8",1, "9",1));
        t.put("19", Map.of("1",4, "2",3, "3",3, "4",3, "5",3, "6",2, "7",1, "8",1, "9",1));
        t.put("20", Map.of("1",4, "2",3, "3",3, "4",3, "5",3, "6",2, "7",2, "8",1, "9",1));
        return t;
    }

    private Map<String, Map<String, Integer>> buildHalfCasterTable() {
        Map<String, Map<String, Integer>> t = new LinkedHashMap<>();
        t.put("2",  Map.of("1",2));
        t.put("3",  Map.of("1",3));
        t.put("4",  Map.of("1",3));
        t.put("5",  Map.of("1",4, "2",2));
        t.put("6",  Map.of("1",4, "2",2));
        t.put("7",  Map.of("1",4, "2",3));
        t.put("8",  Map.of("1",4, "2",3));
        t.put("9",  Map.of("1",4, "2",3, "3",2));
        t.put("10", Map.of("1",4, "2",3, "3",2));
        t.put("11", Map.of("1",4, "2",3, "3",3));
        t.put("12", Map.of("1",4, "2",3, "3",3));
        t.put("13", Map.of("1",4, "2",3, "3",3, "4",1));
        t.put("14", Map.of("1",4, "2",3, "3",3, "4",1));
        t.put("15", Map.of("1",4, "2",3, "3",3, "4",2));
        t.put("16", Map.of("1",4, "2",3, "3",3, "4",2));
        t.put("17", Map.of("1",4, "2",3, "3",3, "4",3, "5",1));
        t.put("18", Map.of("1",4, "2",3, "3",3, "4",3, "5",1));
        t.put("19", Map.of("1",4, "2",3, "3",3, "4",3, "5",2));
        t.put("20", Map.of("1",4, "2",3, "3",3, "4",3, "5",2));
        return t;
    }

    private Map<String, Map<String, Integer>> buildArtificerTable() {
        Map<String, Map<String, Integer>> t = new LinkedHashMap<>();
        t.put("1",  Map.of("1",2));
        t.put("2",  Map.of("1",2));
        t.put("3",  Map.of("1",3));
        t.put("4",  Map.of("1",3));
        t.put("5",  Map.of("1",4, "2",2));
        t.put("6",  Map.of("1",4, "2",2));
        t.put("7",  Map.of("1",4, "2",3));
        t.put("8",  Map.of("1",4, "2",3));
        t.put("9",  Map.of("1",4, "2",3, "3",2));
        t.put("10", Map.of("1",4, "2",3, "3",2));
        t.put("11", Map.of("1",4, "2",3, "3",3));
        t.put("12", Map.of("1",4, "2",3, "3",3));
        t.put("13", Map.of("1",4, "2",3, "3",3, "4",1));
        t.put("14", Map.of("1",4, "2",3, "3",3, "4",1));
        t.put("15", Map.of("1",4, "2",3, "3",3, "4",2));
        t.put("16", Map.of("1",4, "2",3, "3",3, "4",2));
        t.put("17", Map.of("1",4, "2",3, "3",3, "4",3, "5",1));
        t.put("18", Map.of("1",4, "2",3, "3",3, "4",3, "5",1));
        t.put("19", Map.of("1",4, "2",3, "3",3, "4",3, "5",2));
        t.put("20", Map.of("1",4, "2",3, "3",3, "4",3, "5",2));
        return t;
    }

    private Map<String, Map<String, Integer>> buildPactMagicTable() {
        Map<String, Map<String, Integer>> t = new LinkedHashMap<>();
        t.put("1",  Map.of("1",1));
        t.put("2",  Map.of("1",2));
        t.put("3",  Map.of("2",2));
        t.put("4",  Map.of("2",2));
        t.put("5",  Map.of("3",2));
        t.put("6",  Map.of("3",2));
        t.put("7",  Map.of("4",2));
        t.put("8",  Map.of("4",2));
        t.put("9",  Map.of("5",2));
        t.put("10", Map.of("5",2));
        t.put("11", Map.of("5",3));
        t.put("12", Map.of("5",3));
        t.put("13", Map.of("5",3));
        t.put("14", Map.of("5",3));
        t.put("15", Map.of("5",3));
        t.put("16", Map.of("5",3));
        t.put("17", Map.of("5",4));
        t.put("18", Map.of("5",4));
        t.put("19", Map.of("5",4));
        t.put("20", Map.of("5",4));
        return t;
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Arrays.stream(s.split("\\s+"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(s);
    }

    private static final List<String> ALL_SKILLS = List.of(
            "Acrobatics", "Animal Handling", "Arcana", "Athletics", "Deception",
            "History", "Insight", "Intimidation", "Investigation", "Medicine",
            "Nature", "Perception", "Performance", "Persuasion", "Religion",
            "Sleight of Hand", "Stealth", "Survival"
    );

    private record SubclassData(JsonNode node, Map<String, JsonNode> featureMap) {}
}
