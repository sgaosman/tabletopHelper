package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * An entry in a character's inventory, {@code player_characters.equipment} (D168). Equipped and attuned replace
 * the separate {@code equipped_items} and {@code attuned_items} lists, which nothing used. A field an entry doesn't
 * have is left out.
 *
 * @param quantity how many are carried; an entry recorded with none left isn't carried
 * @param itemId   the item it is, when linked to the items table (D164)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryItem(
        @Schema(requiredMode = REQUIRED) String name,
        Integer quantity,
        String description,
        UUID itemId,
        Boolean equipped,
        Boolean attuned
) {
}
