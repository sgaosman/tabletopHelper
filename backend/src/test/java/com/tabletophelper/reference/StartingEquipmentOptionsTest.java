package com.tabletophelper.reference;

import com.tabletophelper.reference.dto.StartingEquipmentResponse;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentCandidate;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentEntry;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.StartingGold;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentGroup;
import com.tabletophelper.reference.dto.StartingEquipmentResponse.EquipmentKind;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A class's and a background's starting equipment (PHB p.143) read from the 5etools shapes the loaders store: the
 * Fighter's and Monk's lists and the Acolyte's, Hermit's and Investigator's, as the data has them.
 */
class StartingEquipmentOptionsTest {

    // The Fighter's defaultData and goldAlternative, as class-fighter.json has them
    private static final String FIGHTER = """
            {"additionalFromBackground": true, "default": [],
             "goldAlternative": "{@dice 5d4 × 10|5d4 × 10|Starting Gold}",
             "defaultData": [
               {"a": ["chain mail|phb"], "b": ["leather armor|phb", "longbow|phb", "arrows (20)|phb"]},
               {"a": [{"equipmentType": "weaponMartial"}, "shield|phb"],
                "b": [{"equipmentType": "weaponMartial", "quantity": 2}]},
               {"a": ["light crossbow|phb", "crossbow bolts (20)|phb"], "b": [{"item": "handaxe|phb", "quantity": 2}]},
               {"a": ["dungeoneer's pack|phb"], "b": ["explorer's pack|phb"]}
             ]}""";

    private static final String MONK = """
            {"goldAlternative": "{@dice 5d4|5d4|Starting Gold}",
             "defaultData": [{"_": [{"item": "dart|phb", "quantity": 10}]}]}""";

    private static final String ACOLYTE = """
            [{"_": [{"item": "holy symbol|phb", "displayName": "holy symbol (a gift to you when you entered the priesthood)"},
                    {"special": "sticks of incense", "quantity": 5}, {"special": "vestments"}, "common clothes|phb",
                    {"item": "pouch|phb", "containsValue": 1500}]},
             {"a": [{"item": "book|phb", "displayName": "prayer book"}], "b": [{"special": "prayer wheel"}]}]""";

    private static final String HERMIT_AND_INVESTIGATOR = """
            [{"_": [{"value": 500}, {"value": 10},
                    {"item": "Horror Trinket|VRGR", "displayName": "evidence (roll for a {@item Horror Trinket|VRGR|Horror Trinkets})"},
                    {"special": "small piece of jewelry", "worthValue": 1000},
                    {"equipmentType": "toolArtisan"}, {"equipmentType": "vehicleLand"}]}]""";

