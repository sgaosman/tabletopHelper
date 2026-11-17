package com.tabletophelper.character;

import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The spells a subclass grants besides its cantrips (D231), from the subclass's {@code always_prepared_spells} and
 * {@code granted_spells} (V41), which the class loader fills from the 5etools data: the spells it keeps always prepared,
 * the spells it teaches by class level, such as a Gloom Stalker's disguise self at Ranger 3 (XGE p.42) or Aberrant Mind's
 * psionic spells (TCE p.66), and, for a subclass whose player picks one of several named lists, each list's spells: a
 * Circle of the Land druid's terrain gives its circle spells, always prepared at Druid 3, 5, 7 and 9 (PHB p.68-69), and a
 * Divine Soul's affinity its spell (XGE p.50), and a Genie warlock's kind its expanded spells (TCE). The option
 * picked is recorded on the level that records the subclass ({@code LevelChoices.subclassOption}); until it is, the
 * option's spells aren't granted, nor on the class's list (D239). The Fathomless's Evard's black tentacles is taught at
 * Warlock 10 with one use a long rest (Grasping Tentacles, TCE).
 * <p>
 * Aberrant Mind and Clockwork Soul let a sorcerer swap a psionic or clockwork spell for another of its level from a
 * school's list on levelling up (TCE p.66, p.68); the swap isn't offered yet.
 */
public final class SubclassSpells {

    private SubclassSpells() {
    }

    /**
     * One named list a subclass's player picks from.
     *
     * @param prepared the spells it keeps always prepared, by the class level they come at
     * @param known    the spells it teaches, by the class level they come at
     * @param expanded the spells it adds to the class's list, as a Genie kind's (TCE); the spells table lists them
     *                 under {@link #listName}
     */
    public record Option(Map<Integer, List<String>> prepared, Map<Integer, List<String>> known,
                         List<String> expanded) {}

    /**
     * What a subclass grants.
     *
     * @param known   the spells it teaches every member, by the class level they come at
     * @param daily   the spells it teaches every member by class level that are also cast once without a slot between
     *                long rests, as the Fathomless's Evard's black tentacles at Warlock 10 (TCE)
     * @param options the named lists its player picks one of, in the data's order; empty for none
     */
    public record Granted(Map<Integer, List<String>> known, Map<Integer, List<String>> daily,
                          Map<String, Option> options) {

        /** The spells taught at exactly that class level that are also cast once a long rest without a slot. */
        public List<String> dailyAt(int classLevel) {
            return daily.getOrDefault(classLevel, List.of());
        }

        /** The names of the lists its player picks one of. */
        public List<String> optionNames() {
            return List.copyOf(options.keySet());
        }

        /** The option of that name, in any case. */
        public Optional<String> optionNamed(String name) {
            if (name == null) return Optional.empty();
            return options.keySet().stream().filter(option -> option.equalsIgnoreCase(name.trim())).findFirst();
        }

        /**
         * The spells the subclass teaches at exactly that class level: every member's, then the option's, when one
         * is picked.
         */
        public List<String> knownAt(int classLevel, String option) {
            List<String> spells = new ArrayList<>(known.getOrDefault(classLevel, List.of()));
            Option picked = option != null ? options.get(option) : null;
            if (picked != null) spells.addAll(picked.known().getOrDefault(classLevel, List.of()));
            return spells;
        }
    }

    private static final Granted NONE = new Granted(Map.of(), Map.of(), Map.of());

    /**
     * The name the spells table gives a subclass's additions to its class's list (D231, D239): "Warlock (The
     * Fathomless)", or for a subclass whose additions follow the option its player picked, that option's, as "Warlock
     * (The Genie: Dao)" (TCE) or "Druid (Circle of the Land: Arctic)" (PHB p.68-69). The spell loader writes the
     * same names. Null without a subclass.
     */
    public static String listName(String className, String subclassName, String option) {
        if (className == null || subclassName == null || subclassName.isBlank()) return null;
        return className.trim() + " (" + subclassName.trim()
                + (option != null && !option.isBlank() ? ": " + option.trim() : "") + ")";
    }

