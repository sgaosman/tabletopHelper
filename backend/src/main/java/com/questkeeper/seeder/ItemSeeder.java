package com.questkeeper.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questkeeper.reference.Item;
import com.questkeeper.reference.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ItemSeeder {

    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("$", "Treasure"), Map.entry("$A", "Art Object"),
            Map.entry("$C", "Coinage"), Map.entry("$G", "Gemstone"),
            Map.entry("A", "Ammunition"), Map.entry("AF", "Ammunition"),
            Map.entry("AT", "Artisan's Tools"), Map.entry("EXP", "Explosive"),
            Map.entry("FD", "Food and Drink"), Map.entry("G", "Adventuring Gear"),
            Map.entry("GS", "Gaming Set"), Map.entry("GV", "Generic Variant"),
            Map.entry("HA", "Heavy Armor"), Map.entry("INS", "Instrument"),
            Map.entry("LA", "Light Armor"), Map.entry("M", "Melee Weapon"),
            Map.entry("MA", "Medium Armor"), Map.entry("MNT", "Mount"),
            Map.entry("OTH", "Other"), Map.entry("P", "Potion"),
            Map.entry("R", "Ranged Weapon"), Map.entry("RD", "Rod"),
            Map.entry("RG", "Ring"), Map.entry("S", "Shield"),
            Map.entry("SC", "Scroll"), Map.entry("SCF", "Spellcasting Focus"),
            Map.entry("T", "Tool"), Map.entry("TAH", "Tack and Harness"),
            Map.entry("TG", "Trade Good"), Map.entry("WD", "Wand"),
            Map.entry("AIR", "Vehicle (Air)"), Map.entry("SHP", "Vehicle (Water)"),
            Map.entry("VEH", "Vehicle (Land)"), Map.entry("SPC", "Vehicle (Space)")
    );

    private static final Map<String, String> DAMAGE_TYPE_MAP = Map.ofEntries(
            Map.entry("S", "slashing"), Map.entry("P", "piercing"),
            Map.entry("B", "bludgeoning"), Map.entry("N", "necrotic"),
            Map.entry("R", "radiant"), Map.entry("F", "fire"),
            Map.entry("C", "cold"), Map.entry("L", "lightning"),
            Map.entry("T", "thunder"), Map.entry("O", "force"),
            Map.entry("A", "acid"), Map.entry("Y", "psychic")
    );

    public void seed() throws Exception {
        if (itemRepository.count() > 0) {
            log.info("Items already seeded, skipping");
            return;
        }

        List<Item> allItems = new ArrayList<>();

        seedFromFile("data/5etools/items-base.json", "baseitem", allItems);
        seedFromFile("data/5etools/items.json", "item", allItems);

        int total = 0;
        List<Item> batch = new ArrayList<>();
        for (Item item : allItems) {
            batch.add(item);
            total++;
            if (batch.size() >= 100) {
                itemRepository.saveAll(batch);
                batch.clear();
                log.info("Seeding items... {}", total);
            }
        }
        if (!batch.isEmpty()) {
            itemRepository.saveAll(batch);
        }
        log.info("Item seeding complete: {} items", total);
    }

    private void seedFromFile(String path, String rootKey, List<Item> allItems) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode items = root.get(rootKey);
                if (items == null || !items.isArray()) return;

                for (JsonNode i : items) {
                    if (i.has("_copy")) continue;
                    Item item = parseItem(i);
                    if (item != null) allItems.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("Error reading {}: {}", path, e.getMessage());
        }
    }

    private Item parseItem(JsonNode i) {
        try {
            String typeCode = i.path("type").asText("");
            if (typeCode.contains("|")) typeCode = typeCode.split("\\|")[0];
            String type = TYPE_MAP.getOrDefault(typeCode, typeCode);
            if (type.isEmpty()) {
                String altCode = i.path("typeAlt").asText("");
                if (altCode.contains("|")) altCode = altCode.split("\\|")[0];
                if (!altCode.isEmpty()) {
                    type = TYPE_MAP.getOrDefault(altCode, altCode);
                }
            }
            if (type.isEmpty()) {
                String baseItem = i.path("baseItem").asText("").split("\\|")[0].toLowerCase();
                if (!baseItem.isEmpty()) {
                    type = inferTypeFromBaseItem(baseItem);
                }
            }
            if (type.isEmpty()) {
                if (i.path("wondrous").asBoolean(false)) type = "Wondrous item";
                else if (i.path("staff").asBoolean(false)) type = "Staff";
                else if (i.path("weapon").asBoolean(false)) type = "Weapon";
                else if (i.path("armor").asBoolean(false)) type = "Armor";
                else if (i.path("poison").asBoolean(false)) type = "Poison";
            }
            if (type.isEmpty()) {
                type = inferTypeFromDescription(i);
            }
            String rarity = i.path("rarity").asText("none");
            if ("none".equals(rarity)) rarity = null;

            String dmgType = i.has("dmgType") ?
                    DAMAGE_TYPE_MAP.getOrDefault(i.path("dmgType").asText(""), i.path("dmgType").asText("")) : null;

            boolean attunement = i.has("reqAttune") && !i.path("reqAttune").asText("").equals("false");
            String attunementCondition = null;
            if (i.has("reqAttune") && i.get("reqAttune").isTextual()) {
                attunementCondition = i.path("reqAttune").asText();
            }

            return Item.builder()
                    .name(i.path("name").asText())
                    .type(type.isEmpty() ? null : type)
                    .subtype(i.path("weaponCategory").asText(null))
                    .rarity(rarity)
                    .description(flattenEntries(i.get("entries")))
                    .properties(parseProperties(i))
                    .requiresAttunement(attunement)
                    .attunementCondition(attunementCondition)
                    .weight(i.has("weight") ? i.path("weight").asDouble() : null)
                    .cost(parseCost(i))
                    .damageDice(i.path("dmg1").asText(null))
                    .damageType(dmgType)
                    .source(i.path("source").asText(null))
                    .isHomebrew(false)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse item {}: {}", i.path("name").asText("unknown"), e.getMessage());
            return null;
        }
    }

    private String parseProperties(JsonNode i) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            if (i.has("property")) {
                List<String> propList = new ArrayList<>();
                for (JsonNode p : i.get("property")) propList.add(p.asText());
                props.put("tags", propList);
            }
            if (i.has("dmg2")) props.put("versatile_damage", i.path("dmg2").asText());
            if (i.has("range")) props.put("range", i.path("range").asText());
            if (i.has("ac")) props.put("ac", i.path("ac").asInt());
            if (i.has("strength")) props.put("strength_requirement", i.path("strength").asText());
            if (i.has("stealth")) props.put("stealth_disadvantage", true);
            return props.isEmpty() ? null : objectMapper.writeValueAsString(props);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseCost(JsonNode i) {
        if (!i.has("value")) return null;
        int valueInCp = i.path("value").asInt(0);
        if (valueInCp >= 100) return (valueInCp / 100) + " gp";
        if (valueInCp >= 10) return (valueInCp / 10) + " sp";
        return valueInCp + " cp";
    }

    private String flattenEntries(JsonNode entries) {
        if (entries == null) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            if (entry.isTextual()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asText()));
            } else if (entry.isObject()) {
                String type = entry.path("type").asText("");
                if ("entries".equals(type) && entry.has("entries")) {
                    if (sb.length() > 0) sb.append("\n");
                    String name = entry.path("name").asText("");
                    if (!name.isEmpty()) sb.append(name).append(". ");
                    sb.append(flattenEntries(entry.get("entries")));
                } else if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (sb.length() > 0) sb.append("\n");
                        if (item.isTextual()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asText()));
                        }
                    }
                } else if ("table".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("[Table]");
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static final Set<String> WEAPON_BASE_ITEMS = Set.of(
            "dagger", "shortsword", "longsword", "greatsword", "rapier", "scimitar",
            "handaxe", "battleaxe", "greataxe", "warhammer", "maul", "morningstar",
            "flail", "glaive", "halberd", "pike", "lance", "trident", "whip",
            "club", "greatclub", "mace", "quarterstaff", "sickle", "spear",
            "javelin", "light hammer", "war pick",
            "shortbow", "longbow", "hand crossbow", "light crossbow", "heavy crossbow",
            "dart", "sling", "blowgun", "net", "musket", "pistol"
    );

    private String inferTypeFromBaseItem(String baseItem) {
        if (WEAPON_BASE_ITEMS.contains(baseItem)) return "Melee Weapon";
        if (baseItem.contains("bow") || baseItem.contains("crossbow") ||
            baseItem.contains("sling") || baseItem.contains("blowgun") ||
            baseItem.contains("musket") || baseItem.contains("pistol") ||
            baseItem.contains("dart") || baseItem.contains("net")) return "Ranged Weapon";
        if (baseItem.contains("armor") || baseItem.contains("mail") ||
            baseItem.contains("hide") || baseItem.contains("leather") ||
            baseItem.equals("breastplate") || baseItem.equals("half plate") ||
            baseItem.equals("plate") || baseItem.equals("splint")) return "Armor";
        if (baseItem.equals("shield")) return "Shield";
        if (baseItem.equals("rod")) return "Rod";
        if (baseItem.equals("wand")) return "Wand";
        if (baseItem.equals("staff")) return "Staff";
        return "";
    }

    private String inferTypeFromDescription(JsonNode i) {
        JsonNode entries = i.get("entries");
        if (entries == null || !entries.isArray() || entries.isEmpty()) return "";
        String first = entries.get(0).isTextual() ? entries.get(0).asText().toLowerCase() : "";
        if (first.matches(".*\\+\\d (?:dagger|shortsword|longsword|greatsword|rapier|scimitar|battleaxe|greataxe|warhammer|maul|morningstar|flail|glaive|halberd|pike|trident|whip|mace|quarterstaff|spear|handaxe).*")) {
            return "Weapon";
        }
        String rarity = i.path("rarity").asText("");
        if (!rarity.isEmpty() && !"none".equals(rarity)) {
            return "Wondrous item";
        }
        return "";
    }
}
