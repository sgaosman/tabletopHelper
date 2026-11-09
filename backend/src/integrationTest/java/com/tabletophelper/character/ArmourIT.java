package com.tabletophelper.character;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Armour and Armour Class end to end (M13.5): the inventory command equips armour, the sheet's AC is derived from it on
 * every command and stored, and the response shows how it adds up and what the armour costs. The armour rows are the
 * PHB's, as ItemLoader writes them.
 */
class ArmourIT extends IntegrationTest {

    private static final Map<String, Object> NO_COINS = Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0);

    @Autowired private ItemRepository itemRepository;

    private RegisteredUser player;
    private Item chainMail;
    private Item leather;

    @BeforeEach
    void setUpArmoury() {
        player = register("aria");
        List<Item> items = itemRepository.saveAll(List.of(
                Item.builder().name("Chain Mail").type("Heavy Armor").source("PHB")
                        .armourCategory(Item.ArmourCategory.HEAVY).armourClassBase(16).strengthRequirement(13)
                        .stealthDisadvantage(true).build(),
                Item.builder().name("Shield").type("Shield").source("PHB")
                        .armourCategory(Item.ArmourCategory.SHIELD).armourClassBase(2).build(),
                Item.builder().name("Leather Armor").type("Light Armor").source("PHB")
                        .armourCategory(Item.ArmourCategory.LIGHT).armourClassBase(11).build()));
        chainMail = items.get(0);
        leather = items.get(2);
    }

    // A fighter with STR 15 and DEX 14, or a wizard with STR 10 and no armour proficiency
    private UUID create(String name, String className, int strength, List<String> armour) {
        Map<String, Object> request = new HashMap<>(Map.of("name", name, "raceId", insertRace("Human"),
                "classId", insertClass(className, 10), "strength", strength, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10));
        request.put("armorProficiencies", armour);
        return UUID.fromString(expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", request,
                player.accessToken())).get("id").asString());
    }

    private JsonNode get(UUID character) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + character, null,
                player.accessToken()));
    }

    private static List<String> parts(JsonNode character) {
        List<String> parts = new ArrayList<>();
        for (JsonNode part : character.get("armourClassBreakdown").get("parts")) {
            parts.add(part.get("label").asString() + " " + part.get("amount").asInt());
        }
        return parts;
    }

    private static Map<String, Object> entry(String name, UUID itemId, boolean equipped) {
        Map<String, Object> entry = new HashMap<>(Map.of("name", name, "quantity", 1, "equipped", equipped));
        if (itemId != null) {
            entry.put("itemId", itemId);
        }
        return entry;
    }

    @Test
    @DisplayName("Equipping Chain Mail, linked to its item, and a Shield, by name, takes the AC from 10 + DEX 2 to 16 + 2 = 18, stored and shown with its parts")
    void chainMailAndShield() {
        UUID fighter = create("Aria", "Fighter", 15, List.of("light", "medium", "heavy", "shield"));
        JsonNode before = get(fighter);
        assertThat(before.get("armourClass").asInt()).isEqualTo(12);
        assertThat(parts(before)).containsExactly("Unarmoured 10", "DEX 2");

        JsonNode response = expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + fighter + "/inventory",
                Map.of("equipment", List.of(entry("Chain Mail", chainMail.getId(), true), entry("Shield", null, true),
                        entry("Leather Armor", leather.getId(), false)), "currency", NO_COINS),
                player.accessToken()));

        assertThat(response.get("armourClass").asInt()).isEqualTo(18);
        JsonNode after = get(fighter);
        assertThat(after.get("armourClass").asInt()).isEqualTo(18);
        assertThat(parts(after)).containsExactly("Chain Mail 16", "Shield 2");
        assertThat(after.get("armourClassBreakdown").get("total").asInt()).isEqualTo(18);
        assertThat(after.get("armourClassBreakdown").get("warnings")).isEmpty();
        assertThat(after.get("armourClassBreakdown").get("armourEntries").toString()).isEqualTo("[0,1,2]");
        assertThat(after.get("speed").asInt()).isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject("SELECT armour_class FROM player_characters WHERE id = ?",
                Integer.class, fighter)).isEqualTo(18);
    }

    @Test
    @DisplayName("Equipping a second armour is a 400 that changes nothing")
    void secondArmourRefused() {
        UUID fighter = create("Aria", "Fighter", 15, List.of("light", "medium", "heavy", "shield"));

        JsonNode error = expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.PUT,
                "/api/characters/" + fighter + "/inventory", Map.of("equipment", List.of(
                        entry("Chain Mail", chainMail.getId(), true), entry("Leather Armor", leather.getId(), true)),
                        "currency", NO_COINS), player.accessToken()));

        assertThat(error.get("error").asString()).isEqualTo(
                "Only one suit of armour can be worn at a time: unequip Chain Mail before equipping Leather Armor");
        JsonNode unchanged = get(fighter);
        assertThat(unchanged.get("armourClass").asInt()).isEqualTo(12);
        assertThat(unchanged.get("equipment")).isEmpty();
    }

    @Test
    @DisplayName("A wizard in Chain Mail without proficiency or the Strength for it is warned of both, and slowed to 20 feet")
    void notProficientAndTooWeak() {
        UUID wizard = create("Mira", "Wizard", 10, List.of());

        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + wizard + "/inventory",
                Map.of("equipment", List.of(entry("Chain Mail", chainMail.getId(), true)), "currency", NO_COINS),
                player.accessToken()));

        JsonNode sheet = get(wizard);
        assertThat(sheet.get("armourClass").asInt()).isEqualTo(16);
        assertThat(sheet.get("speed").asInt()).isEqualTo(20);
        List<String> warnings = new ArrayList<>();
        sheet.get("armourClassBreakdown").get("warnings").forEach(warning -> warnings.add(warning.asString()));
        assertThat(warnings).containsExactly(
                "Not proficient with Chain Mail: disadvantage on STR and DEX rolls, and can't cast spells",
                "Chain Mail needs STR 13: speed -10 ft");
    }
}
