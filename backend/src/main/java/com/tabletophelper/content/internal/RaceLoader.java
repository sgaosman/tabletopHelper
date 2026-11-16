package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class RaceLoader implements ContentLoader {

    private static final String FILE = "data/5etools/races.json";
    // The effects the 5etools data doesn't give, such as the Hill Dwarf's hit points, by natural key (V28)
    static final String EFFECTS_FILE = "data/content/race-effects.json";
    static final Set<String> EFFECTS = Set.of("hpPerLevel", "unarmouredAcBase", "unarmouredAcDex", "unarmouredAcCon",
            "unarmouredAcWithArmour", "acBonus", "heavyArmourSpeedExempt", "noBodyArmour");

    private final RaceRepository raceRepository;
    private final SpellRepository spellRepository;
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

    // A whole number, as a level or use count in the additional spells' keys is written
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?\\d{1,9}");

    private Map<String, Integer> spellLevelLookup = Map.of();

    private void buildSpellLevelLookup() {
        Map<String, Integer> map = new HashMap<>();
        for (Spell spell : spellRepository.findAll()) {
            map.put(spell.getName().toLowerCase(), spell.getLevel());
        }
        spellLevelLookup = map;
        log.info("Built spell level lookup with {} entries from database", map.size());
    }

    // Gives a fixed innate spell recorded at level 0 the level of the spell of its name, unless it is at will
    private void fixRaceSpellLevels(Race race) throws Exception {
        if (race.getAdditionalSpells() == null || spellLevelLookup.isEmpty()) return;
        JsonNode root = objectMapper.readTree(race.getAdditionalSpells());
        boolean changed = false;
        JsonNode fixedSpells = root.get("fixedSpells");
        if (fixedSpells != null && fixedSpells.isArray()) {
            for (JsonNode spell : fixedSpells) {
                if (spell.path("level").asInt(0) == 0 && !spell.path("atWill").asBoolean(false)) {
                    String name = spell.path("name").asString("").toLowerCase();
                    Integer correctLevel = spellLevelLookup.get(name);
                    if (correctLevel != null && correctLevel > 0) {
                        ((ObjectNode) spell).put("level", correctLevel);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            race.setAdditionalSpells(objectMapper.writeValueAsString(root));
        }
    }

    @Override
    public String name() {
        return "races";
    }

    @Override
    public List<String> files() {
        return List.of(FILE, EFFECTS_FILE);
    }

    // Version 2 writes the effects overlay; version 3 (D231) casts a race's levelled spells once between rests unless
    // the data says at will, keeps the spells it names under a short-or-long rest with that reset, and names a known
    // spell's level
    @Override
    public int version() {
        return 3;
    }

    // A fixed spell's uses that come back on a short or long rest, as KnownSpell.usesResetOn writes it (D231)
    static final String SHORT_REST = "shortRest";

    // The levelled spells a race casts at will that its data lists without saying so, by the race's name and source:
    // the data doesn't tell them from its once-a-day spells. The DMG's deep gnome casts nondetection (self only) at
    // will, and blindness/deafness, blur and disguise self once a day each (DMG p.282); a yuan-ti casts animal
    // friendship on snakes "an unlimited number of times" (VGM p.120, MPMM p.29)
    static final Map<String, Set<String>> KNOWN_AT_WILL = Map.of(
            "Gnome (Deep)|DMG", Set.of("nondetection"),
            "Yuan-ti Pureblood|VGM", Set.of("animal friendship"),
            "Yuan-Ti|MPMM", Set.of("animal friendship"));

    // The innate spells' levels come from the spells table
    @Override
    public List<String> dependsOn() {
        return List.of("spells");
    }

    @Override
    public void load(LoadContext context) throws Exception {
        buildSpellLevelLookup();

        JsonNode root = context.read(FILE);
        JsonNode racesArray = LoadContext.array(root, "race", FILE);
        JsonNode subracesArray = root.get("subrace");

        Map<String, JsonNode> raceIndex = new HashMap<>();
        for (JsonNode r : racesArray) {
            String key = r.path("name").asString("") + "|" + r.path("source").asString("");
            raceIndex.put(key, r);
        }

        List<Race> batch = new ArrayList<>();

        for (JsonNode r : racesArray) {
            batch.add(LoadContext.reading(FILE + ": race " + r.path("name").asString("?") + "|"
                    + r.path("source").asString(""), () -> parseRace(r, null, null)));
        }

        if (subracesArray != null && subracesArray.isArray()) {
            for (JsonNode sr : subracesArray) {
                if (sr.has("_copy")) {
                    context.skip("_copy subraces");
                    continue;
                }
                if (!sr.has("raceName")) {
                    context.skip("subraces with no raceName");
                    continue;
                }

                String subName = sr.path("name").asString("");
                if ("?".equals(subName)) {
                    context.skip("subraces named ?");
                    continue;
                }

                String parentKey = sr.path("raceName").asString("") + "|" + sr.path("raceSource").asString("");
                JsonNode parent = raceIndex.get(parentKey);
                if (parent == null) {
                    log.warn("Subrace '{}' references unknown parent '{}'", subName, parentKey);
                    context.skip("subraces of a race not in the data");
                    continue;
                }
                String what = FILE + ": subrace " + subName + "|" + sr.path("source").asString("") + " of " + parentKey;

                if (subName.isEmpty()) {
                    // A subrace with no name adds its ability bonuses to its race
                    String mergedBonuses = LoadContext.reading(what, () -> resolveAbilityBonuses(sr, parent));
                    if (!"[]".equals(mergedBonuses)) {
                        String parentName = sr.path("raceName").asString("");
                        String parentSource = parent.path("source").asString("");
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

                batch.add(LoadContext.reading(what, () -> parseRace(sr, parent, sr.path("raceName").asString(""))));
            }
        }

        for (Race race : batch) {
            LoadContext.reading(FILE + ": the innate spells of " + race.getName() + "|" + race.getSource(), () -> {
                fixRaceSpellLevels(race);
                return null;
            });
        }
        EffectsOverlay effects = EffectsOverlay.read(context, objectMapper, EFFECTS_FILE, EFFECTS);
        for (Race race : batch) {
            race.setEffects(effects.effectsOf(LoadContext.nameAndSource(race.getName(), race.getSource())));
        }
        effects.requireEveryRowFound();
        context.upsert("races", raceRepository, batch, r -> LoadContext.nameAndSource(r.getName(), r.getSource()));
    }

    private Race parseRace(JsonNode node, JsonNode parent, String parentRaceName) throws Exception {
        boolean isSubrace = parent != null;
        String name;
        if (isSubrace) {
            name = node.path("name").asString("") + " " + parentRaceName;
        } else {
            name = node.path("name").asString("");
        }
        String source = node.path("source").asString("");

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
                .additionalSpells(extractAdditionalSpells(node, parent))
                .baseRaceName(parentRaceName)
                .description(description)
                .build();
    }

    private String resolveSize(JsonNode node, JsonNode parent) {
        JsonNode sizeNode = node.get("size");
        if (sizeNode == null && parent != null) sizeNode = parent.get("size");
        if (sizeNode == null || !sizeNode.isArray()) return "Medium";

        List<String> sizes = new ArrayList<>();
        for (JsonNode s : sizeNode) {
            sizes.add(SIZE_MAP.getOrDefault(s.asString(""), s.asString("")));
        }
        return String.join("/", sizes);
    }

    private String resolveSpeed(JsonNode node, JsonNode parent) throws Exception {
        JsonNode speedNode = node.get("speed");
        if (speedNode == null && parent != null) speedNode = parent.get("speed");
        if (speedNode == null) return "{\"walk\":30}";

        if (speedNode.isInt() || speedNode.isNumber()) {
            ObjectNode obj = objectMapper.createObjectNode();
            obj.put("walk", speedNode.asInt(0));
            return objectMapper.writeValueAsString(obj);
        }
        if (speedNode.isObject()) {
            ObjectNode clean = objectMapper.createObjectNode();
            speedNode.properties().forEach(entry -> {
                JsonNode v = entry.getValue();
                if (v.isInt() || v.isNumber()) {
                    clean.put(entry.getKey(), v.asInt(0));
                } else if (v.isBoolean() && v.asBoolean(false)) {
                    clean.put(entry.getKey(), true);
                } else if (v.isObject() && v.has("number")) {
                    clean.put(entry.getKey(), v.get("number").asInt(0));
                }
            });
            return objectMapper.writeValueAsString(clean);
        }
        return "{\"walk\":30}";
    }

    private String resolveAbilityBonuses(JsonNode node, JsonNode parent) throws Exception {
        Set<String> overwritten = getOverwriteKeys(node);
        List<Map<String, Object>> bonuses = new ArrayList<>();

        if (parent != null && !overwritten.contains("ability")) {
            addAbilityBonuses(parent.get("ability"), bonuses);
        }
        addAbilityBonuses(node.get("ability"), bonuses);

        String lineage = node.path("lineage").asString(null);
        if (lineage == null && parent != null) lineage = parent.path("lineage").asString(null);
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
    }

    private void addAbilityBonuses(JsonNode abilityArray, List<Map<String, Object>> bonuses) {
        if (abilityArray == null || !abilityArray.isArray()) return;
        for (JsonNode obj : abilityArray) {
            obj.properties().forEach(entry -> {
                String key = entry.getKey();
                if ("choose".equals(key)) {
                    JsonNode choose = entry.getValue();
                    Map<String, Object> bonus = new LinkedHashMap<>();
                    bonus.put("ability", "CHOOSE");
                    if (choose.has("from")) {
                        List<String> from = new ArrayList<>();
                        choose.get("from").forEach(n -> from.add(ABILITY_MAP.getOrDefault(n.asString(""), n.asString("").toUpperCase())));
                        bonus.put("from", from);
                    }
                    int count = choose.has("count") ? choose.get("count").asInt(0) : 1;
                    int amount = choose.has("amount") ? choose.get("amount").asInt(0) : 1;
                    bonus.put("count", count);
                    bonus.put("bonus", amount);
                    bonuses.add(bonus);
                } else {
                    String abilityName = ABILITY_MAP.getOrDefault(key, key.toUpperCase());
                    Map<String, Object> bonus = new LinkedHashMap<>();
                    bonus.put("ability", abilityName);
                    bonus.put("bonus", entry.getValue().asInt(0));
                    bonuses.add(bonus);
                }
            });
        }
    }

    private String resolveCreatureType(JsonNode node, JsonNode parent) {
        JsonNode ct = node.get("creatureTypes");
        if (ct == null && parent != null) ct = parent.get("creatureTypes");
        if (ct != null && ct.isArray() && ct.size() > 0) {
            String type = ct.get(0).asString("");
            return type.substring(0, 1).toUpperCase() + type.substring(1);
        }
        return "Humanoid";
    }

    private Integer resolveDarkvision(JsonNode node, JsonNode parent) {
        JsonNode dv = node.get("darkvision");
        if (dv == null && parent != null) dv = parent.get("darkvision");
        if (dv != null && dv.isInt()) return dv.asInt(0);
        return null;
    }

    private String resolveTraits(JsonNode node, JsonNode parent) throws Exception {
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
                        overwriteNames.add(data.get("overwrite").asString(""));
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
    }

    private void extractTraits(JsonNode entries, List<Map<String, String>> traits) {
        if (entries == null || !entries.isArray()) return;
        for (JsonNode entry : entries) {
            if (!entry.isObject()) continue;
            String name = entry.path("name").asString(null);
            if (name == null || name.isEmpty()) continue;

            StringBuilder desc = new StringBuilder();
            JsonNode subEntries = entry.get("entries");
            if (subEntries != null && subEntries.isArray()) {
                for (JsonNode e : subEntries) {
                    if (e.isString()) {
                        if (desc.length() > 0) desc.append("\n");
                        desc.append(FiveEToolsMarkupParser.parse(e.asString("")));
                    } else if (e.isObject() && "list".equals(e.path("type").asString(""))) {
                        JsonNode items = e.get("items");
                        if (items != null) {
                            for (JsonNode item : items) {
                                if (item.isString()) {
                                    if (desc.length() > 0) desc.append("\n");
                                    desc.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
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

    private String resolveProficiencies(JsonNode node, JsonNode parent) throws Exception {
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
    }

    private void extractSkillProficiencies(JsonNode node, ArrayNode skills) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.properties().forEach(entry -> {
                String key = entry.getKey();
                if (!"choose".equals(key) && !"any".equals(key) && entry.getValue().isBoolean() && entry.getValue().asBoolean(false)) {
                    String skillName = key.substring(0, 1).toUpperCase() + key.substring(1);
                    skills.add(skillName);
                }
            });
        }
    }

    private void extractLanguageProficiencies(JsonNode node, ArrayNode languages) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.properties().forEach(entry -> {
                String key = entry.getKey();
                if ("anyStandard".equals(key) || "other".equals(key)) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean(false)) {
                    String lang = key.substring(0, 1).toUpperCase() + key.substring(1);
                    languages.add(lang);
                }
            });
        }
    }

    private void extractWeaponProficiencies(JsonNode node, ArrayNode weapons) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.properties().forEach(entry -> {
                if ("choose".equals(entry.getKey())) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean(false)) {
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
            obj.properties().forEach(entry -> {
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean(false)) {
                    armor.add(entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1));
                }
            });
        }
    }

    private void extractToolProficiencies(JsonNode node, ArrayNode tools) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            obj.properties().forEach(entry -> {
                if ("choose".equals(entry.getKey()) || "any".equals(entry.getKey())) return;
                if (entry.getValue().isBoolean() && entry.getValue().asBoolean(false)) {
                    String tool = entry.getKey();
                    if (tool.contains("|")) tool = tool.substring(0, tool.indexOf('|'));
                    tools.add(tool.substring(0, 1).toUpperCase() + tool.substring(1));
                }
            });
        }
    }

    private String resolveResistances(JsonNode node, JsonNode parent) throws Exception {
        List<String> resistances = new ArrayList<>();

        if (parent != null) {
            extractResistances(parent.get("resist"), resistances);
            extractConditionImmunities(parent.get("conditionImmune"), resistances);
        }
        extractResistances(node.get("resist"), resistances);
        extractConditionImmunities(node.get("conditionImmune"), resistances);

        if (resistances.isEmpty()) return "[]";
        return objectMapper.writeValueAsString(resistances);
    }

    private void extractResistances(JsonNode resistNode, List<String> resistances) {
        if (resistNode == null || !resistNode.isArray()) return;
        for (JsonNode r : resistNode) {
            if (r.isString()) {
                resistances.add(r.asString(""));
            } else if (r.isObject() && r.has("choose")) {
                JsonNode from = r.path("choose").get("from");
                if (from != null && from.isArray()) {
                    List<String> options = new ArrayList<>();
                    from.forEach(f -> options.add(f.asString("")));
                    resistances.add("choose: " + String.join(", ", options));
                }
            }
        }
    }

    private void extractConditionImmunities(JsonNode ciNode, List<String> resistances) {
        if (ciNode == null || !ciNode.isArray()) return;
        for (JsonNode c : ciNode) {
            if (c.isString()) {
                resistances.add("condition immunity: " + c.asString(""));
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
            if (entry.isString()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject() && entry.has("entries")) {
                String name = entry.path("name").asString("");
                if (!name.isEmpty() && sb.length() > 0) sb.append("\n");
                if (!name.isEmpty()) sb.append(name).append(": ");
                JsonNode subEntries = entry.get("entries");
                if (subEntries.isArray()) {
                    for (JsonNode e : subEntries) {
                        if (e.isString()) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' && sb.charAt(sb.length() - 1) != ':') {
                                sb.append("\n");
                            }
                            sb.append(FiveEToolsMarkupParser.parse(e.asString("")));
                        }
                    }
                }
            }
        }
    }

    private String resolveRaceChoices(JsonNode node, JsonNode parent) throws Exception {
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
    }

    private void extractLanguageChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray()) return;
        for (JsonNode obj : node) {
            if (!obj.isObject()) continue;
            boolean hasAnyStandard = false;
            if (obj.has("anyStandard")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyStandard", obj.get("anyStandard").asInt(0));
                choices.add(c);
                hasAnyStandard = true;
            }
            if (!hasAnyStandard && obj.has("other") && obj.get("other").asBoolean(false)) {
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
                        String lang = f.asString("");
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
                c.put("any", obj.get("any").asInt(0));
                choices.add(c);
            }
            if (obj.has("choose")) {
                JsonNode choose = obj.get("choose");
                ObjectNode c = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                if (choose.has("from")) {
                    ArrayNode from = objectMapper.createArrayNode();
                    for (JsonNode f : choose.get("from")) {
                        String skill = f.asString("");
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
                c.put("any", obj.get("any").asInt(0));
                choices.add(c);
            }
            if (obj.has("anyArtisansTool")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyArtisansTool", obj.get("anyArtisansTool").asInt(0));
                choices.add(c);
            }
            if (obj.has("anyMusicalInstrument")) {
                ObjectNode c = objectMapper.createObjectNode();
                c.put("anyMusicalInstrument", obj.get("anyMusicalInstrument").asInt(0));
                choices.add(c);
            }
            if (obj.has("choose")) {
                JsonNode choose = obj.get("choose");
                ObjectNode c = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                if (choose.has("from")) {
                    ArrayNode from = objectMapper.createArrayNode();
                    for (JsonNode f : choose.get("from")) {
                        from.add(titleCase(f.asString("")));
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
                for (JsonNode f : choose.get("from")) from.add(titleCase(f.asString("")));
                c.set("from", from);
            } else if (choose.has("fromFilter")) {
                String filter = choose.get("fromFilter").asString("");
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
                    from.forEach(f -> fromArr.add(titleCase(f.asString(""))));
                    c.set("from", fromArr);
                    choices.add(c);
                }
            }
        }
    }

    private String extractAdditionalSpells(JsonNode node, JsonNode parent) throws Exception {
        JsonNode spellsNode = node.get("additionalSpells");
        if (spellsNode == null && parent != null) spellsNode = parent.get("additionalSpells");
        if (spellsNode == null || !spellsNode.isArray() || spellsNode.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();

        // Extract ability
        String ability = null;
        ArrayNode abilityChoicesArr = null;
        for (JsonNode entry : spellsNode) {
            JsonNode abilityNode = entry.get("ability");
            if (abilityNode == null) continue;
            if (abilityNode.isString()) {
                ability = ABILITY_MAP.getOrDefault(abilityNode.asString(""), abilityNode.asString("").toUpperCase());
            } else if (abilityNode.isObject() && abilityNode.has("choose")) {
                abilityChoicesArr = objectMapper.createArrayNode();
                JsonNode choose = abilityNode.get("choose");
                if (choose.isArray()) {
                    for (JsonNode c : choose) {
                        if (c.isString()) {
                            abilityChoicesArr.add(ABILITY_MAP.getOrDefault(c.asString(""), c.asString("").toUpperCase()));
                        } else if (c.isObject() && c.has("from")) {
                            for (JsonNode f : c.get("from"))
                                abilityChoicesArr.add(ABILITY_MAP.getOrDefault(f.asString(""), f.asString("").toUpperCase()));
                        }
                    }
                }
            }
            break;
        }
        if (ability != null) result.put("ability", ability);
        else result.putNull("ability");
        if (abilityChoicesArr != null && !abilityChoicesArr.isEmpty()) result.set("abilityChoices", abilityChoicesArr);
        else result.putNull("abilityChoices");

        ArrayNode fixedSpells = objectMapper.createArrayNode();
        ArrayNode spellChoices = objectMapper.createArrayNode();
        ObjectNode expandedList = null;

        boolean multipleOptions = spellsNode.size() > 1 && !spellsNode.get(0).has("expanded");
        Set<String> atWill = KNOWN_AT_WILL.getOrDefault(node.path("name").asString("") + "|"
                + node.path("source").asString(""), Set.of());

        if (multipleOptions) {
            ArrayNode options = objectMapper.createArrayNode();
            for (JsonNode entry : spellsNode) {
                ArrayNode optionSpells = objectMapper.createArrayNode();
                collectFixedSpells(entry, optionSpells, atWill);
                collectSpellChoices(entry, optionSpells);
                options.add(optionSpells);
            }
            result.set("options", options);
        } else {
            for (JsonNode entry : spellsNode) {
                collectFixedSpells(entry, fixedSpells, atWill);
                collectSpellChoices(entry, spellChoices);
                ObjectNode expanded = collectExpandedList(entry);
                if (expanded != null) expandedList = expanded;
            }
        }

        result.set("fixedSpells", fixedSpells);
        result.set("spellChoices", spellChoices);
        if (expandedList != null) result.set("expandedList", expandedList);
        else result.putNull("expandedList");

        if (fixedSpells.isEmpty() && spellChoices.isEmpty() && expandedList == null
                && !result.has("options") && ability == null && abilityChoicesArr == null) {
            return null;
        }

        return objectMapper.writeValueAsString(result);
    }

    /**
     * The spells a race block names, each with how it is cast (D231). A cantrip ({@code #c}, or a spell the spells
     * table has at level 0) is at will. A levelled spell is at will only where the data says so ({@code will}), or for
     * a spell {@link #KNOWN_AT_WILL} names; otherwise it is cast once without a slot between rests, and with any slot the
     * character has: a {@code known} or {@code innate} list, as a Hexblood's hex and disguise self (VRGR p.18) or a
     * Mark of Passage's misty step (ERLW p.46), once a long rest; {@code daily}, so many times a long rest; and
     * {@code rest}, so many times between short or long rests, as a VGM firbolg's detect magic and disguise self (VGM
     * p.107), a DMG eladrin's misty step (DMG p.286), a Mark of Handling's animal friendship and speak with animals
     * (ERLW p.42) and a Mark of Scribing's comprehend languages (ERLW p.47). Before, a known levelled spell was loaded at
     * will, and a known spell under {@code rest} was dropped.
     */
    private void collectFixedSpells(JsonNode entry, ArrayNode fixedSpells, Set<String> atWill) {
        for (String block : List.of("known", "innate")) {
            JsonNode byLevel = entry.get(block);
            if (byLevel == null || !byLevel.isObject()) continue;
            var fieldNames = byLevel.propertyNames().iterator();
            while (fieldNames.hasNext()) {
                String levelKey = fieldNames.next();
                int unlocksAt = "_".equals(levelKey) ? 1 : parseIntSafe(levelKey, 1);
                collectCasts(byLevel.get(levelKey), fixedSpells, unlocksAt, atWill);
            }
        }
    }

    // The spells of one level's entry: a list (once a long rest), or an object of daily, rest and will lists, or of
    // lists under other keys, as "_"
    private void collectCasts(JsonNode spellsAtLevel, ArrayNode fixedSpells, int unlocksAt, Set<String> atWill) {
        if (spellsAtLevel.isArray()) {
            addEach(fixedSpells, spellsAtLevel, unlocksAt, atWill, 1, null);
            return;
        }
        if (!spellsAtLevel.isObject()) return;
        var keys = spellsAtLevel.propertyNames().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode value = spellsAtLevel.get(key);
            switch (key) {
                case "will" -> {
                    if (value.isArray()) {
                        for (JsonNode s : value) {
                            if (s.isString()) addFixedSpell(fixedSpells, s.asString(""), unlocksAt, true, 0, null);
                        }
                    }
                }
                case "daily", "rest" -> {
                    if (!value.isObject()) continue;
                    var usesKeys = value.propertyNames().iterator();
                    while (usesKeys.hasNext()) {
                        String usesKey = usesKeys.next();
                        // "1e" is once each
                        int uses = parseIntSafe(usesKey.replace("e", ""), 1);
                        addEach(fixedSpells, value.get(usesKey), unlocksAt, atWill, uses,
                                "rest".equals(key) ? SHORT_REST : null);
                    }
                }
                default -> addEach(fixedSpells, value, unlocksAt, atWill, 1, null);
            }
        }
    }

    private void addEach(ArrayNode fixedSpells, JsonNode spells, int unlocksAt, Set<String> atWill, int uses,
                         String resetOn) {
        if (spells == null || !spells.isArray()) return;
        for (JsonNode s : spells) {
            if (!s.isString()) continue;
            boolean will = atWill.contains(baseName(s.asString("")));
            addFixedSpell(fixedSpells, s.asString(""), unlocksAt, will, will ? 0 : uses, will ? null : resetOn);
        }
    }

    // A spell's name in lower case, without its #c or #N mark or its |source suffix
    private static String baseName(String raw) {
        String name = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
        if (name.contains("|")) name = name.substring(0, name.indexOf('|'));
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private void addFixedSpell(ArrayNode arr, String rawName, int unlocksAt, boolean atWill, int usesPerLongRest,
                               String resetOn) {
        String name = rawName;
        int castLevel = 0;
        int spellLevel = 0;

        // Strip #c (cantrip) and #N (cast at level N) suffixes
        if (name.contains("#")) {
            String[] parts = name.split("#", 2);
            name = parts[0];
            String suffix = parts[1];
            if ("c".equals(suffix)) {
                spellLevel = 0;
                atWill = true;
                usesPerLongRest = 0;
                resetOn = null;
            } else {
                castLevel = parseIntSafe(suffix, 0);
            }
        }

        // Strip |SOURCE suffix
        if (name.contains("|")) name = name.substring(0, name.indexOf('|'));

        // Title-case
        name = titleCaseSpell(name);

        ObjectNode spell = objectMapper.createObjectNode();
        spell.put("name", name);
        int resolvedLevel = castLevel > 0 ? castLevel : spellLevel;
        // A levelled spell cast at will keeps its level too, as the DMG deep gnome's nondetection (D231)
        boolean markedCantrip = rawName.contains("#c");
        if (resolvedLevel == 0 && !markedCantrip) {
            Integer lookup = spellLevelLookup.get(name.toLowerCase());
            if (lookup != null && lookup > 0) {
                resolvedLevel = lookup;
            } else if (lookup != null && !atWill) {
                // A cantrip the data doesn't mark: cast at will
                atWill = true;
                usesPerLongRest = 0;
                resetOn = null;
            }
        }
        spell.put("level", resolvedLevel);
        spell.put("atWill", atWill);
        spell.put("unlocksAtLevel", unlocksAt);
        if (castLevel > 0) spell.put("castLevel", castLevel);
        if (usesPerLongRest > 0) spell.put("usesPerLongRest", usesPerLongRest);
        if (usesPerLongRest > 0 && resetOn != null) spell.put("usesResetOn", resetOn);

        arr.add(spell);
    }

    private void collectSpellChoices(JsonNode entry, ArrayNode spellChoices) {
        // Handle "choose" entries in known block
        JsonNode known = entry.get("known");
        if (known != null) {
            collectChooseFromNode(known, spellChoices);
        }
        // Handle "choose" entries in innate block (rare)
        JsonNode innate = entry.get("innate");
        if (innate != null) {
            collectChooseFromNode(innate, spellChoices);
        }
    }

    private void collectChooseFromNode(JsonNode block, ArrayNode spellChoices) {
        var keys = block.propertyNames().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode value = block.get(key);
            if (value.isObject()) {
                var innerKeys = value.propertyNames().iterator();
                while (innerKeys.hasNext()) {
                    String ik = innerKeys.next();
                    JsonNode inner = value.get(ik);
                    if (inner.isArray()) {
                        for (JsonNode item : inner) {
                            parseChooseItem(item, spellChoices);
                        }
                    }
                }
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    parseChooseItem(item, spellChoices);
                }
            }
        }
    }

    private void parseChooseItem(JsonNode item, ArrayNode spellChoices) {
        if (!item.isObject() || !item.has("choose")) return;
        String filter = item.get("choose").asString("");
        int count = item.path("count").asInt(1);

        // Parse filter string like "level=0|class=Wizard"
        ObjectNode choice = objectMapper.createObjectNode();
        for (String part : filter.split("\\|")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                if ("level".equals(kv[0])) choice.put("level", parseIntSafe(kv[1], 0));
                else if ("class".equals(kv[0])) choice.put("fromClass", titleCaseSpell(kv[1]));
                else if ("school".equals(kv[0])) choice.put("fromSchools", kv[1]);
            }
        }
        choice.put("count", count);
        spellChoices.add(choice);
    }

    private ObjectNode collectExpandedList(JsonNode entry) {
        JsonNode expanded = entry.get("expanded");
        if (expanded == null || !expanded.isObject()) return null;

        ObjectNode result = objectMapper.createObjectNode();
        var keys = expanded.propertyNames().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode spells = expanded.get(key);
            if (spells.isArray()) {
                ArrayNode cleaned = objectMapper.createArrayNode();
                for (JsonNode s : spells) {
                    if (s.isString()) {
                        String name = s.asString("");
                        if (name.contains("|")) name = name.split("\\|")[0];
                        if (name.contains("#")) name = name.split("#")[0];
                        cleaned.add(titleCaseSpell(name));
                    }
                }
                result.set(key, cleaned);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private String titleCaseSpell(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            String w = words[i];
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // The number a key such as "3" gives, or the fallback for a key that isn't one, such as "will" or "daily"
    private int parseIntSafe(String s, int fallback) {
        return WHOLE_NUMBER.matcher(s).matches() ? Integer.parseInt(s) : fallback;
    }

    private void extractSpellAbilityChoices(JsonNode node, ArrayNode choices) {
        if (node == null || !node.isArray() || !choices.isEmpty()) return;
        for (JsonNode spellSet : node) {
            JsonNode ability = spellSet.get("ability");
            if (ability == null || !ability.isObject() || !ability.has("choose")) continue;
            JsonNode choose = ability.get("choose");
            if (choose.isArray()) {
                for (JsonNode c : choose) {
                    if (c.isString()) {
                        choices.add(ABILITY_MAP.getOrDefault(c.asString(""), c.asString("").toUpperCase()));
                    } else if (c.isObject() && c.has("from")) {
                        for (JsonNode f : c.get("from")) {
                            choices.add(ABILITY_MAP.getOrDefault(f.asString(""), f.asString("").toUpperCase()));
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
            overwrite.propertyNames().iterator().forEachRemaining(keys::add);
        }
        return keys;
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
