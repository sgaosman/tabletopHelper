package com.tabletophelper.character;

import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.Item.ArmourCategory;
import com.tabletophelper.reference.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The armour and shield a character wears, read from its inventory (M13.5): each entry marked equipped and carried,
 * linked to an armour or shield in the items table, or else named after one ("Chain Mail", or "Plate" for Plate
 * Armor). The first body armour and the first shield in inventory order are the ones worn; an inventory can't equip a
 * second (InventoryChecks), so more is only in data saved before that check, and is reported.
 * <p>
 * Armour worn without proficiency, a shield included, gives disadvantage on every ability check, saving throw and
 * attack roll that uses Strength or Dexterity, and its wearer can't cast spells (PHB p.144). Proficiency comes from
 * the sheet's armour proficiencies, which name a category ("light", "Medium Armor", "shields") or all armour.
 * Combat reads this live from the sheet, as it reads ability scores.
 */
@Component
@RequiredArgsConstructor
public class EquippedArmour {

    // 5etools markup around a name, as in "{@item shield|phb|shields}"
    private static final Pattern MARKUP = Pattern.compile("\\{@\\w+\\s+([^|}]*)[^}]*}");
    private static final Pattern ALL_ARMOUR = Pattern.compile("^(all )?armou?r$");
    // The source 5etools gives the PHB's items, preferred when a name matches items from more than one book
    private static final String PHB = "PHB";

    private final ItemRepository itemRepository;

    /**
     * What a character wears.
     *
     * @param armour        the body armour worn, or null for none
     * @param shield        the shield carried, or null for none
     * @param ignored       the names of the further armour and shields marked equipped, which don't count
     * @param notProficient the armour and shield worn without proficiency, armour first
     */
    public record Worn(Item armour, Item shield, List<String> ignored, List<Item> notProficient) {

        public Worn {
            ignored = List.copyOf(ignored);
            notProficient = List.copyOf(notProficient);
        }

        /** Nothing worn. */
        public static Worn nothing() {
            return new Worn(null, null, List.of(), List.of());
        }

        /** Whether armour or a shield is worn without proficiency: disadvantage on STR and DEX rolls, no spells. */
        public boolean penalised() {
            return !notProficient.isEmpty();
        }

        /**
         * Whether Dexterity (Stealth) checks are made with disadvantage: the armour worn says so (PHB p.145), or it is
         * worn without proficiency, which gives disadvantage on every Dexterity check (PHB p.144).
         */
        public boolean stealthDisadvantage() {
            return (armour != null && Boolean.TRUE.equals(armour.getStealthDisadvantage())) || penalised();
        }
    }

    /** What the character wears now. */
    public Worn of(PlayerCharacter character) {
        List<Item> equipped = equipped(character.getEquipment());
        Item armour = null;
        Item shield = null;
        List<String> ignored = new ArrayList<>();
        for (Item item : equipped) {
            if (item.getArmourCategory() == ArmourCategory.SHIELD) {
                if (shield == null) shield = item;
                else ignored.add(item.getName());
            } else if (armour == null) {
                armour = item;
            } else {
                ignored.add(item.getName());
            }
        }
        Set<ArmourCategory> proficiencies = proficiencies(character.getArmorProficiencies());
        List<Item> notProficient = new ArrayList<>();
        for (Item item : new Item[]{armour, shield}) {
            if (item != null && !proficiencies.contains(item.getArmourCategory())) {
                notProficient.add(item);
            }
        }
        return new Worn(armour, shield, ignored, notProficient);
    }

    /**
     * The armour and shields among an inventory's equipped, carried entries, in inventory order: each entry's linked
     * item when it is armour or a shield, or else the armour or shield it is named after.
     */
    public List<Item> equipped(List<InventoryItem> inventory) {
        return new ArrayList<>(armour(inventory, true).values());
    }

