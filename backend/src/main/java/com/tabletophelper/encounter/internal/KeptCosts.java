package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.ItemUse;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.TurnEconomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a creature keeps when a command of its is taken back inside another, because it did act (D196, D216, D218):
 * Counterspell takes back a spell that "has no effect", and Shield takes back a hit that became a miss, but the spell
 * was cast and the attack made. Both read what to keep here, so the two can't drift apart.
 * <ul>
 *   <li>Always: spell slots, resource pools (sorcery points, item charges), the turn economy and the items used (a
 *       scroll read or a potion drunk stays used up, D218).</li>
 *   <li>For an attack taken back by Shield ({@link Kind#ATTACK}), also its conditions, its Spell Effects and its
 *       concentration, as the attack left them: invisibility that ended as it attacked stays ended, a one-shot rider
 *       spent on the roll, as True Strike's advantage or a smite, stays spent, and concentration ended by casting the
 *       attack's spell stays ended. {@link ReactionAnswers} first refuses a take-back where any of these changed
 *       because of the wizard, so keeping them never keeps a part of the hit.</li>
 *   <li>A countered spell ({@link Kind#SPELL}) keeps its caster's conditions, Spell Effects and concentration as
 *       they were before it, since the spell has no effect (D196's rules call: the caster keeps its previous
 *       concentration).</li>
 * </ul>
 */
final class KeptCosts {

    /** What the creature did that is taken back. */
    enum Kind {
        /** A spell that was countered (D196). */
        SPELL,
        /** An attack whose hit Shield turned into a miss (D216). */
        ATTACK
    }

    private final Kind kind;
    private final Map<String, SpellSlot> slots;
    private final List<ResourcePoolEntry> pools;
    private final TurnEconomy economy;
    private final List<ItemUse> itemsUsed;
    private final List<ConditionEntry> conditions;
    private final List<SpellEffectEntry> effects;
    private final ConcentrationInstance concentration;

    private KeptCosts(Kind kind, EncounterParticipant creature) {
        this.kind = kind;
        this.slots = creature.getSpellSlotsCurrent() != null ? new LinkedHashMap<>(creature.getSpellSlotsCurrent()) : null;
        this.pools = copy(creature.getResourcePoolsCurrent());
        this.economy = creature.getTurnEconomy();
        this.itemsUsed = copy(creature.getItemsUsed());
        this.conditions = copy(creature.getActiveConditions());
        this.effects = copy(creature.getActiveEffects());
        this.concentration = creature.getConcentration();
    }

    /** What the creature has now, read before its command is taken back. */
    static KeptCosts of(Kind kind, EncounterParticipant creature) {
        return new KeptCosts(kind, creature);
    }

    /** Puts back on the creature, its command now taken back, what it keeps. */
    void putBack(EncounterParticipant creature) {
        creature.setSpellSlotsCurrent(slots != null ? new LinkedHashMap<>(slots) : null);
        creature.setResourcePoolsCurrent(copy(pools));
        creature.setTurnEconomy(economy);
        creature.setItemsUsed(copy(itemsUsed));
        if (kind == Kind.ATTACK) {
            creature.setActiveConditions(copy(conditions));
            creature.setActiveEffects(copy(effects));
            creature.setConcentration(concentration);
        }
    }

    private static <T> List<T> copy(List<T> list) {
        return list != null ? new ArrayList<>(list) : null;
    }
}
