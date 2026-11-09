package com.tabletophelper.character;

import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The checks an inventory passes before a character carries it, at creation and on every change (D158): each entry
 * has a name and no fewer than 0 of the thing, an entry linked to an item names one that exists (D164), no more than
 * one body armour and one shield are equipped (M13.5), and no coin is negative.
 */
@Component
@RequiredArgsConstructor
class InventoryChecks {

    private final ItemRepository itemRepository;
    private final EquippedArmour equippedArmour;

    /**
     * The entries as the sheet keeps them, their names trimmed.
     *
     * @throws ValidationException for an entry without a name, a quantity below 0, a link to an item that doesn't
     *                             exist, or a second body armour or shield equipped
     */
    List<InventoryItem> checked(List<InventoryItem> equipment) {
        List<InventoryItem> checked = new ArrayList<>();
        for (InventoryItem entry : equipment) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                throw new ValidationException("Every inventory entry needs a name");
            }
            String name = entry.name().trim();
            if (entry.quantity() != null && entry.quantity() < 0) {
                throw new ValidationException("There can't be fewer than 0 of " + name);
            }
            if (entry.itemId() != null && !itemRepository.existsById(entry.itemId())) {
                throw new ValidationException("The item " + name + " is linked to doesn't exist");
            }
            checked.add(new InventoryItem(name, entry.quantity(), entry.description(), entry.itemId(), entry.equipped(),
                    entry.attuned()));
        }
        requireOneOfEach(checked);
        return checked;
    }

    // A character wears one suit of armour and carries one shield at a time (PHB p.144-146)
    private void requireOneOfEach(List<InventoryItem> inventory) {
        Item armour = null;
        Item shield = null;
        for (Item item : equippedArmour.equipped(inventory)) {
            if (item.getArmourCategory() == Item.ArmourCategory.SHIELD) {
                if (shield != null) {
                    throw new ValidationException("Only one shield can be equipped at a time: unequip " + shield.getName()
                            + " before equipping " + item.getName());
                }
                shield = item;
            } else {
                if (armour != null) {
                    throw new ValidationException("Only one suit of armour can be worn at a time: unequip "
                            + armour.getName() + " before equipping " + item.getName());
                }
                armour = item;
            }
        }
    }

    /**
     * The coins, which can't be negative.
     *
     * @throws ValidationException for a negative coin
     */
    static Currency checked(Currency currency) {
        if (currency.cp() < 0 || currency.sp() < 0 || currency.ep() < 0 || currency.gp() < 0 || currency.pp() < 0) {
            throw new ValidationException("A character can't carry fewer than 0 coins of any kind");
        }
        return currency;
    }
}