    /**
     * The positions in the inventory of the carried entries that are armour or a shield, equipped or not, so the sheet
     * can offer to equip them.
     */
    public List<Integer> armourEntries(List<InventoryItem> inventory) {
        return new ArrayList<>(armour(inventory, false).keySet());
    }

    // The armour or shield each carried entry is, by its position in the inventory; only equipped entries when asked
    private Map<Integer, Item> armour(List<InventoryItem> inventory, boolean equippedOnly) {
        Map<Integer, InventoryItem> entries = new LinkedHashMap<>();
        List<InventoryItem> all = inventory != null ? inventory : List.of();
        for (int i = 0; i < all.size(); i++) {
            InventoryItem entry = all.get(i);
            if (entry == null || (entry.quantity() != null && entry.quantity() <= 0)) continue;
            if (equippedOnly && !Boolean.TRUE.equals(entry.equipped())) continue;
            entries.put(i, entry);
        }
        if (entries.isEmpty()) {
            return Map.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (InventoryItem entry : entries.values()) {
            names.addAll(candidateNames(entry.name()));
        }
        Map<String, Item> byName = names.isEmpty() ? Map.of() : itemRepository.findArmourByLowerNames(names).stream()
                .sorted(Comparator.comparing((Item i) -> !PHB.equalsIgnoreCase(i.getSource())))
                .collect(Collectors.toMap(i -> i.getName().toLowerCase(Locale.ROOT), Function.identity(),
                        (first, later) -> first));

        Map<Integer, Item> armour = new LinkedHashMap<>();
        entries.forEach((index, entry) -> linked(entry.itemId())
                .or(() -> candidateNames(entry.name()).stream().map(byName::get).filter(Objects::nonNull).findFirst())
                .ifPresent(item -> armour.put(index, item)));
        return armour;
    }

    /**
     * The armour categories a sheet's armour proficiencies cover: "light", "Light Armor", "medium", "heavy",
     * "shield" or "shields", and "all armor" for the three kinds of armour. 5etools markup, a "|source" suffix, a note
     * in brackets and case are ignored.
     */
    public static Set<ArmourCategory> proficiencies(List<String> proficiencies) {
        Set<ArmourCategory> categories = EnumSet.noneOf(ArmourCategory.class);
        for (String proficiency : proficiencies != null ? proficiencies : List.<String>of()) {
            if (proficiency == null) continue;
            String text = normalised(proficiency, true);
            if (ALL_ARMOUR.matcher(text).matches()) {
                categories.addAll(EnumSet.of(ArmourCategory.LIGHT, ArmourCategory.MEDIUM, ArmourCategory.HEAVY));
            } else if (text.startsWith("light")) {
                categories.add(ArmourCategory.LIGHT);
            } else if (text.startsWith("medium")) {
                categories.add(ArmourCategory.MEDIUM);
            } else if (text.startsWith("heavy")) {
                categories.add(ArmourCategory.HEAVY);
            } else if (text.startsWith("shield")) {
                categories.add(ArmourCategory.SHIELD);
            }
        }
        return categories;
    }

    // The item an entry is linked to, when it is armour or a shield
    private Optional<Item> linked(UUID itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return itemRepository.findById(itemId).filter(item -> item.getArmourCategory() != null);
    }

    // The names an entry may mean, lower case: as written, then with "armor" after it, as "plate" means Plate Armor
    private static List<String> candidateNames(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String plain = normalised(name, false).replace("armour", "armor");
        return plain.endsWith(" armor") || plain.equals("armor") ? List.of(plain) : List.of(plain, plain + " armor");
    }

    // Text without markup, source, case or, when dropNote is true, a note in brackets, its spaces collapsed
    private static String normalised(String text, boolean dropNote) {
        String plain = MARKUP.matcher(text).replaceAll("$1");
        int source = plain.indexOf('|');
        if (source >= 0) {
            plain = plain.substring(0, source);
        }
        int note = plain.indexOf('(');
        if (dropNote && note > 0) {
            plain = plain.substring(0, note);
        }
        return plain.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
