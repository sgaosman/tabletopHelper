package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * A feat's mechanical effects, the {@code feats.effects} column (D168, D169): the 5etools proficiency, resistance,
 * expertise and optional feature lists, and the bonuses feat-effects.json adds. Every feat has the object, empty when
 * it has no effects. The lists keep the 5etools entries, which are a string or an object such as {@code {"light":
 * true}} or {@code {"choose": {...}}}. The column's JSON is kept whole beside the records, and it is what is stored
 * and what the API returns.
 *
 * @param repeatable whether the feat can be taken more than once, as only Elemental Adept can (PHB p.166)
 * @param json       the column's JSON as stored
 * @param unreadable why the JSON doesn't fit the records, or null; the records are then empty, and
 *                   {@link #readable} stops a command that needs them with a 409 (D155)
 */
public record FeatEffects(List<JsonNode> resistances, List<JsonNode> expertise, List<JsonNode> armorProficiencies,
                          List<JsonNode> weaponProficiencies, List<JsonNode> toolProficiencies,
                          List<JsonNode> skillProficiencies, List<JsonNode> languageProficiencies,
                          List<JsonNode> savingThrowProficiencies, List<JsonNode> skillToolLanguageProficiencies,
                          List<JsonNode> optionalFeatureProgression, Integer speedBonus, Integer initiativeBonus,
                          Integer hpPerLevel, Integer passivePerceptionBonus, Integer passiveInvestigationBonus,
                          Resource resource, boolean repeatable, JsonNode json, String unreadable) {

    private static final Logger log = LoggerFactory.getLogger(FeatEffects.class);

    private static final ObjectMapper READER = JacksonDefaults.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final TypeReference<List<JsonNode>> NODES = new TypeReference<>() {
    };

    /** An empty object: a feat without effects. */
    public static final FeatEffects NONE = of(READER.createObjectNode());

    public FeatEffects {
        resistances = listOf(resistances);
        expertise = listOf(expertise);
        armorProficiencies = listOf(armorProficiencies);
        weaponProficiencies = listOf(weaponProficiencies);
        toolProficiencies = listOf(toolProficiencies);
        skillProficiencies = listOf(skillProficiencies);
        languageProficiencies = listOf(languageProficiencies);
        savingThrowProficiencies = listOf(savingThrowProficiencies);
        skillToolLanguageProficiencies = listOf(skillToolLanguageProficiencies);
        optionalFeatureProgression = listOf(optionalFeatureProgression);
    }

    /**
     * A feat's limited uses, such as Lucky's three luck points.
     *
     * @param resetOn "longRest" or "shortRest"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(String name, int maxUses, String resetOn) {
    }

    /** The effects of this JSON, which they keep as they are. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FeatEffects of(JsonNode json) {
        if (json == null || !json.isObject()) {
            return empty(json, json == null || json.isNull() ? null : "a feat's effects are an object");
        }
        try {
            JsonNode repeatable = json.get("repeatable");
            if (repeatable != null && !repeatable.isNull() && !repeatable.isBoolean()) {
                return empty(json, "repeatable is " + repeatable);
            }
            return new FeatEffects(list(json, "resistances"), list(json, "expertise"),
                    list(json, "armorProficiencies"), list(json, "weaponProficiencies"),
                    list(json, "toolProficiencies"), list(json, "skillProficiencies"),
                    list(json, "languageProficiencies"), list(json, "savingThrowProficiencies"),
                    list(json, "skillToolLanguageProficiencies"), list(json, "optionalFeatureProgression"),
                    whole(json, "speedBonus"), whole(json, "initiativeBonus"), whole(json, "hpPerLevel"),
                    whole(json, "passivePerceptionBonus"), whole(json, "passiveInvestigationBonus"),
                    READER.convertValue(json.get("resource"), Resource.class),
                    repeatable != null && repeatable.asBoolean(false), json, null);
        } catch (IllegalArgumentException | JacksonException e) {
            return empty(json, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /** The effects of the column's text, for data the caller knows to be JSON, such as a test's; null for null. */
    public static FeatEffects parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return of(READER.readTree(json));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    /**
     * A feat's effects, NONE for a feat without them; effects that don't fit the records are a 409 naming the feat
     * (D155).
     */
    public static FeatEffects readable(Feat feat) {
        FeatEffects effects = feat.getEffects();
        if (effects == null || effects.json() == null || effects.json().isNull()) return NONE;
        if (effects.unreadable() != null) {
            log.error("The effects of the feat {} don't fit the records: {}", feat.getName(), effects.unreadable());
            throw new ConflictException("The effects of the feat " + feat.getName() + " can't be read");
        }
        return effects;
    }

    private static FeatEffects empty(JsonNode json, String unreadable) {
        return new FeatEffects(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, false, json, unreadable);
    }

    // One of the lists; null when absent, and an error when present but not a list
    private static List<JsonNode> list(JsonNode json, String key) {
        JsonNode node = json.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) {
            throw new IllegalArgumentException(key + " is not a list: " + node);
        }
        return READER.convertValue(node, NODES);
    }

    // A whole-number bonus; null when absent
    private static Integer whole(JsonNode json, String key) {
        JsonNode node = json.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isInt()) {
            throw new IllegalArgumentException(key + " is not a whole number: " + node);
        }
        return node.intValue();
    }

    private static <T> List<T> listOf(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
