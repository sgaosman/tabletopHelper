package com.tabletophelper.character;

import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The checks an inventory passes before a character carries it, at creation and on every change (D158): each entry
 * has a name and no fewer than 0 of the thing, an entry linked to an item names one that exists (D164), no more than
 * one body armour and one shield are equipped (M13.5), only linked items that require attunement are attuned, three at
 * most (DMG p.138), only a spell scroll holds a spell, one of its level (D210), and no coin is negative.
 */
@Component
@RequiredArgsConstructor
class InventoryChecks {

    /** The most magic items a creature can be attuned to at once (DMG p.138). */
    static final int MAX_ATTUNED = 3;

    private final ItemRepository itemRepository;
    private final EquippedArmour equippedArmour;
    private final SpellRepository spellRepository;

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
            String spell = entry.spell() != null && !entry.spell().isBlank() ? entry.spell().trim() : null;
            Item linked = Boolean.TRUE.equals(entry.attuned()) || spell != null ? linkedItem(entry) : null;
            if (Boolean.TRUE.equals(entry.attuned())) {
                requireAttunable(name, linked);
            }
            if (spell != null) {
                spell = scrollSpell(name, linked, spell);
            }
            checked.add(new InventoryItem(name, entry.quantity(), entry.description(), entry.itemId(), entry.equipped(),
                    entry.attuned(), spell));
        }
        requireOneOfEach(checked);
        requireAttunementLimit(checked);
        return checked;
    }

    private Item linkedItem(InventoryItem entry) {
        return entry.itemId() != null ? itemRepository.findById(entry.itemId()).orElse(null) : null;
    }

    // Only an item that requires attunement can be attuned to (DMG p.136)
    private static void requireAttunable(String name, Item item) {
        if (item == null) {
            throw new ValidationException("Link " + name + " to its item before attuning to it");
        }
        if (!Boolean.TRUE.equals(item.getRequiresAttunement())) {
            throw new ValidationException(item.getName() + " doesn't require attunement");
        }
    }

    // A creature can be attuned to no more than three magic items at a time (DMG p.138)
    private static void requireAttunementLimit(List<InventoryItem> inventory) {
        long attuned = inventory.stream().filter(entry -> Boolean.TRUE.equals(entry.attuned())).count();
        if (attuned > MAX_ATTUNED) {
            throw new ValidationException("A character can be attuned to no more than " + MAX_ATTUNED
                    + " magic items at a time (DMG p.138), not " + attuned);
        }
    }

    // A spell scroll holds one spell of its own level, a cantrip scroll a cantrip (DMG p.200); the spell's name as the
    // spells table has it
    private String scrollSpell(String name, Item item, String spellName) {
        if (item == null || item.getSpellScrollLevel() == null) {
            throw new ValidationException("Only a spell scroll holds a spell: link " + name + " to its spell scroll");
        }
        Spell spell = spellRepository.findByNameIgnoreCase(spellName)
                .orElseThrow(() -> new ValidationException("There is no spell " + spellName));
        int level = spell.getLevel() != null ? spell.getLevel() : 0;
        if (level != item.getSpellScrollLevel()) {
            throw new ValidationException(item.getName() + " holds a " + (item.getSpellScrollLevel() == 0 ? "cantrip"
                    : "level " + item.getSpellScrollLevel() + " spell") + ", and " + spell.getName() + " is "
                    + (level == 0 ? "a cantrip" : "level " + level));
        }
        return spell.getName();
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
