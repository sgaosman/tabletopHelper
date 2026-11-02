package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.combat.WeaponRules;
import com.tabletophelper.rules.combat.WeaponRules.PowerAttack;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A player character's weapons, read from their sheet (D164): each inventory entry named after one of the mundane
 * weapons in the items table, matched by name until R6 links inventory entries to items, then an unarmed strike,
 * which everyone has. An entry with any other name, such as a magic weapon's, isn't offered: its attack is entered
 * by hand, as a Manual Modifier.
 * <ul>
 *   <li>Proficiency comes from the sheet's weapon proficiencies, which name a category ("simple", "Martial",
 *       "Simple Weapons"), a weapon ("dagger", "Hand crossbow", "longswords") or firearms.</li>
 *   <li>Great Weapon Master and Sharpshooter come from the sheet's features, where every feat is listed whatever
 *       granted it, or the feats its level history records.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class CharacterWeapons {

    // 5etools markup around a name, as in "{@item longsword|phb|longswords}"
    private static final Pattern MARKUP = Pattern.compile("\\{@\\w+\\s+([^|}]*)[^}]*}");
    // A weapon category as sheets spell it
    private static final Pattern CATEGORY = Pattern.compile("^(simple|martial)(?:\\s+weapons?)?$");
    private static final Pattern FIREARMS = Pattern.compile("^firearms?$");
    // A note in brackets at the end of a name, as in "Sharpshooter (feat)"
    private static final Pattern NOTE = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    /**
     * A weapon the character can attack with.
     *
     * @param proficient  whether they add their proficiency bonus to its attack rolls
     * @param powerAttack the feat's trade they may take with it, or null for none
     */
    record Held(WeaponRules.Weapon weapon, boolean proficient, PowerAttack powerAttack) {
    }

    /** The character's weapons in inventory order, each once, then the unarmed strike. */
    List<Held> of(PlayerCharacter character) {
        Map<String, Item> weapons = new LinkedHashMap<>();
        itemRepository.findMundaneWeapons().forEach(item -> weapons.putIfAbsent(key(item.getName()), item));
        List<String> proficiencies = strings(character.getWeaponProficiencies(),
                character.getName() + "'s weapon proficiencies");
        Set<PowerAttack> feats = feats(character);

        Map<String, Held> held = new LinkedHashMap<>();
        for (JsonNode entry : StoredJson.read(objectMapper, character.getEquipment(), character.getName() + "'s equipment")) {
            String name = entry.isTextual() ? entry.asText() : entry.path("name").asText("");
            // An entry recorded with none left isn't carried
            if (entry.path("quantity").isNumber() && entry.path("quantity").asInt() <= 0) {
                continue;
            }
            Item item = match(weapons, name);
            if (item != null && !held.containsKey(item.getName())) {
                weapon(item).ifPresent(weapon -> {
                    boolean proficient = proficient(proficiencies, weapon, firearm(item));
                    held.put(item.getName(), new Held(weapon, proficient,
                            WeaponRules.powerAttack(weapon, proficient, feats).orElse(null)));
                });
            }
        }
        List<Held> result = new ArrayList<>(held.values());
        result.add(new Held(WeaponRules.UNARMED_STRIKE, true, null));
        return result;
    }

    /** The character's weapon of that name, in any case. */
    Optional<Held> find(PlayerCharacter character, String weaponName) {
        String wanted = key(weaponName);
        return of(character).stream().filter(held -> key(held.weapon().name()).equals(wanted)).findFirst();
    }

    /** The attack's numbers with the character's STR, DEX and proficiency bonus (D164). */
    WeaponRules.Numbers numbers(PlayerCharacter character, Held held, boolean powerAttack) {
        return WeaponRules.attack(held.weapon(), orTen(character.getStrength()), orTen(character.getDexterity()),
                proficiencyBonus(character), held.proficient(), powerAttack);
    }

    /** The feats among the character's whose attacks can take the trade. */
    Set<PowerAttack> feats(PlayerCharacter character) {
        Set<String> names = new HashSet<>();
        for (JsonNode feature : StoredJson.read(objectMapper, character.getFeatures(), character.getName() + "'s features")) {
            names.add(featKey(feature.isTextual() ? feature.asText() : feature.path("name").asText("")));
        }
        for (JsonNode level : StoredJson.read(objectMapper, character.getLevelHistory(),
                character.getName() + "'s level history")) {
            String featName = level.path("choices").path("asi").path("featName").asText("");
            if (!featName.isBlank()) {
                names.add(featKey(featName));
            }
        }
        Set<PowerAttack> feats = EnumSet.noneOf(PowerAttack.class);
        for (PowerAttack trade : PowerAttack.values()) {
            if (names.contains(featKey(trade.featName()))) {
                feats.add(trade);
            }
        }
        return feats;
    }

    /**
     * Whether a sheet's weapon proficiencies cover the weapon: its category, its name or plural, or for a firearm,
     * firearms. 5etools markup and a "|source" suffix are ignored, and case.
     */
    static boolean proficient(Collection<String> proficiencies, WeaponRules.Weapon weapon, boolean firearm) {
        String name = key(weapon.name());
        for (String proficiency : proficiencies) {
            String text = normalised(proficiency);
            Matcher category = CATEGORY.matcher(text);
            if (category.matches()) {
                if (weapon.category() != null && weapon.category().name().equalsIgnoreCase(category.group(1))) {
                    return true;
                }
            } else if ((firearm && FIREARMS.matcher(text).matches()) || named(text, name)) {
                return true;
            }
        }
        return false;
    }

    // A proficiency or name as it reads without markup, source or case
    static String normalised(String text) {
        String plain = MARKUP.matcher(text).replaceAll("$1");
        int source = plain.indexOf('|');
        if (source >= 0) {
            plain = plain.substring(0, source);
        }
        return key(plain);
    }

    // The base weapon an inventory entry names, singular or plural, or null
    private static Item match(Map<String, Item> weapons, String entryName) {
        String name = normalised(entryName);
        if (name.isEmpty()) {
            return null;
        }
        Item exact = weapons.get(name);
        if (exact != null) {
            return exact;
        }
        return weapons.entrySet().stream()
                .filter(weapon -> named(name, weapon.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    // The weapon an item row describes, when its damage reads as one group of dice or a flat amount
    private Optional<WeaponRules.Weapon> weapon(Item item) {
        Optional<List<Dice>> damage = TemplateDice.read(item.getDamageDice());
        if (damage.isEmpty() || damage.get().size() != 1) {
            return Optional.empty();
        }
        Set<String> tags = tags(item);
        WeaponRules.Category category = "martial".equalsIgnoreCase(item.getSubtype())
                ? WeaponRules.Category.MARTIAL : WeaponRules.Category.SIMPLE;
        return Optional.of(new WeaponRules.Weapon(item.getName(), category, "Ranged Weapon".equals(item.getType()),
                tags.contains("F"), tags.contains("H"), damage.get().getFirst(),
                item.getDamageType() != null ? item.getDamageType().trim().toLowerCase(Locale.ROOT) : null));
    }

    // A firearm's ammunition tag, as the DMG's renaissance, modern and futuristic weapons have
    private boolean firearm(Item item) {
        return tags(item).contains("AF");
    }

    // The 5etools property tags, such as F for finesse, H for heavy and AF for firearm ammunition, without sources
    private Set<String> tags(Item item) {
        Set<String> tags = new HashSet<>();
        for (JsonNode tag : StoredJson.read(objectMapper, item.getProperties(), item.getName() + "'s properties")
                .path("tags")) {
            String text = tag.asText("");
            tags.add((text.contains("|") ? text.substring(0, text.indexOf('|')) : text).trim().toUpperCase(Locale.ROOT));
        }
        return tags;
    }

    private List<String> strings(String json, String what) {
        List<String> strings = new ArrayList<>();
        for (JsonNode entry : StoredJson.read(objectMapper, json, what)) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                strings.add(entry.asText());
            }
        }
        return strings;
    }

    // A weapon's name or its plural, as in "longswords" or "hand crossbows"
    private static boolean named(String text, String name) {
        return text.equals(name) || text.equals(name + "s") || text.equals(name + "es");
    }

    private static String featKey(String name) {
        return key(NOTE.matcher(name).replaceFirst(""));
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static int proficiencyBonus(PlayerCharacter character) {
        if (character.getProficiencyBonus() != null) {
            return character.getProficiencyBonus();
        }
        return ProficiencyBonus.forLevel(character.getLevel() != null ? character.getLevel() : 1);
    }

    private static int orTen(Integer score) {
        return score != null ? score : 10;
    }
}
