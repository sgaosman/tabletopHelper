package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatAbilityIncrease;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatPrerequisite;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.FeatSpells;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.FeatPrerequisites.AbilityMinimum;
import com.tabletophelper.rules.character.FeatPrerequisites.FeatRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.LevelRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.PrerequisiteAlternative;
import com.tabletophelper.rules.character.FeatPrerequisites.ProficiencyKind;
import com.tabletophelper.rules.character.FeatPrerequisites.ProficiencyRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.RaceRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class FeatLoader implements ContentLoader {

    private static final String FEATS_FILE = "data/5etools/feats.json";
    // The effects the 5etools data doesn't give, by feat name, added to each feat's effects
    private static final String EFFECTS_FILE = "data/content/feat-effects.json";

    private final FeatRepository featRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "feats";
    }

    @Override
    public List<String> files() {
        return List.of(FEATS_FILE, EFFECTS_FILE);
    }

    @Override
    public int version() {
        // 2: prerequisites as alternatives of typed requirements (D159)
        return 2;
    }

    @Override
    public void load(LoadContext context) {
        context.upsert("feats", featRepository, parseFeatsFile(context, readEffects(context)),
                feat -> LoadContext.nameAndSource(feat.getName(), feat.getSource()));
    }

    private JsonNode readEffects(LoadContext context) {
        JsonNode effects = context.read(EFFECTS_FILE);
        if (!effects.isObject()) {
            throw new IllegalStateException(EFFECTS_FILE + " is not an object of feat names");
        }
        return effects;
    }

    private List<Feat> parseFeatsFile(LoadContext context, JsonNode extraEffects) {
        JsonNode feats = LoadContext.array(context.read(FEATS_FILE), "feat", FEATS_FILE);
        // Each feat's name by its name in lower case, as another feat's prerequisite names it
        Map<String, String> featNames = new HashMap<>();
        for (JsonNode feat : feats) {
            String name = feat.path("name").asString(null);
            if (name != null) featNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        List<Feat> batch = new ArrayList<>();
        for (JsonNode feat : feats) {
            String name = feat.path("name").asString(null);
            if (name == null) {
                context.skip("feats with no name");
                continue;
            }
            batch.add(LoadContext.reading(FEATS_FILE + ": feat " + name + "|" + feat.path("source").asString(""),
                    () -> parseFeat(name, feat, extraEffects, featNames)));
        }
        return batch;
    }

    private Feat parseFeat(String name, JsonNode feat, JsonNode extraEffects, Map<String, String> featNames) {
        return Feat.builder()
                .name(name)
                .source(feat.path("source").asString(null))
                .prerequisite(extractPrerequisite(feat, featNames))
                .description(flattenEntries(feat.get("entries")))
                .abilityScoreIncrease(typed(extractAbilityScoreIncrease(feat), FeatAbilityIncrease::of,
                        FeatAbilityIncrease::unreadable, "ability"))
                .grantsFeatures(typed(extractGrantsFeatures(feat), FeatSpells::of, FeatSpells::unreadable,
                        "additionalSpells"))
                .effects(typed(extractEffects(feat, extraEffects.path(name)), FeatEffects::of,
                        FeatEffects::unreadable, "effects"))
                .build();
    }

    // The column's records for the JSON written, which a load never stores unless they fit (D155)
    private static <T> T typed(JsonNode json, Function<JsonNode, T> of, Function<T, String> unreadable, String what) {
        if (json == null) return null;
        T value = of.apply(json);
        String why = unreadable.apply(value);
        if (why != null) {
            throw new IllegalStateException(what + " doesn't fit the feat records: " + why);
        }
        return value;
    }

    /**
     * The prerequisite as alternatives (D159): each 5etools prerequisite object is one alternative, any one of which
     * suffices, and each key in it a requirement. A key, or a shape of one, that isn't read here fails the load
     * (D155), so no requirement is lost unnoticed.
     */
    FeatPrerequisite extractPrerequisite(JsonNode feat, Map<String, String> featNames) {
        JsonNode prereqs = feat.get("prerequisite");
        if (prereqs == null || prereqs.isNull()) return null;
        if (!prereqs.isArray()) {
            throw new IllegalStateException("prerequisite is not a list");
        }
        List<PrerequisiteAlternative> alternatives = new ArrayList<>();
        for (JsonNode prereq : prereqs) {
            if (!prereq.isObject()) {
                throw new IllegalStateException("a prerequisite is not an object: " + prereq);
            }
            alternatives.add(alternative(prereq, featNames));
        }
        if (alternatives.isEmpty()) return null;
        JsonNode json = pruned(objectMapper.valueToTree(alternatives));
        return typed(json, FeatPrerequisite::of, FeatPrerequisite::unreadable, "prerequisite");
    }

    private PrerequisiteAlternative alternative(JsonNode prereq, Map<String, String> featNames) {
        List<AbilityMinimum> abilities = new ArrayList<>();
        List<RaceRequirement> races = new ArrayList<>();
        boolean spellcasting = false;
        boolean spellcastingFeature = false;
        List<ProficiencyRequirement> proficiencies = new ArrayList<>();
        List<FeatRequirement> feats = new ArrayList<>();
        LevelRequirement level = null;
        List<String> backgrounds = new ArrayList<>();
        List<String> campaigns = new ArrayList<>();
        String other = null;
        for (Map.Entry<String, JsonNode> field : prereq.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            switch (key) {
                // Any one of the abilities at its minimum: Ritual Caster's [{"int": 13}, {"wis": 13}]
                case "ability" -> {
                    for (JsonNode minimum : list(value, key)) {
                        if (!minimum.isObject() || minimum.size() != 1) {
                            throw new IllegalStateException("an ability minimum names one ability: " + minimum);
                        }
                        Map.Entry<String, JsonNode> only = minimum.properties().iterator().next();
                        Ability ability = Ability.parse(only.getKey()).orElseThrow(() ->
                                new IllegalStateException("unknown ability " + only.getKey()));
                        if (!only.getValue().isInt()) {
                            throw new IllegalStateException("an ability minimum is a whole number: " + minimum);
                        }
                        abilities.add(new AbilityMinimum(ability, only.getValue().intValue()));
                    }
                }
                case "race" -> {
                    for (JsonNode race : list(value, key)) {
                        allowKeys(race, key, "name", "subrace", "displayEntry");
                        races.add(new RaceRequirement(text(race, "name"), race.path("subrace").asString(null),
                                markup(race.path("displayEntry").asString(null))));
                    }
                }
                // "The ability to cast at least one spell" (PHB)
                case "spellcasting" -> spellcasting = flag(value, key);
                // "Spellcasting or Pact Magic feature" (TCE), and "Spellcasting feature"
                case "spellcasting2020", "spellcastingFeature" -> spellcastingFeature = flag(value, key);
                case "proficiency" -> {
                    for (JsonNode proficiency : list(value, key)) {
                        if (!proficiency.isObject() || proficiency.size() != 1) {
                            throw new IllegalStateException("a proficiency names one kind: " + proficiency);
                        }
                        Map.Entry<String, JsonNode> only = proficiency.properties().iterator().next();
                        ProficiencyKind kind = switch (only.getKey()) {
                            case "armor" -> ProficiencyKind.ARMOR;
                            case "weapon" -> ProficiencyKind.WEAPON;
                            case "weaponGroup" -> ProficiencyKind.WEAPON_GROUP;
                            default -> throw new IllegalStateException("unknown proficiency " + only.getKey());
                        };
                        if (!only.getValue().isString()) {
                            throw new IllegalStateException("a proficiency is named: " + proficiency);
                        }
                        proficiencies.add(new ProficiencyRequirement(kind, only.getValue().asString()));
                    }
                }
                // "name|source|display", such as "strike of the giants|BGG|strike of the giants (fire strike)"
                case "feat" -> {
                    for (JsonNode f : list(value, key)) {
                        if (!f.isString()) {
                            throw new IllegalStateException("a feat is named: " + f);
                        }
                        String[] parts = f.asString().split("\\|", -1);
                        String featName = featNames.get(parts[0].trim().toLowerCase(Locale.ROOT));
                        if (featName == null) {
                            throw new IllegalStateException("the feat " + parts[0] + " isn't in " + FEATS_FILE);
                        }
                        String source = parts.length > 1 && !parts[1].isBlank()
                                ? parts[1].trim().toUpperCase(Locale.ROOT) : null;
                        String display = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null;
                        feats.add(new FeatRequirement(featName, source, display));
                    }
                }
                case "level" -> {
                    if (value.isInt()) {
                        level = new LevelRequirement(value.intValue(), null);
                    } else if (value.isObject()) {
                        allowKeys(value, key, "level", "class");
                        if (!value.path("level").isInt()) {
                            throw new IllegalStateException("a level is a whole number: " + value);
                        }
                        String className = null;
                        if (value.has("class")) {
                            allowKeys(value.get("class"), "class", "name", "source", "visible");
                            className = text(value.get("class"), "name");
                        }
                        level = new LevelRequirement(value.path("level").intValue(), className);
                    } else {
                        throw new IllegalStateException("a level is a number or an object: " + value);
                    }
                }
                case "background" -> {
                    for (JsonNode background : list(value, key)) {
                        allowKeys(background, key, "name", "displayEntry");
                        backgrounds.add(text(background, "name"));
                    }
                }
                case "campaign" -> {
                    for (JsonNode campaign : list(value, key)) {
                        if (!campaign.isString()) {
                            throw new IllegalStateException("a campaign is named: " + campaign);
                        }
                        campaigns.add(campaign.asString());
                    }
                }
                case "other" -> {
                    if (!value.isString()) {
                        throw new IllegalStateException("other is text: " + value);
                    }
                    other = markup(value.asString());
                }
                default -> throw new IllegalStateException("unknown prerequisite " + key + ": " + value);
            }
        }
        return new PrerequisiteAlternative(abilities, races, spellcasting, spellcastingFeature, proficiencies, feats,
                level, backgrounds, campaigns, other);
    }

    private static List<JsonNode> list(JsonNode value, String key) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalStateException(key + " is not a list: " + value);
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    private static void allowKeys(JsonNode node, String what, String... keys) {
        if (!node.isObject()) {
            throw new IllegalStateException(what + " is not an object: " + node);
        }
        List<String> allowed = List.of(keys);
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw new IllegalStateException("unknown key " + key + " in " + what + ": " + node);
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException(key + " is missing from " + node);
        }
        return value.asString().trim();
    }

    private static boolean flag(JsonNode value, String key) {
        if (!value.isBoolean() || !value.booleanValue()) {
            throw new IllegalStateException(key + " is not true: " + value);
        }
        return true;
    }

    private static String markup(String text) {
        return text == null ? null : FiveEToolsMarkupParser.parse(text);
    }

    // The JSON without what a requirement leaves out: nulls, false and empty lists
    private static JsonNode pruned(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                pruned(item);
            }
        } else if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> empty = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = pruned(field.getValue());
                if (value.isNull() || (value.isBoolean() && !value.booleanValue())
                        || (value.isArray() && value.isEmpty())) {
                    empty.add(field.getKey());
                }
            }
            object.remove(empty);
        }
        return node;
    }

    private JsonNode extractAbilityScoreIncrease(JsonNode feat) {
        JsonNode ability = feat.get("ability");
        if (ability == null || !ability.isArray() || ability.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode ab : ability) {
            if (!ab.isObject()) continue;
            ObjectNode entry = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = ab.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("choose".equals(key)) {
                    ObjectNode chooseObj = objectMapper.createObjectNode();
                    if (value.has("from")) {
                        ArrayNode from = objectMapper.createArrayNode();
                        for (JsonNode f : value.get("from")) {
                            from.add(f.asString("").toUpperCase());
                        }
                        chooseObj.set("from", from);
                    }
                    chooseObj.put("amount", value.path("amount").asInt(1));
                    entry.set("choose", chooseObj);
                } else {
                    entry.put(key.toUpperCase(), value.asInt(0));
                }
            }
            result.add(entry);
        }
        return result.isEmpty() ? null : result;
    }

    private JsonNode extractGrantsFeatures(JsonNode feat) {
        JsonNode spells = feat.get("additionalSpells");
        return spells == null ? null : spells.deepCopy();
    }

    private JsonNode extractEffects(JsonNode feat, JsonNode extra) {
        ObjectNode effects = objectMapper.createObjectNode();

        if (feat.has("resist")) {
            effects.set("resistances", feat.get("resist"));
        }
        if (feat.has("expertise")) {
            effects.set("expertise", feat.get("expertise"));
        }
        if (feat.has("armorProficiencies")) {
            effects.set("armorProficiencies", feat.get("armorProficiencies"));
        }
        if (feat.has("weaponProficiencies")) {
            effects.set("weaponProficiencies", feat.get("weaponProficiencies"));
        }
        if (feat.has("toolProficiencies")) {
            effects.set("toolProficiencies", feat.get("toolProficiencies"));
        }
        if (feat.has("skillProficiencies")) {
            effects.set("skillProficiencies", feat.get("skillProficiencies"));
        }
        if (feat.has("languageProficiencies")) {
            effects.set("languageProficiencies", feat.get("languageProficiencies"));
        }
        if (feat.has("savingThrowProficiencies")) {
            effects.set("savingThrowProficiencies", feat.get("savingThrowProficiencies"));
        }
        if (feat.has("skillToolLanguageProficiencies")) {
            effects.set("skillToolLanguageProficiencies", feat.get("skillToolLanguageProficiencies"));
        }
        if (feat.has("optionalfeatureProgression")) {
            effects.set("optionalFeatureProgression", feat.get("optionalfeatureProgression"));
        }

        if (extra.isObject()) {
            effects.setAll((ObjectNode) extra.deepCopy());
        }

        // Always an object, never null (D169)
        return effects;
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
}
