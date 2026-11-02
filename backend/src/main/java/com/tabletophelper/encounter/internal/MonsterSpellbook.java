package com.tabletophelper.encounter.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A monster's spellcasting as its action templates record it (D165): its spellcasting numbers, its spell slots,
 * and each spell with how it is cast, from the typed {@link MonsterTemplates.Spellcasting} block. The seeded data
 * comes in several shapes, all read here:
 * <ul>
 *   <li>{@code spellsByLevel} lists spells under their level, "0" for cantrips, but sometimes under daily keys such
 *       as "1e/day" or an "at_will" key instead;</li>
 *   <li>{@code dailySpells} lists spells under daily keys: "1e", "1/day", "3e/day each" and a plain "2" all give
 *       uses, never a spell level;</li>
 *   <li>{@code atWillSpells} lists the spells cast at will;</li>
 *   <li>{@code innateSpells} is a flag, except for a few monsters that keep their daily and at-will lists in it.</li>
 * </ul>
 * The flag doesn't say whether a monster has slots, so it has them exactly when the block lists them. A second
 * spellcasting block ({@code additionalSpellcasting}) isn't read.
 *
 * @param ability     the spellcasting ability, such as "INT"; null when not given
 * @param attackBonus the spell attack bonus; null when not given
 * @param saveDc      the spell save DC; null when not given
 * @param slots       the slots of each spell level, keyed "1" to "9"
 * @param spells      each spell, in the order the block lists them
 */
record MonsterSpellbook(String ability, Integer attackBonus, Integer saveDc, Map<String, Integer> slots,
                        List<Entry> spells) {

    /** No spellcasting at all. */
    static final MonsterSpellbook NONE = new MonsterSpellbook(null, null, null, Map.of(), List.of());

    // "1e", "1/day", "3e/day each", "2/day each" or a plain "2": that many uses a day
    private static final Pattern DAILY_KEY = Pattern.compile("(?i)^(\\d{1,2})\\s*e?\\s*(?:/\\s*day)?\\s*(?:each)?$");
    private static final Pattern AT_WILL_KEY = Pattern.compile("(?i)^(?:at[ _-]?will|will)$");
    private static final Pattern LEVEL_KEY = Pattern.compile("^\\d$");
    // A note in brackets at the end of a name, as in "plane shift (self only)", or an asterisk
    private static final Pattern NAME_NOTE = Pattern.compile("\\s*(?:\\([^()]*\\)|\\*)\\s*$");

    /** How a monster casts a spell. */
    enum Kind {
        /** Listed under its level: a cantrip, or a spell that needs a slot. */
        LEVELLED,
        AT_WILL,
        DAILY
    }

    /**
     * A spell in the block.
     *
     * @param name        as the stat block writes it, notes and all, such as "plane shift (self only)"
     * @param listedLevel the level a LEVELLED spell is listed under; null for the others
     * @param uses        a DAILY spell's uses; 0 for the others
     */
    record Entry(String name, Kind kind, Integer listedLevel, int uses) {

        /** The name to look the spell up by, without the notes in brackets or asterisks at its end. */
        String lookupName() {
            String name = this.name.trim();
            Matcher note = NAME_NOTE.matcher(name);
            while (note.find()) {
                name = name.substring(0, note.start()).trim();
                note = NAME_NOTE.matcher(name);
            }
            return name.isEmpty() ? this.name.trim() : name;
        }
    }

    /** Whether the monster has a spellcasting block at all. */
    boolean present() {
        return this != NONE;
    }

    /** Reads the spellcasting block of a monster's action templates. */
    static MonsterSpellbook read(MonsterTemplates templates) {
        MonsterTemplates.Spellcasting block = templates.spellcasting();
        if (block == null) {
            return NONE;
        }
        List<Entry> spells = new ArrayList<>();
        block.spellsByLevel().forEach((group, names) -> {
            String key = group.trim();
            if (LEVEL_KEY.matcher(key).matches()) {
                addAll(spells, names, Kind.LEVELLED, Integer.valueOf(key), 0);
            } else {
                addKeyed(spells, key, names);
            }
        });
        block.dailySpells().forEach((key, names) -> addKeyed(spells, key, names));
        addAll(spells, block.atWillSpells(), Kind.AT_WILL, null, 0);
        block.innateLists().forEach((key, names) -> addKeyed(spells, key, names));
        return new MonsterSpellbook(text(block.ability()), block.attackBonus(), block.saveDc(), slots(block.slots()),
                spells);
    }

    /** The uses a daily key gives, such as 3 for "3e/day each"; empty for a key that isn't one. */
    static OptionalInt dailyUses(String key) {
        Matcher matcher = DAILY_KEY.matcher(key.trim());
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        int uses = Integer.parseInt(matcher.group(1));
        return uses > 0 ? OptionalInt.of(uses) : OptionalInt.empty();
    }

    // A group under a daily or at-will key; any other key is skipped
    private static void addKeyed(List<Entry> spells, String key, List<String> names) {
        if (AT_WILL_KEY.matcher(key.trim()).matches()) {
            addAll(spells, names, Kind.AT_WILL, null, 0);
            return;
        }
        dailyUses(key).ifPresent(uses -> addAll(spells, names, Kind.DAILY, null, uses));
    }

    private static void addAll(List<Entry> spells, List<String> names, Kind kind, Integer listedLevel, int uses) {
        for (String name : names != null ? names : List.<String>of()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Entry entry = new Entry(name.trim(), kind, listedLevel, uses);
            if (!spells.contains(entry)) {
                spells.add(entry);
            }
        }
    }

    // Levels "1" to "9" with at least one slot, in level order
    private static Map<String, Integer> slots(Map<String, Integer> slots) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int level = 1; level <= 9; level++) {
            Integer count = slots.get(String.valueOf(level));
            if (count != null && count > 0) {
                result.put(String.valueOf(level), count);
            }
        }
        return result;
    }

    private static String text(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
