package com.tabletophelper.reference;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemRepositoryIT extends IntegrationTest {

    // ReferenceController passes a one-element sentinel list when a filter is empty
    private static final List<String> NO_FILTER = List.of("");

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void insertItems() {
        insertItem("Longsword", "weapon", "none", "PHB");
        insertItem("Flame Tongue Longsword", "weapon", "rare", "DMG");
        insertItem("Bag of Holding", "wondrous item", "uncommon", "DMG");
        insertItem("Cloak of Elvenkind", "wondrous item", "uncommon", "DMG");
        insertItem("Potion of Healing", "potion", "common", "PHB");
    }

    private void insertItem(String name, String type, String rarity, String source) {
        itemRepository.save(Item.builder().name(name).type(type).rarity(rarity).source(source).build());
    }

    @Test
    @DisplayName("searchItems combines type, rarity and source filters, each accepting several values")
    void searchItemsCombinesFilters() {
        var page = itemRepository.searchItems(null,
                2, List.of("weapon", "wondrous item"), 2, List.of("uncommon", "rare"), 1, List.of("DMG"),
                PageRequest.of(0, 20, Sort.by("name")));

        assertThat(page.getContent()).extracting(Item::getName)
                .containsExactly("Bag of Holding", "Cloak of Elvenkind", "Flame Tongue Longsword");
    }

    @Test
    @DisplayName("searchItems matches a name substring whatever its case, with page counts")
    void searchItemsMatchesNameAndPages() {
        var page = itemRepository.searchItems("LONGSWORD", 0, NO_FILTER, 0, NO_FILTER, 0, NO_FILTER,
                PageRequest.of(0, 1, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Item::getName).containsExactly("Flame Tongue Longsword");
    }
}
