package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ability score increases a feat gives, the {@code feats.ability_score_increase} column (D168): a list of entries,
 * each fixed increases by ability, such as {@code {"CON": 1}}, or a choice, such as {@code {"choose": {"from": ["STR",
 * "DEX"], "amount": 1}}}. The column's JSON is kept whole beside the records, and it is what is stored and what the
 * API returns.
 *
 * @param entries    the entries, in order
 * @param json       the column's JSON as stored
 * @param unreadable why the JSON doesn't fit the records, or null; the records are then empty, and
 *                   {@link #readable} stops a command that needs them with a 409 (D155)
 */
public record FeatAbilityIncrease(List<Entry> entries, JsonNode json, String unreadable) {

    private static final ObjectMapper READER = JacksonDefaults.mapper();

    public FeatAbilityIncrease {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One entry: fixed increases, or a choice of one of the abilities it lists.
     *
     * @param fixed  each fixed increase by ability as the data writes it, such as "CON"; empty for a choice
     * @param choose the choice, or null
     */
    public record Entry(Map<String, Integer> fixed, Choose choose) {

        public Entry {
            fixed = fixed == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fixed));
        }
    }

    /** A choice of an increase to one of the abilities listed, such as "STR". */
    public record Choose(List<String> from, int amount) {

        public Choose {
            from = from == null ? List.of() : List.copyOf(from);
        }
    }

    /** The increases of this JSON, which they keep as it is. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FeatAbilityIncrease of(JsonNode json) {
        if (json == null || json.isNull()) {
            return new FeatAbilityIncrease(null, json, null);
        }
        if (!json.isArray()) {
            return new FeatAbilityIncrease(null, json, "ability score increases are a list");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : json) {
            if (!node.isObject()) {
                return new FeatAbilityIncrease(null, json, "an ability score increase is an object: " + node);
            }
            Map<String, Integer> fixed = new LinkedHashMap<>();
            Choose choose = null;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode value = field.getValue();
                if ("choose".equals(field.getKey())) {
                    JsonNode from = value.path("from");
                    JsonNode amount = value.path("amount");
                    if (!(from.isMissingNode() || from.isArray()) || !(amount.isMissingNode() || amount.isInt())) {
                        return new FeatAbilityIncrease(null, json, "a choice of increase doesn't fit: " + value);
                    }
                    List<String> abilities = new ArrayList<>();
                    from.forEach(ability -> abilities.add(ability.asString("")));
                    choose = new Choose(abilities, amount.isInt() ? amount.intValue() : 1);
                } else if (value.isInt()) {
                    fixed.put(field.getKey(), value.intValue());
                } else {
                    return new FeatAbilityIncrease(null, json, "an increase of " + field.getKey()
                            + " isn't a whole number: " + value);
                }
            }
            entries.add(new Entry(fixed, choose));
        }
        return new FeatAbilityIncrease(entries, json, null);
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /** The increases of the column's text, for data the caller knows to be JSON, such as a test's; null for null. */
    public static FeatAbilityIncrease parse(String json) {
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
     * The entries of a feat's increases, none for a feat without any; ones that don't fit the records are a 409
     * naming the feat (D155).
     */
    public static List<Entry> readable(Feat feat) {
        FeatAbilityIncrease increase = feat.getAbilityScoreIncrease();
        if (increase == null) return List.of();
        if (increase.unreadable() != null) {
            throw new ConflictException("The ability score increases of the feat " + feat.getName()
                    + " can't be read");
        }
        return increase.entries();
    }
}
