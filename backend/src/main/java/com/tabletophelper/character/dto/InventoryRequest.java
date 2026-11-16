package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.InventoryItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A character's whole inventory, which replaces the one it has (D158): each entry needs a name and no fewer than 0 of
 * it, an entry linked to an item names one that exists (D164), and no coin is negative. At most 500 entries, each
 * within InventoryItem's text limits (D221).
 */
public record InventoryRequest(
        @NotNull @Size(max = 500) List<@NotNull @Valid InventoryItem> equipment,
        @NotNull Currency currency
) {
}
