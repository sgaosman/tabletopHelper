package com.tabletophelper.content.internal;

import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The items loader's armour columns (M13.5), read from the 5etools item files as the app loads them: the category, base
 * AC, Strength requirement, stealth disadvantage and magic bonus of each armour and shield, and none for other items.
 */
class ItemLoaderTest {

    private static Map<String, Item> items;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() {
        ObjectMapper objectMapper = JacksonDefaults.mapper();
        ItemRepository repository = mock(ItemRepository.class);
        when(repository.findAll()).thenReturn(java.util.List.of());
        when(repository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<Item>>getArgument(0)));

        new ItemLoader(repository, objectMapper).load(new LoadContext(objectMapper));

        ArgumentCaptor<Collection<Item>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(repository).saveAll(saved.capture());
        items = saved.getValue().stream().collect(Collectors.toMap(i -> i.getName() + "|" + i.getSource(),
                Function.identity(), (first, later) -> first));
    }

    @Test
    @DisplayName("Plate Armor (PHB p.145) is heavy armour with AC 18, needing STR 15, with stealth disadvantage")
    void plateArmour() {
        Item plate = items.get("Plate Armor|PHB");

        assertThat(plate.getArmourCategory()).isEqualTo(Item.ArmourCategory.HEAVY);
        assertThat(plate.getArmourClassBase()).isEqualTo(18);
        assertThat(plate.getStrengthRequirement()).isEqualTo(15);
        assertThat(plate.getStealthDisadvantage()).isTrue();
        assertThat(plate.getBonusAc()).isNull();
    }

    @Test
    @DisplayName("Light, medium and shield rows: Studded Leather 12, Breastplate 14 without stealth disadvantage, Shield 2")
    void otherCategories() {
        Item studded = items.get("Studded Leather Armor|PHB");
        assertThat(studded.getArmourCategory()).isEqualTo(Item.ArmourCategory.LIGHT);
        assertThat(studded.getArmourClassBase()).isEqualTo(12);
        assertThat(studded.getStrengthRequirement()).isNull();

        Item breastplate = items.get("Breastplate|PHB");
        assertThat(breastplate.getArmourCategory()).isEqualTo(Item.ArmourCategory.MEDIUM);
        assertThat(breastplate.getArmourClassBase()).isEqualTo(14);
        assertThat(breastplate.getStealthDisadvantage()).isFalse();

        Item shield = items.get("Shield|PHB");
        assertThat(shield.getArmourCategory()).isEqualTo(Item.ArmourCategory.SHIELD);
        assertThat(shield.getArmourClassBase()).isEqualTo(2);
    }

    @Test
    @DisplayName("Vanrak's Mithral Shirt, whose data says \"stealth\": false, has no stealth disadvantage, in its column or its properties")
    void vanraksMithralShirt() {
        Item shirt = items.get("Vanrak's Mithral Shirt|WDMM");

        assertThat(shirt.getArmourCategory()).isEqualTo(Item.ArmourCategory.MEDIUM);
        assertThat(shirt.getArmourClassBase()).isEqualTo(13);
        assertThat(shirt.getStealthDisadvantage()).isFalse();
        assertThat(shirt.getProperties()).doesNotContain("stealth_disadvantage");
    }

    @Test
    @DisplayName("A magic armour's bonusAc is stored: Efreeti Chain +3, Elven Chain +1; and a magic shield's: Arrow-Catching Shield +2")
    void magicBonus() {
        Item efreeti = items.get("Efreeti Chain|DMG");
        assertThat(efreeti.getArmourCategory()).isEqualTo(Item.ArmourCategory.HEAVY);
        assertThat(efreeti.getArmourClassBase()).isEqualTo(16);
        assertThat(efreeti.getBonusAc()).isEqualTo(3);
        assertThat(efreeti.getStrengthRequirement()).isEqualTo(13);

        assertThat(items.get("Elven Chain|DMG").getBonusAc()).isEqualTo(1);
        assertThat(items.get("Arrow-Catching Shield|DMG").getBonusAc()).isEqualTo(2);
    }

    @Test
    @DisplayName("A Wand of Magic Missiles (DMG p.211) has 7 charges, regaining 1d6 + 1 at dawn, and casts magic missile for 1 charge")
    void wandOfMagicMissiles() {
        Item wand = items.get("Wand of Magic Missiles|DMG");

        assertThat(wand.getCharges()).isEqualTo(7);
        assertThat(wand.getRecharge()).isEqualTo("dawn");
        assertThat(wand.getRechargeAmount()).isEqualTo("1d6 + 1");
        assertThat(wand.getAttachedSpells()).isEqualTo("{\"charges\":{\"1\":[\"magic missile\"]}}");
        assertThat(wand.getSpellSaveDc()).isNull();
        assertThat(wand.getSpellScrollLevel()).isNull();
    }

    @Test
    @DisplayName("A Wand of Fireballs' own text gives its spell save DC 15 (DMG p.210); a Staff of Fire uses the wielder's")
    void ownSpellNumbers() {
        assertThat(items.get("Wand of Fireballs|DMG").getSpellSaveDc()).isEqualTo(15);
        assertThat(items.get("Wand of Fireballs|DMG").getSpellAttackBonus()).isNull();
        assertThat(items.get("Staff of Fire|DMG").getSpellSaveDc()).isNull();
        assertThat(items.get("Staff of Power|DMG").getSpellSaveDc()).isNull();
        assertThat(items.get("Wand of Binding|DMG").getSpellSaveDc()).isEqualTo(17);
    }

    @Test
    @DisplayName("An item's DC given beside one of its spells counts: the Ring of Animal Influence's list gives 13 (DMG p.191), the Wand of Fear's \"as with the command spell\" 15 (DMG p.210)")
    void dcBesideItsSpells() {
        assertThat(items.get("Ring of Animal Influence|DMG").getSpellSaveDc()).isEqualTo(13);
        assertThat(items.get("Wand of Fear|DMG").getSpellSaveDc()).isEqualTo(15);
    }

    @Test
    @DisplayName("Every item casting spells whose text says \"your spell save DC\" or \"your spell attack bonus\" keeps the wielder's: no DC or attack bonus of its own")
    void wieldersOwnNumbersStayNull() throws java.io.IOException {
        ObjectMapper objectMapper = JacksonDefaults.mapper();
        int checked = 0;
        try (var in = getClass().getResourceAsStream("/data/5etools/items.json")) {
            for (tools.jackson.databind.JsonNode raw : objectMapper.readTree(in).get("item")) {
                if (!raw.has("attachedSpells")) continue;
                String text = raw.path("entries").toString();
                Item item = items.get(raw.get("name").asString() + "|" + raw.get("source").asString());
                if (item == null) continue;
                if (text.contains("your spell save DC")) {
                    assertThat(item.getSpellSaveDc()).as(item.getName()).isNull();
                    checked++;
                }
                if (text.contains("your spell attack bonus")) {
                    assertThat(item.getSpellAttackBonus()).as(item.getName()).isNull();
                }
            }
        }
        assertThat(checked).isGreaterThan(10);
    }

    @Test
    @DisplayName("Spell scrolls keep their level, a Staff of Power its 20 charges regaining 2d8 + 4, and a potion has none of these")
    void scrollsStaffAndPotion() {
        assertThat(items.get("Spell Scroll (3rd Level)|DMG").getSpellScrollLevel()).isEqualTo(3);
        assertThat(items.get("Spell Scroll (Cantrip)|DMG").getSpellScrollLevel()).isZero();

        Item staff = items.get("Staff of Power|DMG");
        assertThat(staff.getCharges()).isEqualTo(20);
        assertThat(staff.getRechargeAmount()).isEqualTo("2d8 + 4");

        Item potion = items.get("Potion of Healing|DMG");
        assertThat(potion.getType()).isEqualTo("Potion");
        assertThat(potion.getCharges()).isNull();
        assertThat(potion.getAttachedSpells()).isNull();
    }

    @Test
    @DisplayName("A recharge amount loses its markup and a plain number becomes text; anything else is null")
    void rechargeAmounts() {
        ObjectMapper mapper = JacksonDefaults.mapper();
        assertThat(ItemLoader.rechargeAmount(mapper.readTree("\"{@dice 1d6 + 4}\""))).isEqualTo("1d6 + 4");
        assertThat(ItemLoader.rechargeAmount(mapper.readTree("3"))).isEqualTo("3");
        assertThat(ItemLoader.rechargeAmount(mapper.readTree("true"))).isNull();
        assertThat(ItemLoader.rechargeAmount(null)).isNull();
    }

    @Test
    @DisplayName("An item that isn't armour has no armour columns, even with a bonusAc: Ring of Protection, Longsword")
    void notArmour() {
        Item ring = items.get("Ring of Protection|DMG");
        assertThat(ring.getArmourCategory()).isNull();
        assertThat(ring.getBonusAc()).isNull();
        assertThat(ring.getStealthDisadvantage()).isFalse();

        Item longsword = items.get("Longsword|PHB");
        assertThat(longsword.getArmourCategory()).isNull();
        assertThat(longsword.getArmourClassBase()).isNull();
    }
}
