package com.tabletophelper.content.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mechanical effects a data file in {@code data/content/} gives the rows the 5etools data doesn't (D167, D169),
 * keyed by each row's natural key, such as {@code "Hill Dwarf|PHB": {"hpPerLevel": 1}}. Each effect is one the
 * character rules read, with a whole number. A file that names an effect the rules don't read, or a row the data
 * doesn't have, fails the load, so a renamed or removed row is found rather than losing its effect.
 */
final class EffectsOverlay {

    private final String file;
    private final Map<String, String> effects;
    private final Set<String> used = new HashSet<>();

    private EffectsOverlay(String file, Map<String, String> effects) {
        this.file = file;
        this.effects = effects;
    }

    /** Reads the file, checking that each row's effects are among {@code known}, each a whole number. */
    static EffectsOverlay read(LoadContext context, ObjectMapper objectMapper, String file, Set<String> known) {
        JsonNode root = context.read(file);
        if (!root.isObject()) {
            throw new IllegalStateException(file + " is not an object of natural keys");
        }
        Map<String, String> effects = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> rows = root.fields();
        while (rows.hasNext()) {
            Map.Entry<String, JsonNode> row = rows.next();
            JsonNode value = row.getValue();
            if (!value.isObject() || value.isEmpty()) {
                throw new IllegalStateException(file + ": " + row.getKey() + " has no effects object");
            }
            value.fields().forEachRemaining(effect -> {
                if (!known.contains(effect.getKey())) {
                    throw new IllegalStateException(file + ": " + row.getKey() + " has the effect " + effect.getKey()
                            + ", which isn't one of " + known);
                }
                if (!effect.getValue().isIntegralNumber() || !effect.getValue().canConvertToInt()) {
                    throw new IllegalStateException(file + ": " + row.getKey() + "'s " + effect.getKey()
                            + " is not a whole number");
                }
            });
            try {
                effects.put(row.getKey(), objectMapper.writeValueAsString(value));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(file + ": " + row.getKey() + " could not be written", e);
            }
        }
        return new EffectsOverlay(file, effects);
    }

    /** The effects of the row with this natural key, as JSON text, or null for a row the file doesn't name. */
    String effectsOf(String key) {
        String found = effects.get(key);
        if (found != null) {
            used.add(key);
        }
        return found;
    }

    /** Fails when the file names a row the data didn't have. */
    void requireEveryRowFound() {
        List<String> missing = new ArrayList<>(effects.keySet());
        missing.removeAll(used);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(file + " names rows the data doesn't have: " + missing);
        }
    }
}
