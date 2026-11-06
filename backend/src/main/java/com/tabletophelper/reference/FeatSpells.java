package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The spells a feat grants, the {@code feats.grants_features} column (D168): the 5etools {@code additionalSpells}
 * list, one option per spell list the player can pick, such as Magic Initiate's classes. Each option's spells are
 * known (cantrips, keyed "_") or innate (keyed "_", then cast "daily" by uses, after a "rest", at "will" or as a
 * "ritual"). A spell is named, such as "misty step" or "hex|xge", or chosen, by a filter such as
 * "level=0|class=Wizard" or from a list. The column's JSON is kept whole beside the records, and it is what is stored
 * and what the API returns.
 *
 * @param options    the options, in order
 * @param json       the column's JSON as stored
 * @param unreadable why the JSON doesn't fit the records, or null; the records are then empty, and
 *                   {@link #readable} stops a command that needs them with a 409 (D155)
 */
public record FeatSpells(List<Option> options, JsonNode json, String unreadable) {

    private static final Logger log = LoggerFactory.getLogger(FeatSpells.class);

    private static final ObjectMapper READER = JacksonDefaults.mapper();

    // A daily key: the uses, with an "e" for each spell once, such as "1" or "1e"
    private static final Pattern DAILY = Pattern.compile("^(\\d+)e?$");

    public FeatSpells {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /**
     * One spell list a player can pick.
     *
     * @param name   its name, such as "Wizard Spells"; null when the feat has one list
     * @param known  the known spells, by key ("_" for any level)
     * @param daily  the innate spells cast once a day each, by their uses per long rest
     * @param other  the other innate spells: at will, rituals or after a rest
     */
    public record Option(String name, Map<String, List<SpellEntry>> known, Map<Integer, List<SpellEntry>> daily,
                         List<SpellEntry> other) {

        public Option {
            known = known == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(known));
            daily = daily == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(daily));
            other = other == null ? List.of() : List.copyOf(other);
        }
    }

    /**
     * One spell: named, chosen by a filter, or chosen from a list.
     *
     * @param name   the spell named, such as "misty step"; null for a choice
     * @param filter the choice's filter, such as "level=0|class=Wizard"; null otherwise
     * @param from   the spells the choice is from; empty otherwise
     * @param count  how many the choice picks
     */
    public record SpellEntry(String name, String filter, List<String> from, int count) {

        public SpellEntry {
            from = from == null ? List.of() : List.copyOf(from);
        }

        /** The spell named, without its book, class tag or level: "hex|xge" and "Hex#c" give "hex". */
        public String spellName() {
            return name == null ? null : name.replaceAll("#c$", "").replaceAll("#\\d+$", "").replaceAll("\\|.*$", "")
                    .trim().toLowerCase(Locale.ROOT);
        }
    }

    /** The spells of this JSON, which they keep as it is. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FeatSpells of(JsonNode json) {
        if (json == null || json.isNull()) {
            return new FeatSpells(null, json, null);
        }
        if (!json.isArray()) {
            return new FeatSpells(null, json, "the spells a feat grants are a list of options");
        }
        try {
            List<Option> options = new ArrayList<>();
            for (JsonNode option : json) {
                options.add(option(option));
            }
            return new FeatSpells(options, json, null);
        } catch (IllegalArgumentException e) {
            return new FeatSpells(null, json, e.getMessage());
        }
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /** The spells of the column's text, for data the caller knows to be JSON, such as a test's; null for null. */
    public static FeatSpells parse(String json) {
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
     * The options of a feat's spells, none for a feat that grants none; spells that don't fit the records are a 409
     * naming the feat (D155).
     */
    public static List<Option> readable(Feat feat) {
        FeatSpells spells = feat.getGrantsFeatures();
        if (spells == null) return List.of();
        if (spells.unreadable() != null) {
            log.error("The spells the feat {} grants don't fit the records: {}", feat.getName(), spells.unreadable());
            throw new ConflictException("The spells the feat " + feat.getName() + " grants can't be read");
        }
        return spells.options();
    }

    private static Option option(JsonNode option) {
        if (!option.isObject()) {
            throw new IllegalArgumentException("an option of spells is an object: " + option);
        }
        JsonNode name = option.get("name");
        Map<String, List<SpellEntry>> known = new LinkedHashMap<>();
        JsonNode knownNode = option.get("known");
        if (knownNode != null) {
            requireObject(knownNode, "known");
            knownNode.properties().forEach(e -> known.put(e.getKey(), entries(e.getValue(), "known")));
        }
        Map<Integer, List<SpellEntry>> daily = new LinkedHashMap<>();
        List<SpellEntry> other = new ArrayList<>();
        JsonNode innate = option.get("innate");
        if (innate != null) {
            requireObject(innate, "innate");
            for (Map.Entry<String, JsonNode> level : innate.properties()) {
                JsonNode casting = level.getValue();
                if (casting.isArray()) {
                    other.addAll(entries(casting, "innate"));
                    continue;
                }
                requireObject(casting, "innate");
                for (Map.Entry<String, JsonNode> how : casting.properties()) {
                    switch (how.getKey()) {
                        case "daily" -> {
                            requireObject(how.getValue(), "daily");
                            how.getValue().properties().forEach(uses -> {
                                var matcher = DAILY.matcher(uses.getKey());
                                if (!matcher.matches()) {
                                    throw new IllegalArgumentException("a daily key isn't a number of uses: "
                                            + uses.getKey());
                                }
                                daily.computeIfAbsent(Integer.parseInt(matcher.group(1)), k -> new ArrayList<>())
                                        .addAll(entries(uses.getValue(), "daily"));
                            });
                        }
                        case "rest" -> {
                            requireObject(how.getValue(), "rest");
                            how.getValue().properties().forEach(uses -> other.addAll(entries(uses.getValue(), "rest")));
                        }
                        case "will", "ritual" -> other.addAll(entries(how.getValue(), how.getKey()));
                        default -> throw new IllegalArgumentException("innate spells cast by " + how.getKey()
                                + " aren't read");
                    }
                }
            }
        }
        return new Option(name != null && name.isString() ? name.asString() : null, known, daily, other);
    }

    private static List<SpellEntry> entries(JsonNode list, String where) {
        if (!list.isArray()) {
            throw new IllegalArgumentException(where + " spells are a list: " + list);
        }
        List<SpellEntry> entries = new ArrayList<>();
        for (JsonNode item : list) {
            if (item.isString()) {
                entries.add(new SpellEntry(item.asString(), null, null, 1));
                continue;
            }
            JsonNode choose = item.path("choose");
            int count = item.path("count").asInt(1);
            if (choose.isString()) {
                entries.add(new SpellEntry(null, choose.asString(), null, count));
            } else if (choose.isObject() && choose.path("from").isArray()) {
                List<String> from = new ArrayList<>();
                choose.path("from").forEach(spell -> from.add(spell.asString("")));
                entries.add(new SpellEntry(null, null, from, choose.path("count").asInt(count)));
            } else {
                throw new IllegalArgumentException(where + " spell doesn't fit: " + item);
            }
        }
        return entries;
    }

    private static void requireObject(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " spells are an object: " + node);
        }
    }
}
