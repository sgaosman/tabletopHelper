package com.tabletophelper.character;

import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a player character brings to an encounter when it joins, read from its sheet (D166): the encounter copies this
 * into the participant rather than reading the sheet itself, and keeps its totals as what the participant joined with,
 * from which Write-back works out what the encounter changed. The hit point maximum, initiative and main spell numbers
 * are the stored Derived Stats, which include the DM's adjustments, and the armour class includes the DM's AC
 * adjustments (D158).
 *
 * @param ownerId the user who plays the character, and so controls its participant
 * @param dead    a dead character can't join until the DM revives it
 * @param totals  the hit points, temporary hit points, slots spent and pool uses left that it joins with
 */
public record CombatSnapshot(
        UUID characterId,
        String name,
        UUID ownerId,
        boolean dead,
        int hpMax,
        int armourClass,
        Integer initiativeBonus,
        Map<String, SpellSlot> spellSlots,
        Integer spellAttackBonus,
        Integer spellSaveDc,
        String spellcastingAbility,
        List<KnownSpell> spellsKnown,
        List<ResourcePoolEntry> resourcePools,
        CombatTotals totals
) {

    /**
     * The character as it would join now. Its hit points are kept between 0 and a maximum of at least 1, each slot's
     * used count between 0 and the slot's maximum, and the lists are copies, so the participant never shares the
     * sheet's.
     */
    public static CombatSnapshot of(PlayerCharacter character) {
        int hpMax = Math.max(1, character.getHpMax() != null ? character.getHpMax() : 1);
        int hp = Math.max(0, Math.min(hpMax, character.getHpCurrent() != null ? character.getHpCurrent() : hpMax));
        int temporary = Math.max(0, character.getHpTemp() != null ? character.getHpTemp() : 0);
        Map<String, SpellSlot> slots = copySpellSlots(character.getSpellSlots());
        List<ResourcePoolEntry> pools = new ArrayList<>(CharacterUpdates.orEmpty(character.getResourcePools()));
        return new CombatSnapshot(character.getId(), character.getName(), character.getUser().getId(),
                character.isDead(), hpMax, DmAdjustments.armourClass(character), character.getInitiativeBonus(), slots,
                character.getSpellAttackBonus(), character.getSpellSaveDc(), character.getSpellcastingAbility(),
                character.getSpellsKnown() != null ? new ArrayList<>(character.getSpellsKnown()) : null, pools,
                CombatTotals.of(hp, temporary, slots, pools));
    }

    // Sheets and participants hold slots in the same {max, used} shape (D168); each used count is copied between 0
    // and the maximum, and a level without a value is left out
    private static Map<String, SpellSlot> copySpellSlots(Map<String, SpellSlot> sheetSlots) {
        if (sheetSlots == null) {
            return null;
        }
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        sheetSlots.forEach((level, slot) -> {
            if (slot != null) {
                slots.put(level, new SpellSlot(slot.max(), Math.max(0, Math.min(slot.max(), slot.used()))));
            }
        });
        return slots;
    }
}
