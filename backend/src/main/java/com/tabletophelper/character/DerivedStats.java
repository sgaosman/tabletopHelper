package com.tabletophelper.character;

import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.Ability;

import java.util.List;
import java.util.Map;

/**
 * A character's Derived Stats as the rules give them now (D158, D167), worked out from its sheet, its recorded
 * level history and its DM Adjustments. The sheet keeps stored copies of the hit point maximum, proficiency bonus,
 * initiative, speed, armour class (M13.5), slot and Hit Dice maximums and the main spell numbers, which only this
 * derivation writes.
 *
 * @param armourClass          the AC from the armour and shield worn, or the best unarmoured formula, and the flat
 *                             bonuses, before the DM's AC adjustments (M13.5)
 * @param abilityScores        each ability score, with the DM's adjustments to it
 * @param spellSlots           each slot's maximum, with the used count kept up to it; null for a character without
 *                             slots
 * @param hitDice              each class's Hit Dice: its level in the class, with the dice already spent still spent
 * @param spellcasting         what each class casts with
 * @param spellcastingAbility  the main spellcasting ability, which race and feat spells use (D164): the one chosen for
 *                             them at creation, or else the first class's that casts; null for neither
 * @param spellSaveDc          the main save DC, from that ability; null without one
 * @param spellAttackBonus     the main attack bonus, from that ability; null without one
 * @param resourcePools        the pools the character's classes, subclasses, innate spells and feats give, keeping
 *                             their uses
 * @param warnings             what the rules would refuse but the sheet already has, such as more cantrips than the
 *                             class knows or a second armour equipped; nothing changes them
 */
public record DerivedStats(
        int proficiencyBonus,
        int hpMax,
        int initiativeBonus,
        int speed,
        int armourClass,
        Map<Ability, Integer> abilityScores,
        AbilityScoreMaximums abilityScoreMaximums,
        Map<String, SpellSlot> spellSlots,
        Map<String, HitDicePool> hitDice,
        List<ClassSpellcasting> spellcasting,
        String spellcastingAbility,
        Integer spellSaveDc,
        Integer spellAttackBonus,
        List<ResourcePoolEntry> resourcePools,
        List<String> warnings
) {
}
