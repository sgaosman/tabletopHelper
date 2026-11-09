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
