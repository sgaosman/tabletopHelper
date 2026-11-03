package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.InventoryItem;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * A character's whole inventory, which replaces the one it has (D158): each entry needs a name and no fewer than 0 of
 * it, an entry linked to an item names one that exists (D164), and no coin is negative.
 */
public record InventoryRequest(
        @NotNull List<@NotNull InventoryItem> equipment,
        @NotNull Currency currency
) {
}
