package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class RaceSeeder {

    private final RaceRepository raceRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> SIZE_MAP = Map.of(
            "M", "Medium", "S", "Small", "L", "Large", "T", "Tiny", "H", "Huge", "V", "Varies"
    );

    private static final Map<String, String> ABILITY_MAP = Map.of(
            "str", "STR", "dex", "DEX", "con", "CON", "int", "INT", "wis", "WIS", "cha", "CHA"
    );

    private static final List<String> STANDARD_LANGUAGES = List.of(
            "Common", "Dwarvish", "Elvish", "Giant", "Gnomish", "Goblin", "Halfling", "Orc"
    );

    private static final List<String> MARTIAL_WEAPONS = List.of(
            "Battleaxe", "Flail", "Glaive", "Greataxe", "Greatsword", "Halberd", "Lance",
            "Longsword", "Maul", "Morningstar", "Pike", "Rapier", "Scimitar", "Shortsword",
            "Trident", "War Pick", "Warhammer", "Whip",
            "Blowgun", "Hand Crossbow", "Heavy Crossbow", "Longbow", "Net"
    );

    public void seed() throws Exception {
        if (raceRepository.count() > 0) {
            log.info("Races already seeded, skipping");
            return;
        }

        ClassPathResource resource = new ClassPathResource("data/5etools/races.json");
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode racesArray = root.get("race");
            JsonNode subracesArray = root.get("subrace");

            if (racesArray == null || !racesArray.isArray()) return;

            Map<String, JsonNode> raceIndex = new HashMap<>();
            for (JsonNode r : racesArray) {
                String key = r.path("name").asText() + "|" + r.path("source").asText();
                raceIndex.put(key, r);
            }

            List<Race> batch = new ArrayList<>();

            for (JsonNode r : racesArray) {
                Race race = parseRace(r, null, null);
                if (race != null) batch.add(race);
            }

            if (subracesArray != null && subracesArray.isArray()) {
                for (JsonNode sr : subracesArray) {
                    if (sr.has("_copy")) continue;
                    if (!sr.has("raceName")) continue;

                    String subName = sr.path("name").asText("");
                    if ("?".equals(subName)) continue;

                    String parentKey = sr.path("raceName").asText() + "|" + sr.path("raceSource").asText();
                    JsonNode parent = raceIndex.get(parentKey);
                    if (parent == null) {
                        log.warn("Subrace '{}' references unknown parent '{}'", subName, parentKey);
                        continue;
                    }

                    if (subName.isEmpty()) {
                        String mergedBonuses = resolveAbilityBonuses(sr, parent);
                        if (!"[]".equals(mergedBonuses)) {
                            String parentName = sr.path("raceName").asText();
                            String parentSource = parent.path("source").asText("");
                            for (Race r : batch) {
                                if (r.getName().equals(parentName) && r.getSource().equals(parentSource)
                                        && "[]".equals(r.getAbilityScoreBonuses())) {
                                    r.setAbilityScoreBonuses(mergedBonuses);
                                    break;
                                }
                            }
                        }
                        continue;
                    }

                    Race race = parseRace(sr, parent, sr.path("raceName").asText());
                    if (race != null) batch.add(race);
                }
            }

            raceRepository.saveAll(batch);
            log.info("Race seeding complete: {} races (base + subraces)", batch.size());
        }
    }

    private Race parseRace(JsonNode node, JsonNode parent, String parentRaceName) {
        try {
            boolean isSubrace = parent != null;
            String name;
            if (isSubrace) {
                name = node.path("name").asText() + " " + parentRaceName;
            } else {
                name = node.path("name").asText();
            }
            String source = node.path("source").asText("");

            String size = resolveSize(node, parent);
            String speed = resolveSpeed(node, parent);
            String abilityBonuses = resolveAbilityBonuses(node, parent);
            String creatureType = resolveCreatureType(node, parent);
            Integer darkvision = resolveDarkvision(node, parent);
            String traits = resolveTraits(node, parent);
            String proficiencies = resolveProficiencies(node, parent);
            String resistances = resolveResistances(node, parent);
            String description = buildDescription(node, parent);

            return Race.builder()
                    .name(name)
                    .source(source)
                    .size(size)
                    .speed(speed)
                    .abilityScoreBonuses(abilityBonuses)
                    .creatureType(creatureType)
                    .darkvision(darkvision)
                    .traits(traits)
                    .proficiencies(proficiencies)
                    .resistances(resistances)
                    .raceChoices(resolveRaceChoices(node, parent))
                    .baseRaceName(parentRaceName)
                    .description(description)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse race {}: {}", node.path("name").asText("unknown"), e.getMessage());
            return null;
        }
    }

    private String resolveSize(JsonNode node, JsonNode parent) {
        JsonNode sizeNode = node.get("size");
        if (sizeNode == null && parent != null) sizeNode = parent.get("size");
        if (sizeNode == null || !sizeNode.isArray()) return "Medium";

        List<String> sizes = new ArrayList<>();
        for (JsonNode s : sizeNode) {
            sizes.add(SIZE_MAP.getOrDefault(s.asText(), s.asText()));
        }
        return String.join("/", sizes);
    }

    private String resolveSpeed(JsonNode node, JsonNode parent) {
        try {
            JsonNode speedNode = node.get("speed");
            if (speedNode == null && parent != null) speedNode = parent.get("speed");
            if (speedNode == null) return "{\"walk\":30}";

            if (speedNode.isInt() || speedNode.isNumber()) {
                ObjectNode obj = objectMapper.createObjectNode();
                obj.put("walk", speedNode.asInt());
                return objectMapper.writeValueAsString(obj);
            }
            if (speedNode.isObject()) {
                ObjectNode clean = objectMapper.createObjectNode();
                speedNode.fields().forEachRemaining(entry -> {
                    JsonNode v = entry.getValue();
                    if (v.isInt() || v.isNumber()) {
                        clean.put(entry.getKey(), v.asInt());
                    } else if (v.isBoolean() && v.asBoolean()) {
                        clean.put(entry.getKey(), true);
                    } else if (v.isObject() && v.has("number")) {
                        clean.put(entry.getKey(), v.get("number").asInt());
                    }
                });
                return objectMapper.writeValueAsString(clean);
            }
            return "{\"walk\":30}";
        } catch (Exception e) {
            return "{\"walk\":30}";
        }
    }

    private String resolveAbilityBonuses(JsonNode node, JsonNode parent) {
        try {
            Set<String> overwritten = getOverwriteKeys(node);
            List<Map<String, Object>> bonuses = new ArrayList<>();

            if (parent != null && !overwritten.contains("ability")) {
                addAbilityBonuses(parent.get("ability"), bonuses);
            }
            addAbilityBonuses(node.get("ability"), bonuses);

            String lineage = node.path("lineage").asText(null);
            if (lineage == null && parent != null) lineage = parent.path("lineage").asText(null);
            if (bonuses.isEmpty() && "VRGR".equals(lineage)) {
                Map<String, Object> choose1 = new LinkedHashMap<>();
                choose1.put("ability", "CHOOSE");
                choose1.put("from", List.of("STR", "DEX", "CON", "INT", "WIS", "CHA"));
                choose1.put("count", 1);
                choose1.put("bonus", 2);
                bonuses.add(choose1);

                Map<String, Object> choose2 = new LinkedHashMap<>();
                choose2.put("ability", "CHOOSE");
                choose2.put("from", List.of("STR", "DEX", "CON", "INT", "WIS", "CHA"));
                choose2.put("count", 1);
                choose2.put("bonus", 1);
                bonuses.add(choose2);
            }

            if (bonuses.isEmpty()) return "[]";
            return objectMapper.writeValueAsString(bonuses);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void addAbilityBonuses(JsonNode abilityArray, List<Map<String, Object>> bonuses) {
        if (abilityArray == null || !abilityArray.isArray()) return;
        for (JsonNode obj : abilityArray) {
            obj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if ("choose".equals(key)) {
                    JsonNode choose = entry.getValue();
                    Map<String, Object> bonus = new LinkedHashMap<>();
                    bonus.put("ability", "CHOOSE");
                    if (choose.has("from")) {
                        List<String> from = new ArrayList<>();
                        choose.get("from").forEach(n -> from.add(ABILITY_MAP.getOrDefault(n.asText(), n.asText().toUpperCase())));
                        bonus.put("from", from);
                    }
                    int count = choose.has("count") ? choose.get("count").asInt() : 1;
                    int amount = choose.has("amount") ? choose.get("amount").asInt() : 1;
                    bonus.put("count", count);
                    bonus.put("bonus", amount);
                    bonuses.add(bonus);
                } else {
                    String abilityName = ABILITY_MAP.getOrDefault(key, key.toUpperCase());
                    Map<String, Object> bonus = new LinkedHashMap<>();
                    bonus.put("ability", abilityName);
                    bonus.put("bonus", entry.getValue().asInt());
                    bonuses.add(bonus);
                }
            });
        }
    }

    private String resolveCreatureType(JsonNode node, JsonNode parent) {
        JsonNode ct = node.get("creatureTypes");
        if (ct == null && parent != null) ct = parent.get("creatureTypes");
        if (ct != null && ct.isArray() && ct.size() > 0) {
            String type = ct.get(0).asText();
            return type.substring(0, 1).toUpperCase() + type.substring(1);
        }
        return "Humanoid";
    }

    private Integer resolveDarkvision(JsonNode node, JsonNode parent) {
        JsonNode dv = node.get("darkvision");
        if (dv == null && parent != null) dv = parent.get("darkvision");
        if (dv != null && dv.isInt()) return dv.asInt();
        return null;
    }

    private String resolveTraits(JsonNode node, JsonNode parent) {
        try {
            List<Map<String, String>> traits = new ArrayList<>();
            Set<String> overwritten = getOverwriteKeys(node);

            if (parent != null && !overwritten.contains("entries")) {
                extractTraits(parent.get("entries"), traits);
            }

            Set<String> overwriteNames = new HashSet<>();
            if (node.has("entries") && node.get("entries").isArray()) {
                for (JsonNode entry : node.get("entries")) {
                    if (entry.isObject() && entry.has("data")) {
                        JsonNode data = entry.get("data");
                        if (data.has("overwrite")) {
                            overwriteNames.add(data.get("overwrite").asText());
                        }
                    }
                }
            }
            if (!overwriteNames.isEmpty()) {
                traits.removeIf(t -> overwriteNames.contains(t.get("name")));
            }

            extractTraits(node.get("entries"), traits);

            if (traits.isEmpty()) return "[]";
            return objectMapper.writeValueAsString(traits);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void extractTraits(JsonNode entries, List<Map<String, String>> traits) {
        if (entries == null || !entries.isArray()) return;
        for (JsonNode entry : entries) {
            if (!entry.isObject()) continue;
            String name = entry.path("name").asText(null);
            if (name == null || name.isEmpty()) continue;

            StringBuilder desc = new StringBuilder();
            JsonNode subEntries = entry.get("entries");
            if (subEntries != null && subEntries.isArray()) {
                for (JsonNode e : subEntries) {
                    if (e.isTextual()) {
                        if (desc.length() > 0) desc.append("\n");
                        desc.append(FiveEToolsMarkupParser.parse(e.asText()));
                    } else if (e.isObject() && "list".equals(e.path("type").asText())) {
                        JsonNode items = e.get("items");
                        if (items != null) {
                            for (JsonNode item : items) {
                                if (item.isTextual()) {
                                    if (desc.length() > 0) desc.append("\n");
                                    desc.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                                }
                            }
                        }
                    }
                }
            }

            Map<String, String> trait = new LinkedHashMap<>();
            trait.put("name", name);
            trait.put("description", desc.toString());
            traits.add(trait);
        }
    }

    private String resolveProficiencies(JsonNode node, JsonNode parent) {
        try {
            Set<String> overwritten = getOverwriteKeys(node);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode skills = objectMapper.createArrayNode();
            ArrayNode weapons = objectMapper.createArrayNode();
            ArrayNode armor = objectMapper.createArrayNode();
            ArrayNode tools = objectMapper.createArrayNode();
            ArrayNode languages = objectMapper.createArrayNode();

            if (parent != null) {
                if (!overwritten.contains("skillProficiencies"))
                    extractSkillProficiencies(parent.get("skillProficiencies"), skills);
                if (!overwritten.contains("languageProficiencies"))
                    extractLanguageProficiencies(parent.get("languageProficiencies"), languages);
                if (!overwritten.contains("weaponProficiencies"))
                    extractWeaponProficiencies(parent.get("weaponProficiencies"), weapons);
                if (!overwritten.contains("armorProficiencies"))
                    extractArmorProficiencies(parent.get("armorProficiencies"), armor);
                if (!overwritten.contains("toolProficiencies"))
                    extractToolProficiencies(parent.get("toolProficiencies"), tools);
            }

            extractSkillProficiencies(node.get("skillProficiencies"), skills);
            extractLanguageProficiencies(node.get("languageProficiencies"), languages);
            extractWeaponProficiencies(node.get("weaponProficiencies"), weapons);
            extractArmorProficiencies(node.get("armorProficiencies"), armor);
            extractToolProficiencies(node.get("toolProficiencies"), tools);

            result.set("skills", skills);
            result.set("weapons", weapons);
            result.set("armor", armor);
            result.set("tools", tools);
            result.set("languages", languages);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"skills\":[],\"weapons\":[],\"armor\":[],\"tools\":[],\"languages\":[]}";
        }
    }

    private void extractSkillProficiencies(JsonNode node, ArrayNode skills) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!"choose".equals(key) && !"any".equals(key) && entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    String skillName = key.substring(0, 1).toUpperCase() + key.substring(1);
                    skills.add(skillName);
                }
            });
        }
    }

    private void extractLanguageProficiencies(JsonNode node, ArrayNode languages) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if ("anyStandard".equals(key) || "other".equals(key)) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    String lang = key.substring(0, 1).toUpperCase() + key.substring(1);
                    languages.add(lang);
                }
            });
        }
    }

    private void extractWeaponProficiencies(JsonNode node, ArrayNode weapons) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.fields().forEachRemaining(entry -> {
                if ("choose".equals(entry.getKey())) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    String weapon = entry.getKey();
                    if (weapon.contains("|")) weapon = weapon.substring(0, weapon.indexOf('|'));
                    weapons.add(weapon.substring(0, 1).toUpperCase() + weapon.substring(1));
                }
            });
        }
    }

    private void extractArmorProficiencies(JsonNode node, ArrayNode armor) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.fields().forEachRemaining(entry -> {
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    armor.add(entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1));
                }
            });
        }
    }

    private void extractToolProficiencies(JsonNode node, ArrayNode tools) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.fields().forEachRemaining(entry -> {
                if ("choose".equals(entry.getKey()) || "any".equals(entry.getKey())) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                    String tool = entry.getKey();
                    if (tool.contains("|")) tool = tool.substring(0, tool.indexOf('|'));
                    tools.add(tool.substring(0, 1).toUpperCase() + tool.substring(1));
                }
            });
        }
    }

    private String resolveResistances(JsonNode node, JsonNode parent) {
        try {
            List<String> resistances = new ArrayList<>();

            if (parent != null) {
                extractResistances(parent.get("resist"), resistances);
                extractConditionImmunities(parent.get("conditionImmune"), resistances);
            }
            extractResistances(node.get("resist"), resistances);
            extractConditionImmunities(node.get("conditionImmune"), resistances);

            if (resistances.isEmpty()) return "[]";
            return objectMapper.writeValueAsString(resistances);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void extractResistances(JsonNode resistNode, List<String> resistances) {
        if (resistNode == null || !resistNode.isArray()) return;
        for (JsonNode r : resistNode) {
            if (r.isTextual()) {
                resistances.add(r.asText());
            } else if (r.isObject() && r.has("choose")) {
                JsonNode from = r.path("choose").get("from");
                if (from != null && from.isArray()) {
                    List<String> options = new ArrayList<>();
                    from.forEach(f -> options.add(f.asText()));
                    resistances.add("choose: " + String.join(", ", options));
                }
            }
        }
    }

    private void extractConditionImmunities(JsonNode ciNode, List<String> resistances) {
        if (ciNode == null || !ciNode.isArray()) return;
        for (JsonNode c : ciNode) {
            if (c.isTextual()) {
                resistances.add("condition immunity: " + c.asText());
            }
        }
    }

    private String buildDescription(JsonNode node, JsonNode parent) {
        StringBuilder sb = new StringBuilder();
        if (parent != null) {
            flattenEntriesToText(parent.get("entries"), sb);
        }
        flattenEntriesToText(node.get("entries"), sb);
        return sb.length() == 0 ? null : sb.toString();
    }

    private void flattenEntriesToText(JsonNode entries, StringBuilder sb) {
        if (entries == null || !entries.isArray()) return;
        for (JsonNode entry : entries) {
            if (entry.isTextual()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asText()));
            } else if (entry.isObject() && entry.has("entries")) {
                String name = entry.path("name").asText("");
                if (!name.isEmpty() && sb.length() > 0) sb.append("\n");
                if (!name.isEmpty()) sb.append(name).append(": ");
                JsonNode subEntries = entry.get("entries");
                if (subEntries.isArray()) {
                    for (JsonNode e : subEntries) {
                        if (e.isTextual()) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' && sb.charAt(sb.length() - 1) != ':') {
                                sb.append("\n");
                            }
                            sb.append(FiveEToolsMarkupParser.parse(e.asText()));
                        }
                    }
                }
            }
        }
    }

    private String resolveRaceChoices(JsonNode node, JsonNode parent) {
        try {
            Set<String> overwritten = getOverwriteKeys(node);
            ObjectNode choices = objectMapper.createObjectNode();

            ArrayNode langChoices = objectMapper.createArrayNode();
            if (parent != null && !overwritten.contains("languageProficiencies"))
                extractLanguageChoices(parent.get("languageProficiencies"), langChoices);
            extractLanguageChoices(node.get("languageProficiencies"), langChoices);
            if (!langChoices.isEmpty()) choices.set("languages", langChoices);

            ArrayNode skillChoices = objectMapper.createArrayNode();
            if (parent != null && !overwritten.contains("skillProficiencies"))
                extractSkillChoices(parent.get("skillProficiencies"), skillChoices);
            extractSkillChoices(node.get("skillProficiencies"), skillChoices);
            if (!skillChoices.isEmpty()) choices.set("skills", skillChoices);

            ArrayNode toolChoices = objectMapper.createArrayNode();
            if (parent != null && !overwritten.contains("toolProficiencies"))
                extractToolChoices(parent.get("toolProficiencies"), toolChoices);
            extractToolChoices(node.get("toolProficiencies"), toolChoices);
            if (!toolChoices.isEmpty()) choices.set("tools", toolChoices);

            ArrayNode weaponChoices = objectMapper.createArrayNode();
            if (parent != null && !overwritten.contains("weaponProficiencies"))
                extractWeaponChoices(parent.get("weaponProficiencies"), weaponChoices);
            extractWeaponChoices(node.get("weaponProficiencies"), weaponChoices);
            if (!weaponChoices.isEmpty()) choices.set("weapons", weaponChoices);

            ArrayNode resistChoices = objectMapper.createArrayNode();
            if (parent != null && !overwritten.contains("resist"))
                extractResistanceChoices(parent.get("resist"), resistChoices);
            extractResistanceChoices(node.get("resist"), resistChoices);
            if (!resistChoices.isEmpty()) choices.set("resistances", resistChoices);

            ArrayNode spellAbility = objectMapper.createArrayNode();
            extractSpellAbilityChoices(node.get("additionalSpells"), spellAbility);
            if (spellAbility.isEmpty() && parent != null)
                extractSpellAbilityChoices(parent.get("additionalSpells"), spellAbility);
            if (!spellAbility.isEmpty()) choices.set("spellAbility", spellAbility);

            int featCount = extractFeatCount(node.get("feats"));
            if (featCount == 0 && parent != null) featCount = extractFeatCount(parent.get("feats"));
            if (featCount > 0) choices.put("feats", featCount);

            if (choices.isEmpty()) return null;
            return objectMapper.writeValueAsString(choices);
        } catch (Exception e) {
            return null;
        }
    }

    private void extractLanguageChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            if (!obj.isObject()) continue;
            boolean hasAnyStandard = false;
            if (obj.has("anyStandard")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyStandard", obj.get("anyStandard").asInt());
                choices.add(c);
                hasAnyStandard = true;
            }
            if (!hasAnyStandard && obj.has("other") && obj.get("other").asBoolean()) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyStandard", 1);
                choices.add(c);
            }
            if (obj.has("choose")) {
                JsonNode choose = obj.get("choose");
                ArrayNode from = objectMapper.createArrayNode();
                Set<String> explicit = new HashSet<>();
                boolean hasOther = false;
                if (choose.has("from")) {
                    for (JsonNode f : choose.get("from")) {
                        String lang = f.asText();
                        if ("other".equals(lang)) {
                            hasOther = true;
                        } else {
                            String formatted = titleCase(lang);
                            from.add(formatted);
                            explicit.add(formatted);
                        }
                    }
                }
                if (hasOther) {
                    for (String stdLang : STANDARD_LANGUAGES) {
                        if (!explicit.contains(stdLang)) from.add(stdLang);
                    }
                }
                ObjectNode c = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                inner.set("from", from);
                inner.put("count", choose.path("count").asInt(1));
                c.set("choose", inner);
                choices.add(c);
            }
        }
    }

    private void extractSkillChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            if (!obj.isObject()) continue;
            if (obj.has("any")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("any", obj.get("any").asInt());
                choices.add(c);
            }
            if (obj.has("choose")) {
                JsonNode choose = obj.get("choose");
                ObjectNode c = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                if (choose.has("from")) {
                    ArrayNode from = objectMapper.createArrayNode();
                    for (JsonNode f : choose.get("from")) {
                        String skill = f.asText();
                        if (skill.contains(" ")) {
                            from.add(Arrays.stream(skill.split(" "))
                                    .map(this::titleCase)
                                    .reduce((a, b) -> a + " " + b).orElse(skill));
                        } else {
                            from.add(titleCase(skill));
                        }
                    }
                    inner.set("from", from);
                }
                inner.put("count", choose.path("count").asInt(1));
                c.set("choose", inner);
                choices.add(c);
            }
        }
    }

    private void extractToolChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            if (!obj.isObject()) continue;
            if (obj.has("any")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("any", obj.get("any").asInt());
                choices.add(c);
            }
            if (obj.has("anyArtisansTool")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyArtisansTool", obj.get("anyArtisansTool").asInt());
                choices.add(c);
            }
            if (obj.has("anyMusicalInstrument")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyMusicalInstrument", obj.get("anyMusicalInstrument").asInt());
                choices.add(c);
            }
            if (obj.has("choose")) {
                JsonNode choose = obj.get("choose");
                ObjectNode c = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                if (choose.has("from")) {
                    ArrayNode from = objectMapper.createArrayNode();
                    for (JsonNode f : choose.get("from")) {
                        from.add(titleCase(f.asText()));
                    }
                    inner.set("from", from);
                }
                inner.put("count", choose.path("count").asInt(1));
                c.set("choose", inner);
                choices.add(c);
            }
        }
    }

    private void extractWeaponChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            if (!obj.isObject() || !obj.has("choose")) continue;
            JsonNode choose = obj.get("choose");
            ObjectNode c = objectMapper.createObjectNode();
            int count = choose.path("count").asInt(1);
            c.put("count", count);
            if (choose.has("from")) {
                ArrayNode from = objectMapper.createArrayNode();
                for (JsonNode f : choose.get("from")) from.add(titleCase(f.asText()));
                c.set("from", from);
            } else if (choose.has("fromFilter")) {
                String filter = choose.get("fromFilter").asText("");
                if (filter.contains("martial weapon")) {
                    ArrayNode from = objectMapper.createArrayNode();
                    MARTIAL_WEAPONS.forEach(from::add);
                    c.set("from", from);
                }
            }
            choices.add(c);
        }
    }

    private void extractResistanceChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode r : node) {
            if (r.isObject() && r.has("choose")) {
                JsonNode from = r.path("choose").get("from");
                if (from != null && from.isArray()) {
                    ObjectNode c = objectMapper.createObjectNode();
                    ArrayNode fromArr = objectMapper.createArrayNode();
                    from.forEach(f -> fromArr.add(titleCase(f.asText())));
                    c.set("from", fromArr);
                    choices.add(c);
                }
            }
        }
    }

    private void extractSpellAbilityChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray() || !choices.isEmpty()) return;
        for (JsonNode spellSet : node) {
            JsonNode ability = spellSet.get("ability");
            if (ability == null || !ability.isObject() || !ability.has("choose")) continue;
            JsonNode choose = ability.get("choose");
            if (choose.isArray()) {
                for (JsonNode c : choose) {
                    if (c.isTextual()) {
                        choices.add(ABILITY_MAP.getOrDefault(c.asText(), c.asText().toUpperCase()));
                    } else if (c.isObject() && c.has("from")) {
                        for (JsonNode f : c.get("from")) {
                            choices.add(ABILITY_MAP.getOrDefault(f.asText(), f.asText().toUpperCase()));
                        }
                    }
                }
            }
            if (!choices.isEmpty()) break;
        }
    }

    private int extractFeatCount(JsonNode node) {
        if (node == null || !node.isArray()) return 0;
        for (JsonNode obj : node) {
            if (obj.has("any")) return obj.get("any").asInt(1);
        }
        return 0;
    }

    private Set<String> getOverwriteKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        JsonNode overwrite = node.get("overwrite");
        if (overwrite != null && overwrite.isObject()) {
            overwrite.fieldNames().forEachRemaining(keys::add);
        }
        return keys;
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
