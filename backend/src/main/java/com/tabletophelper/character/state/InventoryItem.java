package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * An entry in a character's inventory, {@code player_characters.equipment} (D168). Equipped and attuned replace
 * the separate {@code equipped_items} and {@code attuned_items} lists, which nothing used and V29 dropped. A field an entry doesn't
 * have is left out.
 *
 * @param quantity how many are carried; an entry recorded with none left isn't carried
 * @param itemId   the item it is, when linked to the items table (D164)
 * @param attuned  the character is attuned to it, which only an item requiring attunement allows, three at most
 *                 (DMG p.138, D210)
 * @param spell    the spell a spell scroll holds, by its name in the spells table (DMG p.200, D210); null for anything
 *                 else
 * @param chargesSpent the charges spent from the item's pool when the sheet last changed the inventory, kept while the
 *                 item has no pool (unattuned, unlinked or none carried) so getting it back doesn't refill it; the pool
 *                 itself counts while it exists; null for none spent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryItem(
        @Schema(requiredMode = REQUIRED) String name,
        Integer quantity,
        String description,
        UUID itemId,
        Boolean equipped,
        Boolean attuned,
        String spell,
        Integer chargesSpent
) {

    /** An entry with no charges recorded as spent. */
    public InventoryItem(String name, Integer quantity, String description, UUID itemId, Boolean equipped,
                         Boolean attuned, String spell) {
        this(name, quantity, description, itemId, equipped, attuned, spell, null);
    }

    /** An entry that holds no spell, as anything but a spell scroll. */
    public InventoryItem(String name, Integer quantity, String description, UUID itemId, Boolean equipped,
                         Boolean attuned) {
        this(name, quantity, description, itemId, equipped, attuned, null);
    }

    /** How many are carried: an entry without a quantity is one, and one recorded with none left is none. */
    @JsonIgnore
    public int carried() {
        return quantity != null ? Math.max(0, quantity) : 1;
    }

    /** The same entry with these charges recorded as spent; null or 0 for none. */
    public InventoryItem withChargesSpent(Integer spent) {
        return new InventoryItem(name, quantity, description, itemId, equipped, attuned, spell,
                spent != null && spent > 0 ? spent : null);
    }
}
