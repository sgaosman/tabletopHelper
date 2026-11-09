package com.tabletophelper.reference;

import com.tabletophelper.reference.dto.StartingEquipmentResponse;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentCandidate;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentEntry;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.StartingGold;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentGroup;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentKind;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentOption;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The starting equipment a class or background gives (PHB p.143), read from the 5etools data the loaders keep in
 * {@code starting_equipment}: a class's {@code defaultData} and {@code goldAlternative}, and a background's list.
 * Each line is a group whose keys are "_" for what is always given, or "a", "b", "c"... for a choice. An entry is
 * <ul>
 *   <li>an item reference, "chain mail|phb" (name, then source, PHB when blank), or {@code {item, quantity,
 *       displayName, containsValue}}: linked to the items table by name and source, or by name alone when the source
 *       has no such item, and kept by name when nothing matches;</li>
 *   <li>{@code {special, quantity, worthValue, containsValue}}: something only described, such as an insignia of rank;
 *   </li>
 *   <li>{@code {value}}: coins, in copper pieces;</li>
 *   <li>{@code {equipmentType, quantity}}: "a martial weapon" and the like, offered as a pick from the PHB's items of
 *       that kind. A type this class doesn't know is kept as a named entry.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StartingEquipmentOptions {

    private static final String DEFAULT_SOURCE = "PHB";
    private static final String FIXED = "_";
    private static final Pattern MARKUP = Pattern.compile("\\{@\\w+\\s+([^}]*)}");
    private static final Pattern GOLD = Pattern.compile("(\\d+)d(\\d+)(?:\\s*[×x*]\\s*(\\d+))?");

    // The spellcasting foci of PHB p.151, which the items table doesn't tell apart
    private static final List<String> ARCANE_FOCI = List.of("crystal", "orb", "rod", "staff", "wand");
    private static final List<String> DRUIDIC_FOCI = List.of("sprig of mistletoe", "totem", "wooden staff", "yew wand");
    private static final List<String> HOLY_SYMBOLS = List.of("amulet", "emblem", "reliquary");

    private static final String MELEE = "Melee Weapon";
    private static final String RANGED = "Ranged Weapon";
    private static final String SIMPLE = "simple";
    private static final String MARTIAL = "martial";

    private final CharacterClassRepository characterClassRepository;
    private final BackgroundRepository backgroundRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    /**
     * A class's starting equipment and its starting wealth.
     *
     * @throws NotFoundException for a class that doesn't exist
     * @throws ConflictException for data that can't be read
     */
    @Transactional(readOnly = true)
    public StartingEquipmentResponse forClass(UUID classId) {
        CharacterClass characterClass = characterClassRepository.findById(classId)
                .orElseThrow(() -> new NotFoundException("Class not found"));
        JsonNode data = read(characterClass.getStartingEquipment(), characterClass.getName());
        if (data == null) {
            return new StartingEquipmentResponse(List.of(), null);
        }
        return new StartingEquipmentResponse(groups(data.path("defaultData")),
                goldAlternative(data.path("goldAlternative").asString("")));
    }

    /**
     * A background's starting equipment.
     *
     * @throws NotFoundException for a background that doesn't exist
     * @throws ConflictException for data that can't be read
     */
    @Transactional(readOnly = true)
    public StartingEquipmentResponse forBackground(UUID backgroundId) {
        Background background = backgroundRepository.findById(backgroundId)
                .orElseThrow(() -> new NotFoundException("Background not found"));
        JsonNode data = read(background.getStartingEquipment(), background.getName());
        return new StartingEquipmentResponse(data == null ? List.of() : groups(data), null);
    }

    private JsonNode read(String json, String owner) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isNull() ? null : node;
        } catch (JacksonException e) {
            throw new ConflictException("The starting equipment of " + owner + " can't be read");
        }
    }

    /** The starting wealth in a 5etools dice tag, such as "{@dice 5d4 × 10|5d4 × 10|Starting Gold}"; null for none. */
    static StartingGold goldAlternative(String text) {
        Matcher matcher = GOLD.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int multiplier = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 1;
        return new StartingGold(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), multiplier);
    }

    // ── Groups ───────────────────────────────────────────────────

    private List<EquipmentGroup> groups(JsonNode data) {
        if (!data.isArray()) {
            return List.of();
        }
        Map<String, List<Item>> items = itemsNamed(data);
        Map<String, List<EquipmentCandidate>> picks = new HashMap<>();
        List<EquipmentGroup> groups = new ArrayList<>();
        for (JsonNode group : data) {
            if (!group.isObject()) {
                continue;
            }
            List<EquipmentOption> options = new ArrayList<>();
            for (Map.Entry<String, JsonNode> option : group.properties()) {
                String key = option.getKey();
                List<EquipmentEntry> entries = new ArrayList<>();
                for (JsonNode entry : option.getValue()) {
                    EquipmentEntry parsed = entry(entry, items, picks);
                    if (parsed != null) {
                        entries.add(parsed);
                    }
                }
                options.add(new EquipmentOption(key, entries));
            }
            if (!options.isEmpty()) {
                boolean choice = options.size() > 1 || !FIXED.equals(options.getFirst().key());
                groups.add(new EquipmentGroup(choice, options));
            }
        }
        return groups;
    }

    // Every item the data names, by lower-case name, found in one query
    private Map<String, List<Item>> itemsNamed(JsonNode data) {
        Set<String> names = new HashSet<>();
        for (JsonNode group : data) {
            for (JsonNode option : group) {
                for (JsonNode entry : option) {
                    String reference = entry.isString() ? entry.asString() : entry.path("item").asString(null);
                    if (reference != null) {
                        names.add(referenceName(reference).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        Map<String, List<Item>> byName = new HashMap<>();
        if (!names.isEmpty()) {
            for (Item item : itemRepository.findByLowerNames(names)) {
                byName.computeIfAbsent(item.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(item);
            }
        }
        return byName;
    }

    private EquipmentEntry entry(JsonNode entry, Map<String, List<Item>> items, Map<String, List<EquipmentCandidate>> picks) {
        if (entry.isString()) {
            return itemEntry(entry.asString(), null, 1, 0, items);
        }
        if (!entry.isObject()) {
            return null;
        }
        int quantity = Math.max(1, entry.path("quantity").asInt(1));
        int coins = entry.path("containsValue").asInt(0);
        if (entry.has("item")) {
            return itemEntry(entry.get("item").asString(""), entry.path("displayName").asString(null), quantity, coins,
                    items);
        }
        if (entry.has("special")) {
            String name = capitalised(stripMarkup(entry.get("special").asString("")));
            if (entry.has("worthValue")) {
                name += " (worth " + coins(entry.get("worthValue").asInt(0)) + ")";
            }
            return new EquipmentEntry(EquipmentKind.ITEM, name, quantity, null, null, coins, List.of());
        }
        if (entry.has("value")) {
            int value = entry.get("value").asInt(0);
            return new EquipmentEntry(EquipmentKind.COINS, coins(value), 1, null, null, value, List.of());
        }
        if (entry.has("equipmentType")) {
            return pick(entry.get("equipmentType").asString(""), quantity, picks);
        }
        return null;
    }

    private EquipmentEntry itemEntry(String reference, String displayName, int quantity, int coins,
                            Map<String, List<Item>> items) {
        String name = referenceName(reference);
        String source = referenceSource(reference);
        List<Item> named = items.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        Item item = named.stream().filter(i -> source.equalsIgnoreCase(i.getSource())).findFirst()
                .orElse(named.isEmpty() ? null : named.getFirst());
        String shown = displayName != null ? capitalised(stripMarkup(displayName))
                : item != null ? item.getName() : capitalised(name);
        return new EquipmentEntry(EquipmentKind.ITEM, shown, quantity, item != null ? item.getId() : null,
                item != null ? item.getArmourCategory() : null, coins, List.of());
    }

    // ── Picks ────────────────────────────────────────────────────

    private EquipmentEntry pick(String equipmentType, int quantity, Map<String, List<EquipmentCandidate>> picks) {
        String label = pickLabel(equipmentType);
        if (label == null) {
            // A kind this list doesn't know is kept as named
            return new EquipmentEntry(EquipmentKind.ITEM, capitalised(equipmentType), quantity, null, null, 0, List.of());
        }
        List<EquipmentCandidate> candidates = picks.computeIfAbsent(equipmentType, this::candidates);
        return new EquipmentEntry(EquipmentKind.PICK, label, quantity, null, null, 0, candidates);
    }

    static String pickLabel(String equipmentType) {
        return switch (equipmentType) {
            case "weaponSimple" -> "Simple weapon";
            case "weaponSimpleMelee" -> "Simple melee weapon";
            case "weaponSimpleRanged" -> "Simple ranged weapon";
            case "weaponMartial" -> "Martial weapon";
            case "weaponMartialMelee" -> "Martial melee weapon";
            case "weaponMartialRanged" -> "Martial ranged weapon";
            case "focusSpellcastingArcane" -> "Arcane focus";
            case "focusSpellcastingDruidic" -> "Druidic focus";
            case "focusSpellcastingHoly" -> "Holy symbol";
            case "instrumentMusical" -> "Musical instrument";
            case "toolArtisan" -> "Artisan's tools";
            case "setGaming" -> "Gaming set";
            default -> null;
        };
    }

    private List<EquipmentCandidate> candidates(String equipmentType) {
        List<Item> items = switch (equipmentType) {
            case "weaponSimple" -> weapons(SIMPLE, null);
            case "weaponSimpleMelee" -> weapons(SIMPLE, MELEE);
            case "weaponSimpleRanged" -> weapons(SIMPLE, RANGED);
            case "weaponMartial" -> weapons(MARTIAL, null);
            case "weaponMartialMelee" -> weapons(MARTIAL, MELEE);
            case "weaponMartialRanged" -> weapons(MARTIAL, RANGED);
            case "focusSpellcastingArcane" -> foci(ARCANE_FOCI);
            case "focusSpellcastingDruidic" -> foci(DRUIDIC_FOCI);
            case "focusSpellcastingHoly" -> foci(HOLY_SYMBOLS);
            case "instrumentMusical" -> itemRepository.findPhbMundaneOfType("Instrument");
            case "toolArtisan" -> itemRepository.findPhbMundaneOfType("Artisan's Tools");
            case "setGaming" -> itemRepository.findPhbMundaneOfType("Gaming Set");
            default -> List.of();
        };
        return items.stream().map(item -> new EquipmentCandidate(item.getName(), item.getId())).toList();
    }

    // The PHB's weapons of a category (PHB p.149), and of a type when one is given
    private List<Item> weapons(String category, String type) {
        Predicate<Item> ofType = type == null ? item -> true : item -> type.equals(item.getType());
        return itemRepository.findMundaneWeapons().stream()
                .filter(item -> DEFAULT_SOURCE.equalsIgnoreCase(item.getSource()))
                .filter(item -> category.equalsIgnoreCase(item.getSubtype()))
                .filter(ofType)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    private List<Item> foci(List<String> names) {
        return itemRepository.findByLowerNames(names).stream()
                .filter(item -> DEFAULT_SOURCE.equalsIgnoreCase(item.getSource()))
                .filter(item -> "Spellcasting Focus".equals(item.getType()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    // ── Text ─────────────────────────────────────────────────────

    static String referenceName(String reference) {
        int bar = reference.indexOf('|');
        return (bar < 0 ? reference : reference.substring(0, bar)).trim();
    }

    static String referenceSource(String reference) {
        String[] parts = reference.split("\\|", -1);
        return parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : DEFAULT_SOURCE;
    }

    /** A 5etools tag's text: "{@item Horror Trinket|VRGR|Horror Trinkets}" shows "Horror Trinkets". */
    static String stripMarkup(String text) {
        Matcher matcher = MARKUP.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String[] parts = matcher.group(1).split("\\|", -1);
            String shown = parts.length > 2 && !parts[2].isBlank() ? parts[2] : parts[0];
            matcher.appendReplacement(result, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(result);
        return result.toString().trim();
    }

    static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Coins in copper pieces as the largest whole coin: 1500 is "15 gp", 50 "5 sp", 7 "7 cp". */
    static String coins(int copper) {
        if (copper >= 100 && copper % 100 == 0) {
            return copper / 100 + " gp";
        }
        if (copper >= 10 && copper % 10 == 0) {
            return copper / 10 + " sp";
        }
        return copper + " cp";
    }
}
