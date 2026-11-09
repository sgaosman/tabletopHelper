package com.tabletophelper.rules.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The lists a proficiency "of your choice" is chosen from, as a subclass's bonus proficiencies name them in
 * {@code data/content/subclass-proficiencies.json}: a name starting with {@code *} stands for one of these lists, so the
 * College of Lore's "three skills of your choice" (PHB p.54) is {@code *skills}.
 * <ul>
 *   <li>{@code *skills}: the 18 skills (PHB p.174)</li>
 *   <li>{@code *languages}: the standard and exotic languages (PHB p.123)</li>
 *   <li>{@code *artisan's tools}: the 17 kinds of artisan's tools (PHB p.154)</li>
 *   <li>{@code *gaming sets}: the four gaming sets (PHB p.154)</li>
 *   <li>{@code *one-handed melee weapons}: the simple and martial melee weapons without the two-handed property (PHB
 *       p.149), less the lance, which needs two hands when its wielder isn't mounted (PHB p.147)</li>
 * </ul>
 */
public final class ProficiencyLists {

    /** What a name in the file starts with when it stands for a list. */
    public static final String LIST_PREFIX = "*";

    public static final List<String> SKILLS = List.of(
            "Acrobatics", "Animal Handling", "Arcana", "Athletics", "Deception", "History", "Insight", "Intimidation",
            "Investigation", "Medicine", "Nature", "Perception", "Performance", "Persuasion", "Religion",
            "Sleight of Hand", "Stealth", "Survival");

    public static final List<String> LANGUAGES = List.of(
            "Common", "Dwarvish", "Elvish", "Giant", "Gnomish", "Goblin", "Halfling", "Orc",
            "Abyssal", "Celestial", "Deep Speech", "Draconic", "Infernal", "Primordial", "Sylvan", "Undercommon");

    public static final List<String> ARTISANS_TOOLS = List.of(
            "Alchemist's Supplies", "Brewer's Supplies", "Calligrapher's Supplies", "Carpenter's Tools",
            "Cartographer's Tools", "Cobbler's Tools", "Cook's Utensils", "Glassblower's Tools", "Jeweler's Tools",
            "Leatherworker's Tools", "Mason's Tools", "Painter's Supplies", "Potter's Tools", "Smith's Tools",
            "Tinker's Tools", "Weaver's Tools", "Woodcarver's Tools");

    public static final List<String> GAMING_SETS = List.of(
            "Dice Set", "Dragonchess Set", "Playing Card Set", "Three-Dragon Ante Set");

    /** The simple melee weapons a creature can wield in one hand (PHB p.149). */
    public static final List<String> SIMPLE_ONE_HANDED_MELEE = List.of(
            "Club", "Dagger", "Handaxe", "Javelin", "Light Hammer", "Mace", "Quarterstaff", "Sickle", "Spear");

    /** The martial melee weapons a creature can wield in one hand (PHB p.149), the lance left out. */
    public static final List<String> MARTIAL_ONE_HANDED_MELEE = List.of(
            "Battleaxe", "Flail", "Longsword", "Morningstar", "Rapier", "Scimitar", "Shortsword", "Trident",
            "War Pick", "Warhammer", "Whip");

    private static final Map<String, List<String>> LISTS = Map.of(
            "*skills", SKILLS,
            "*languages", LANGUAGES,
            "*artisan's tools", ARTISANS_TOOLS,
            "*gaming sets", GAMING_SETS,
            "*one-handed melee weapons", concat(SIMPLE_ONE_HANDED_MELEE, MARTIAL_ONE_HANDED_MELEE));

    private ProficiencyLists() {
    }

    /**
     * The names with each list name replaced by the list, in order, each name once whatever its case.
     *
     * @throws IllegalArgumentException for a list name that isn't one of these
     */
    public static List<String> expand(List<String> names) {
        List<String> expanded = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String name : names) {
            String trimmed = name.trim();
            List<String> items = trimmed.startsWith(LIST_PREFIX) ? list(trimmed) : List.of(trimmed);
            for (String item : items) {
                String key = item.toLowerCase(Locale.ROOT);
                if (!seen.contains(key)) {
                    seen.add(key);
                    expanded.add(item);
                }
            }
        }
        return expanded;
    }

    private static List<String> list(String name) {
        List<String> list = LISTS.get(name.toLowerCase(Locale.ROOT));
        if (list == null) {
            throw new IllegalArgumentException(name + " isn't one of the lists " + LISTS.keySet().stream().sorted()
                    .toList());
        }
        return list;
    }

    /**
     * The category of a one-handed melee weapon: "simple" or "martial" (PHB p.149); empty for a name that isn't one, so
     * proficiency with the whole category counts as proficiency with it.
     */
    public static Optional<String> weaponCategory(String weapon) {
        if (weapon == null) return Optional.empty();
        String name = weapon.trim();
        if (SIMPLE_ONE_HANDED_MELEE.stream().anyMatch(name::equalsIgnoreCase)) return Optional.of("simple");
        if (MARTIAL_ONE_HANDED_MELEE.stream().anyMatch(name::equalsIgnoreCase)) return Optional.of("martial");
        return Optional.empty();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
