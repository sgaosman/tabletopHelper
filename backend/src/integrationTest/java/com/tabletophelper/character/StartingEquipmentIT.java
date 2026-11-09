package com.tabletophelper.character;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting equipment in the creation wizard (PHB p.143): the class's options come from its stored 5etools data with
 * their items linked, the AC preview works the armour out as creation does without saving anything, and a character
 * created with the kit's armour and shield equipped has the AC the preview showed.
 */
class StartingEquipmentIT extends IntegrationTest {

    private static final String FIGHTER_KIT = """
            {"goldAlternative": "{@dice 5d4 × 10|5d4 × 10|Starting Gold}",
             "defaultData": [
               {"a": ["chain mail|phb"], "b": ["leather armor|phb", "longbow|phb", "arrows (20)|phb"]},
               {"a": [{"equipmentType": "weaponMartial"}, "shield|phb"],
                "b": [{"equipmentType": "weaponMartial", "quantity": 2}]}
             ]}""";

    @Autowired private ItemRepository itemRepository;

    private RegisteredUser player;
    private Item chainMail;
    private Item shield;
    private Item longsword;
    private UUID fighter;
    private UUID human;

    @BeforeEach
    void setUp() {
        player = register("aria");
        List<Item> items = itemRepository.saveAll(List.of(
                Item.builder().name("Chain Mail").type("Heavy Armor").source("PHB")
                        .armourCategory(Item.ArmourCategory.HEAVY).armourClassBase(16).strengthRequirement(13)
                        .stealthDisadvantage(true).build(),
                Item.builder().name("Shield").type("Shield").source("PHB")
                        .armourCategory(Item.ArmourCategory.SHIELD).armourClassBase(2).build(),
                Item.builder().name("Longsword").type("Melee Weapon").subtype("martial").damageDice("1d8")
                        .damageType("slashing").source("PHB").build(),
                Item.builder().name("Leather Armor").type("Light Armor").source("PHB")
                        .armourCategory(Item.ArmourCategory.LIGHT).armourClassBase(11).build()));
        chainMail = items.get(0);
        shield = items.get(1);
        longsword = items.get(2);
        fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB").hitDice(10)
                .startingEquipment(FIGHTER_KIT).build()).getId();
        human = insertRace("Human");
    }

    private Map<String, Object> request(List<Map<String, Object>> equipment) {
        Map<String, Object> request = new HashMap<>(Map.of("name", "Aria", "raceId", human, "classId", fighter,
                "strength", 15, "dexterity", 14, "constitution", 12, "intelligence", 10, "wisdom", 10,
                "charisma", 10));
        request.put("armorProficiencies", List.of("light", "medium", "heavy", "shield"));
        request.put("equipment", equipment);
        return request;
    }

    private static Map<String, Object> entry(String name, UUID itemId, boolean equipped) {
        return Map.of("name", name, "quantity", 1, "itemId", itemId, "equipped", equipped);
    }

    @Test
    @DisplayName("The class's options come through the endpoint in order, Chain Mail linked as heavy armour and the martial weapons to pick")
    void classOptions() {
        JsonNode kit = expect(HttpStatus.OK, exchange(HttpMethod.GET,
                "/api/reference/classes/" + fighter + "/starting-equipment", null, player.accessToken()));

        assertThat(kit.get("groups")).hasSize(2);
        JsonNode armour = kit.get("groups").get(0);
        assertThat(armour.get("choice").asBoolean()).isTrue();
        assertThat(armour.get("options").get(0).get("key").asString()).isEqualTo("a");
        JsonNode chain = armour.get("options").get(0).get("entries").get(0);
        assertThat(chain.get("itemId").asString()).isEqualTo(chainMail.getId().toString());
        assertThat(chain.get("armourCategory").asString()).isEqualTo("HEAVY");
        JsonNode pick = kit.get("groups").get(1).get("options").get(0).get("entries").get(0);
        assertThat(pick.get("kind").asString()).isEqualTo("PICK");
        assertThat(pick.get("candidates").get(0).get("itemId").asString()).isEqualTo(longsword.getId().toString());
        assertThat(kit.get("goldAlternative").get("count").asInt()).isEqualTo(5);
        assertThat(kit.get("goldAlternative").get("multiplier").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("The AC preview of Chain Mail and a Shield is 16 + 2 = 18 and saves nothing; the created character has 18")
    void previewThenCreate() {
        List<Map<String, Object>> kit = List.of(entry("Chain Mail", chainMail.getId(), true),
                entry("Longsword", longsword.getId(), false), entry("Shield", shield.getId(), true));

        JsonNode preview = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/armour-class-preview",
                request(kit), player.accessToken()));

        assertThat(preview.get("total").asInt()).isEqualTo(18);
        assertThat(preview.get("parts").toString()).contains("Chain Mail", "Shield");
        assertThat(preview.get("armourEntries").toString()).isEqualTo("[0,2]");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM player_characters", Integer.class)).isZero();

        JsonNode created = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", request(kit),
                player.accessToken()));
        assertThat(created.get("armourClass").asInt()).isEqualTo(18);
        assertThat(created.get("equipment")).hasSize(3);
    }

    @Test
    @DisplayName("Without armour the preview is 10 + DEX, and two equipped suits of armour are a 400")
    void previewWithoutArmourAndTwoSuits() {
        JsonNode bare = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/armour-class-preview",
                request(List.of()), player.accessToken()));
        assertThat(bare.get("total").asInt()).isEqualTo(12);

        Item leather = itemRepository.findAll().stream().filter(i -> i.getName().equals("Leather Armor")).findFirst()
                .orElseThrow();
        JsonNode error = expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST,
                "/api/characters/armour-class-preview", request(List.of(entry("Chain Mail", chainMail.getId(), true),
                        entry("Leather Armor", leather.getId(), true))), player.accessToken()));
        assertThat(error.get("error").asString()).startsWith("Only one suit of armour can be worn at a time");
    }
}
