package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.SpellSlotTables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CharacterClassLoader implements ContentLoader {

    private final CharacterClassRepository classRepository;
    private final SubclassRepository subclassRepository;
    private final ObjectMapper objectMapper;

    // Class files we don't load: Mystic is unofficial playtest material, and sidekicks aren't player classes
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

    @Override
    public String name() {
        return "classes";
    }

    @Override
    public List<String> files() {
        return List.of("data/5etools/class/class-*.json");
    }

    // Version 1 also writes the spell slot tables of rules/SpellSlotTables, so a change to them raises it
    @Override
    public int version() {
        return 1;
    }

    /** Loads the classes, then the subclasses under them. */
    @Override
    public void load(LoadContext context) {
        List<CharacterClass> allClasses = new ArrayList<>();
        List<SubclassData> allSubclassData = new ArrayList<>();

        for (LoadContext.DataFile file : context.files("data/5etools/class/class-*.json")) {
            String filename = file.name();
            if (filename == null || filename.startsWith("fluff-")) {
                context.skip("fluff files");
                continue;
            }
            if (SKIP_FILES.contains(filename)) {
                context.skip("SKIP_FILES");
                continue;
            }

            JsonNode root = context.read(file);
            JsonNode classArray = root.get("class");
            if (classArray == null || !classArray.isArray()) {
                context.skip("files with no class array");
                continue;
            }

            Map<String, JsonNode> classFeatureMap = buildFeatureMap(root.get("classFeature"));
            Map<String, JsonNode> subclassFeatureMap = buildSubclassFeatureMap(root.get("subclassFeature"));

            for (JsonNode classNode : classArray) {
                allClasses.add(LoadContext.reading(filename + ": class " + classNode.path("name").asText("?") + "|"
                        + classNode.path("source").asText(""), () -> parseClass(classNode, classFeatureMap)));
            }

            JsonNode subclassArray = root.get("subclass");
            if (subclassArray != null && subclassArray.isArray()) {
                for (JsonNode scNode : subclassArray) {
                    allSubclassData.add(new SubclassData(filename, scNode, subclassFeatureMap));
                }
            }
        }

        Map<String, CharacterClass> classLookup = context.upsert("character_classes", classRepository, allClasses,
                cc -> LoadContext.nameAndSource(cc.getName(), cc.getSource()));

        List<Subclass> allSubclasses = new ArrayList<>();
        for (SubclassData scd : allSubclassData) {
            JsonNode node = scd.node;
            String className = node.path("className").asText();
            String classSource = node.path("classSource").asText();
            CharacterClass parent = classLookup.get(className + "|" + classSource);
            if (parent == null) {
                log.debug("Parent class not found for subclass {}: {}|{}", node.path("name").asText(), className,
                        classSource);
                context.skip("subclasses of a class not loaded");
                continue;
            }
            allSubclasses.add(LoadContext.reading(scd.filename + ": subclass " + node.path("name").asText("?") + "|"
                    + node.path("source").asText("") + " of " + className + "|" + classSource,
                    () -> parseSubclass(scd, parent)));
        }

        context.upsert("subclasses", subclassRepository, allSubclasses, CharacterClassLoader::subclassKey);
    }

    /** A subclass's natural key: its class's name and source, then its own. */
    static String subclassKey(Subclass subclass) {
        CharacterClass parent = subclass.getCharacterClass();
        return LoadContext.nameAndSource(parent.getName(), parent.getSource()) + "|"
                + LoadContext.nameAndSource(subclass.getName(), subclass.getSource());
    }

    private CharacterClass parseClass(JsonNode node, Map<String, JsonNode> featureMap) throws Exception {
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

        String multiclassRequirements = parseMulticlassRequirements(node);
        String multiclassProficiencies = parseMulticlassProficiencies(node);

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
                .multiclassRequirements(multiclassRequirements)
                .multiclassProficiencies(multiclassProficiencies)
                .build();
    }

    private Subclass parseSubclass(SubclassData scd, CharacterClass parent) throws Exception {
        JsonNode node = scd.node;
        String name = node.path("name").asText();
        String source = node.path("source").asText();

        String features = parseSubclassFeatures(node, scd.featureMap);
        String alwaysPreparedSpells = parseAlwaysPreparedSpells(node);
        String expandedSpellList = parseExpandedSpellList(node);

        return Subclass.builder()
                .name(name)
                .source(source)
                .characterClass(parent)
                .features(features)
                .alwaysPreparedSpells(alwaysPreparedSpells)
                .expandedSpellList(expandedSpellList)
                .build();
    }

    private String parseSavingThrows(JsonNode profNode) throws Exception {
        if (profNode == null || !profNode.isArray()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        for (JsonNode p : profNode) {
            arr.add(p.asText().toUpperCase());
        }
        return objectMapper.writeValueAsString(arr);
    }

    private String parseStringArray(JsonNode node) throws Exception {
        if (node == null || !node.isArray()) return null;
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
    }

    private String parseToolProficiencies(JsonNode node) throws Exception {
        if (node == null || !node.isArray()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        for (JsonNode item : node) {
            arr.add(FiveEToolsMarkupParser.parse(item.asText()));
        }
        return arr.isEmpty() ? null : objectMapper.writeValueAsString(arr);
    }

    private String parseSkillChoices(JsonNode skillsNode) throws Exception {
        if (skillsNode == null || !skillsNode.isArray() || skillsNode.isEmpty()) return null;
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
    }

    private String parseMulticlassRequirements(JsonNode classNode) throws Exception {
        JsonNode mc = classNode.path("multiclassing").path("requirements");
        if (mc.isMissingNode() || mc.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();

        if (mc.has("or") && mc.get("or").isArray()) {
            JsonNode orBlock = mc.get("or").get(0);
            Iterator<String> fields = orBlock.fieldNames();
            while (fields.hasNext()) {
                String ability = fields.next();
                ObjectNode req = objectMapper.createObjectNode();
                req.put("ability", ability.toUpperCase());
                req.put("minimum", orBlock.get(ability).asInt());
                req.put("operator", "OR");
                result.add(req);
            }
        } else {
            Iterator<String> fields = mc.fieldNames();
            while (fields.hasNext()) {
                String ability = fields.next();
                ObjectNode req = objectMapper.createObjectNode();
                req.put("ability", ability.toUpperCase());
                req.put("minimum", mc.get(ability).asInt());
                result.add(req);
            }
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String parseMulticlassProficiencies(JsonNode classNode) throws Exception {
        JsonNode pg = classNode.path("multiclassing").path("proficienciesGained");
        if (pg.isMissingNode() || pg.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();

        if (pg.has("armor") && pg.get("armor").isArray()) {
            ArrayNode armor = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("armor")) {
                if (item.isTextual()) {
                    armor.add(titleCase(item.asText()));
                } else if (item.isObject() && item.has("proficiency")) {
                    armor.add(titleCase(item.get("proficiency").asText()));
                }
            }
            if (!armor.isEmpty()) result.set("armor", armor);
        }

        if (pg.has("weapons") && pg.get("weapons").isArray()) {
            ArrayNode weapons = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("weapons")) {
                weapons.add(FiveEToolsMarkupParser.parse(titleCase(item.asText())));
            }
            if (!weapons.isEmpty()) result.set("weapons", weapons);
        }

        if (pg.has("tools") && pg.get("tools").isArray()) {
            ArrayNode tools = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("tools")) {
                tools.add(FiveEToolsMarkupParser.parse(item.asText()));
            }
            if (!tools.isEmpty()) result.set("tools", tools);
        }

        if (pg.has("skills") && pg.get("skills").isArray()) {
            JsonNode first = pg.get("skills").get(0);
            if (first != null && first.has("choose")) {
                ObjectNode skillChoice = objectMapper.createObjectNode();
                skillChoice.put("count", first.get("choose").path("count").asInt(1));
                ArrayNode from = objectMapper.createArrayNode();
                for (JsonNode s : first.get("choose").path("from")) {
                    from.add(titleCase(s.asText()));
                }
                skillChoice.set("from", from);
                result.set("skills", skillChoice);
            }
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    // The abilities the class's multiclassing prerequisite names, as the PHB's class table gives its primary ability:
    // "STR and CHA" for a Paladin, and "STR or DEX" for a Fighter, whose prerequisite is either
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
        JsonNode either = mc.path("or").path(0);
        if (either.isObject()) {
            return String.join(" or ", abilityNames(either));
        }
        return String.join(" and ", abilityNames(mc));
    }

    private static List<String> abilityNames(JsonNode requirements) {
        List<String> abilities = new ArrayList<>();
        requirements.fieldNames().forEachRemaining(f -> abilities.add(f.toUpperCase()));
        return abilities;
    }

    private String parseClassFeatures(JsonNode classNode, Map<String, JsonNode> featureMap) throws Exception {
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
    }

    private String parseSubclassFeatures(JsonNode scNode, Map<String, JsonNode> featureMap) throws Exception {
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
    }

    private String parseAlwaysPreparedSpells(JsonNode scNode) throws Exception {
        JsonNode addSpells = scNode.get("additionalSpells");
        if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode spellBlock : addSpells) {
            JsonNode prep = spellBlock.get("prepared");
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
    }

    private String parseExpandedSpellList(JsonNode scNode) throws Exception {
        JsonNode addSpells = scNode.get("additionalSpells");
        if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode spellBlock : addSpells) {
            JsonNode expanded = spellBlock.get("expanded");
            if (expanded == null) continue;

            expanded.fieldNames().forEachRemaining(level -> {
                JsonNode spells = expanded.get(level);
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

    private String buildSpellSlotProgression(String className, String casterProgression) throws Exception {
        if (casterProgression == null) return null;
        // Shared with SpellSlotCalculator (D153); 5etools names the half-caster progression "1/2"
        Map<String, Map<String, Integer>> table = switch (casterProgression) {
            case "full", "pact", "artificer" -> SpellSlotTables.progression(casterProgression);
            case "1/2" -> SpellSlotTables.progression("half");
            default -> null;
        };
        if (table == null) return null;
        return objectMapper.writeValueAsString(table);
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

    private record SubclassData(String filename, JsonNode node, Map<String, JsonNode> featureMap) {}
}