    private final CharacterClassRepository classes = mock(CharacterClassRepository.class);
    private final BackgroundRepository backgrounds = mock(BackgroundRepository.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private StartingEquipmentOptions options;

    private final Item chainMail = item("Chain Mail", "Heavy Armor", null, Item.ArmourCategory.HEAVY, "PHB");
    private final Item leather = item("Leather Armor", "Light Armor", null, Item.ArmourCategory.LIGHT, "PHB");
    private final Item shield = item("Shield", "Shield", null, Item.ArmourCategory.SHIELD, "PHB");
    private final Item longbow = item("Longbow", "Ranged Weapon", "martial", null, "PHB");
    private final Item arrows = item("Arrows (20)", "Ammunition", null, null, "PHB");
    private final Item handaxe = item("Handaxe", "Melee Weapon", "simple", null, "PHB");
    private final Item longsword = item("Longsword", "Melee Weapon", "martial", null, "PHB");
    private final Item adventureSword = item("Longsword of Elsewhere", "Melee Weapon", "martial", null, "XYZ");
    private final Item pouch = item("Pouch", "Adventuring Gear", null, null, "PHB");
    private final Item clothes = item("Common Clothes", "Adventuring Gear", null, null, "PHB");
    private final Item book = item("Book", "Adventuring Gear", null, null, "PHB");
    private final Item trinket = item("Horror Trinket", "Other", null, null, "VRGR");
    private final Item smithsTools = item("Smith's Tools", "Artisan's Tools", null, null, "PHB");
    private final List<Item> all = List.of(chainMail, leather, shield, longbow, arrows, handaxe, longsword,
            adventureSword, pouch, clothes, book, trinket, smithsTools);

    private static Item item(String name, String type, String subtype, Item.ArmourCategory armour, String source) {
        return Item.builder().id(UUID.randomUUID()).name(name).type(type).subtype(subtype).armourCategory(armour)
                .source(source).damageDice(subtype != null ? "1d8" : null).build();
    }

    @BeforeEach
    void setUp() {
        options = new StartingEquipmentOptions(classes, backgrounds, items, JacksonDefaults.mapper());
        when(items.findByLowerNames(any())).thenAnswer(call -> {
            Collection<String> names = call.getArgument(0);
            return all.stream().filter(i -> names.contains(i.getName().toLowerCase(Locale.ROOT))).toList();
        });
        when(items.findMundaneWeapons()).thenReturn(List.of(handaxe, longsword, longbow, adventureSword));
        when(items.findPhbMundaneOfType(anyString())).thenAnswer(call -> all.stream()
                .filter(i -> i.getType().equals(call.getArgument(0))).toList());
    }

    private StartingEquipmentResponse forClass(String json) {
        UUID id = UUID.randomUUID();
        when(classes.findById(id)).thenReturn(Optional.of(CharacterClass.builder().id(id).name("Fighter")
                .startingEquipment(json).build()));
        return options.forClass(id);
    }

    private StartingEquipmentResponse forBackground(String json) {
        UUID id = UUID.randomUUID();
        when(backgrounds.findById(id)).thenReturn(Optional.of(Background.builder().id(id).name("Acolyte")
                .startingEquipment(json).build()));
        return options.forBackground(id);
    }

    private static List<String> names(List<EquipmentEntry> entries) {
        return entries.stream().map(e -> e.quantity() + " " + e.name()).toList();
    }

    @Nested
    @DisplayName("A class")
    class Classes {

        @Test
        @DisplayName("The Fighter's four lines are each a choice of (a) or (b), its items linked, and 5d4 × 10 gp instead")
        void fighter() {
            StartingEquipmentResponse fighter = forClass(FIGHTER);

            assertThat(fighter.groups()).hasSize(4).allMatch(EquipmentGroup::choice);
            EquipmentGroup armour = fighter.groups().getFirst();
            assertThat(armour.options()).extracting(StartingEquipmentResponse.EquipmentOption::key).containsExactly("a", "b");
            EquipmentEntry chain = armour.options().getFirst().entries().getFirst();
            assertThat(chain.kind()).isEqualTo(EquipmentKind.ITEM);
            assertThat(chain.name()).isEqualTo("Chain Mail");
            assertThat(chain.itemId()).isEqualTo(chainMail.getId());
            assertThat(chain.armourCategory()).isEqualTo(Item.ArmourCategory.HEAVY);
            assertThat(names(armour.options().get(1).entries()))
                    .containsExactly("1 Leather Armor", "1 Longbow", "1 Arrows (20)");
            assertThat(fighter.goldAlternative()).isEqualTo(new StartingGold(5, 4, 10));
        }

        @Test
        @DisplayName("\"A martial weapon and a shield or two martial weapons\" offers the PHB's martial weapons to pick, once or twice")
        void martialWeaponPicks() {
            EquipmentGroup weapons = forClass(FIGHTER).groups().get(1);

            List<EquipmentEntry> a = weapons.options().getFirst().entries();
            assertThat(a.getFirst().kind()).isEqualTo(EquipmentKind.PICK);
            assertThat(a.getFirst().name()).isEqualTo("Martial weapon");
            assertThat(a.getFirst().quantity()).isEqualTo(1);
            assertThat(a.getFirst().candidates()).extracting(EquipmentCandidate::name).containsExactly("Longbow", "Longsword");
            assertThat(a.get(1).armourCategory()).isEqualTo(Item.ArmourCategory.SHIELD);
            EquipmentEntry two = weapons.options().get(1).entries().getFirst();
            assertThat(two.kind()).isEqualTo(EquipmentKind.PICK);
            assertThat(two.quantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("Two handaxes keep their quantity, and an item missing from the items table is kept by name, unlinked")
        void quantitiesAndUnknownItems() {
            EquipmentGroup third = forClass(FIGHTER).groups().get(2);

            assertThat(names(third.options().get(1).entries())).containsExactly("2 Handaxe");
            EquipmentEntry bolts = third.options().getFirst().entries().get(1);
            assertThat(bolts.name()).isEqualTo("Crossbow bolts (20)");
            assertThat(bolts.itemId()).isNull();
        }

        @Test
        @DisplayName("A line given whatever is chosen is no choice, and the Monk's 5d4 gp has no multiplier")
        void fixedLineAndPlainGold() {
            StartingEquipmentResponse monk = forClass(MONK);

            assertThat(monk.groups()).singleElement().satisfies(group -> {
                assertThat(group.choice()).isFalse();
                assertThat(group.options().getFirst().key()).isEqualTo("_");
                assertThat(names(group.options().getFirst().entries())).containsExactly("10 Dart");
            });
            assertThat(monk.goldAlternative()).isEqualTo(new StartingGold(5, 4, 1));
        }

        @Test
        @DisplayName("A class without starting equipment has none, and no gold")
        void none() {
            StartingEquipmentResponse none = forClass(null);

            assertThat(none.groups()).isEmpty();
            assertThat(none.goldAlternative()).isNull();
        }

        @Test
        @DisplayName("A class that doesn't exist is a 404, and data that can't be read a 409")
        void errors() {
            assertThatThrownBy(() -> options.forClass(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> forClass("{not json")).isInstanceOf(ConflictException.class)
                    .hasMessage("The starting equipment of Fighter can't be read");
        }
    }

    @Nested
    @DisplayName("A background")
    class Backgrounds {

        @Test
        @DisplayName("The Acolyte's kit: a holy symbol by its description, 5 sticks of incense, vestments, clothes, a pouch of 15 gp, and a prayer book or wheel")
        void acolyte() {
            StartingEquipmentResponse acolyte = forBackground(ACOLYTE);

            assertThat(acolyte.goldAlternative()).isNull();
            EquipmentGroup kit = acolyte.groups().getFirst();
            assertThat(kit.choice()).isFalse();
            List<EquipmentEntry> entries = kit.options().getFirst().entries();
            assertThat(names(entries)).containsExactly(
                    "1 Holy symbol (a gift to you when you entered the priesthood)", "5 Sticks of incense",
                    "1 Vestments", "1 Common Clothes", "1 Pouch");
            assertThat(entries.getFirst().itemId()).isNull();
            assertThat(entries.get(3).itemId()).isEqualTo(clothes.getId());
            assertThat(entries.get(4).coinsCp()).isEqualTo(1500);
            EquipmentGroup prayer = acolyte.groups().get(1);
            assertThat(prayer.choice()).isTrue();
            assertThat(prayer.options().getFirst().entries().getFirst().name()).isEqualTo("Prayer book");
            assertThat(prayer.options().getFirst().entries().getFirst().itemId()).isEqualTo(book.getId());
            assertThat(names(prayer.options().get(1).entries())).containsExactly("1 Prayer wheel");
        }

        @Test
        @DisplayName("Coins, a tagged description, a thing's worth, an artisan's tools pick, and a kind it doesn't know")
        void otherShapes() {
            List<EquipmentEntry> entries = forBackground(HERMIT_AND_INVESTIGATOR).groups().getFirst().options().getFirst()
                    .entries();

            assertThat(entries.get(0).kind()).isEqualTo(EquipmentKind.COINS);
            assertThat(entries.get(0).name()).isEqualTo("5 gp");
            assertThat(entries.get(0).coinsCp()).isEqualTo(500);
            assertThat(entries.get(1).name()).isEqualTo("1 sp");
            assertThat(entries.get(2).name()).isEqualTo("Evidence (roll for a Horror Trinkets)");
            assertThat(entries.get(2).itemId()).isEqualTo(trinket.getId());
            assertThat(entries.get(3).name()).isEqualTo("Small piece of jewelry (worth 10 gp)");
            assertThat(entries.get(4).kind()).isEqualTo(EquipmentKind.PICK);
            assertThat(entries.get(4).name()).isEqualTo("Artisan's tools");
            assertThat(entries.get(4).candidates()).extracting(EquipmentCandidate::itemId).containsExactly(smithsTools.getId());
            assertThat(entries.get(5).kind()).isEqualTo(EquipmentKind.ITEM);
            assertThat(entries.get(5).name()).isEqualTo("VehicleLand");
        }
    }

    @Test
    @DisplayName("Coins read as the largest whole coin")
    void coins() {
        assertThat(StartingEquipmentOptions.coins(1500)).isEqualTo("15 gp");
        assertThat(StartingEquipmentOptions.coins(50)).isEqualTo("5 sp");
        assertThat(StartingEquipmentOptions.coins(7)).isEqualTo("7 cp");
    }
}