    /**
     * What the subclass grants; nothing for a subclass that grants nothing.
     *
     * @throws ConflictException when the subclass's granted spells can't be read (D155)
     */
    public static Granted of(ObjectMapper objectMapper, Subclass subclass) {
        String json = subclass.getGrantedSpells();
        if (json == null || json.isBlank()) return NONE;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) throw unreadable(subclass);
            Map<String, Option> options = new LinkedHashMap<>();
            JsonNode listed = root.path("options");
            if (!listed.isMissingNode() && !listed.isNull() && !listed.isArray()) throw unreadable(subclass);
            for (JsonNode option : listed) {
                String name = option.path("name").asString("").trim();
                if (name.isEmpty()) throw unreadable(subclass);
                options.put(name, new Option(byLevel(option.path("prepared"), subclass),
                        byLevel(option.path("known"), subclass), names(option.path("expanded"), subclass)));
            }
            return new Granted(byLevel(root.path("known"), subclass), byLevel(root.path("daily"), subclass), options);
        } catch (JacksonException e) {
            throw unreadable(subclass);
        }
    }

    /**
     * The subclass's always-prepared spells by the class level they come at, as the sheet shows them: its own, then
     * those of the option picked, such as a Land druid's terrain; null for none.
     *
     * @throws ConflictException when the subclass's spells can't be read (D155)
     */
    public static Map<String, List<String>> alwaysPrepared(ObjectMapper objectMapper, Subclass subclass,
                                                           String option) {
        Map<String, List<String>> combined = new LinkedHashMap<>();
        if (subclass.getAlwaysPreparedSpells() != null && !subclass.getAlwaysPreparedSpells().isBlank()) {
            try {
                JsonNode own = objectMapper.readTree(subclass.getAlwaysPreparedSpells());
                for (Map.Entry<String, JsonNode> level : own.properties()) {
                    List<String> names = new ArrayList<>();
                    level.getValue().forEach(name -> {
                        if (name.isString() && !name.asString("").isBlank()) names.add(name.asString(""));
                    });
                    combined.put(level.getKey(), names);
                }
            } catch (JacksonException e) {
                throw new ConflictException("The always-prepared spells of the subclass " + subclass.getName()
                        + " can't be read");
            }
        }
        Option picked = option != null ? of(objectMapper, subclass).options().get(option) : null;
        if (picked != null) {
            picked.prepared().forEach((level, names) -> combined.computeIfAbsent(String.valueOf(level),
                    key -> new ArrayList<>()).addAll(names));
        }
        return combined.isEmpty() ? null : combined;
    }

    /**
     * The option the character picked for the class's subclass, recorded on the level that records the subclass;
     * null for none.
     */
    public static String optionOf(PlayerCharacter character, UUID classId) {
        if (classId == null) return null;
        for (LevelHistoryEntry level : CharacterUpdates.orEmpty(character.getLevelHistory())) {
            LevelChoices choices = level.choices();
            if (choices != null && choices.subclass() != null && classId.equals(choices.subclass().classId())) {
                return choices.subclassOption();
            }
        }
        return null;
    }

    private static Map<Integer, List<String>> byLevel(JsonNode node, Subclass subclass) {
        Map<Integer, List<String>> byLevel = new LinkedHashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) return byLevel;
        if (!node.isObject()) throw unreadable(subclass);
        for (Map.Entry<String, JsonNode> level : node.properties()) {
            int classLevel;
            try {
                classLevel = Integer.parseInt(level.getKey().trim());
            } catch (NumberFormatException e) {
                throw unreadable(subclass);
            }
            List<String> names = new ArrayList<>();
            for (JsonNode name : level.getValue()) {
                if (name.isString() && !name.asString("").isBlank()) names.add(name.asString("").trim());
            }
            byLevel.put(classLevel, List.copyOf(names));
        }
        return byLevel;
    }

    private static List<String> names(JsonNode node, Subclass subclass) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw unreadable(subclass);
        List<String> names = new ArrayList<>();
        for (JsonNode name : node) {
            if (name.isString() && !name.asString("").isBlank()) names.add(name.asString("").trim());
        }
        return List.copyOf(names);
    }

    private static ConflictException unreadable(Subclass subclass) {
        return new ConflictException("The granted spells of the subclass " + subclass.getName() + " can't be read");
    }
}
