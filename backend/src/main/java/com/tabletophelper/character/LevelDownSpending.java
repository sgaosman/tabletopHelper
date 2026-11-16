package com.tabletophelper.character;

import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelDownUses;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Keeps what a level-down takes away spent, so that gaining the level back doesn't refill it (D222). A pool, slot level
 * or class's Hit Dice that a level-down removes, or whose maximum it lowers, loses the uses spent beyond what the sheet
 * can still hold; they are recorded as a {@link LevelDownUses} for the character level removed, and spent again when
 * that character level is gained back, in whatever class, on the pools, slot levels and Hit Dice that come back with
 * it. A long rest drops every record, since it restores them all, and a short rest the short-rest pools' and pact
 * slots' counts. Package-private and pure.
 */
final class LevelDownSpending {

    private static final String PACT_SLOT = "pact_";
    private static final String SHORT_REST = "shortRest";

    private LevelDownSpending() {}

    /** The sheet's spent counts before a level-down, to compare with after it. */
    record Before(List<ResourcePoolEntry> pools, Map<String, SpellSlot> slots, Map<String, HitDicePool> hitDice) {

        static Before of(PlayerCharacter character) {
            return new Before(List.copyOf(orEmpty(character.getResourcePools())),
                    character.getSpellSlots() != null ? Map.copyOf(character.getSpellSlots()) : Map.of(),
                    character.getHitDice() != null ? Map.copyOf(character.getHitDice()) : Map.of());
        }
    }

    /**
     * Records what the level-down of this character level took away spent, after the Derived Stats were worked out
     * again; a level-down that took nothing spent records nothing.
     */
    static void recordRemoved(PlayerCharacter character, int removedLevel, Before before) {
        Map<String, Integer> pools = new LinkedHashMap<>();
        Map<String, Integer> shortRestPools = new LinkedHashMap<>();
        Map<String, ResourcePoolEntry> after = new LinkedHashMap<>();
        orEmpty(character.getResourcePools()).forEach(pool -> after.put(pool.poolId(), pool));
        for (ResourcePoolEntry pool : before.pools()) {
            if (pool == null || pool.unlimited()) continue;
            ResourcePoolEntry now = after.get(pool.poolId());
            int lost = spent(pool) - (now != null && !now.unlimited() ? spent(now) : 0);
            if (lost > 0) {
                (SHORT_REST.equals(pool.resetOn()) ? shortRestPools : pools).put(pool.poolId(), lost);
            }
        }

        Map<String, SpellSlot> slotsAfter = character.getSpellSlots() != null ? character.getSpellSlots() : Map.of();
        Map<String, Integer> slots = new LinkedHashMap<>();
        before.slots().forEach((key, slot) -> {
            if (slot == null || key.startsWith(PACT_SLOT)) return;
            SpellSlot now = slotsAfter.get(key);
            int lost = slot.used() - (now != null ? now.used() : 0);
            if (lost > 0) slots.put(key, lost);
        });
        int pactLost = pactUsed(before.slots()) - pactUsed(slotsAfter);
        if (pactLost > 0) slots.put(LevelDownUses.PACT, pactLost);

        Map<String, HitDicePool> diceAfter = character.getHitDice() != null ? character.getHitDice() : Map.of();
        Map<String, Integer> hitDice = new LinkedHashMap<>();
        before.hitDice().forEach((className, pool) -> {
            if (pool == null) return;
            HitDicePool now = diceAfter.get(className);
            int lost = spent(pool) - (now != null ? spent(now) : 0);
            if (lost > 0) hitDice.put(className, lost);
        });

        List<LevelDownUses> kept = new ArrayList<>(orEmpty(character.getLevelDownUses()).stream()
                .filter(uses -> uses.characterLevel() != removedLevel)
                .toList());
        LevelDownUses removed = new LevelDownUses(removedLevel, pools, shortRestPools, slots, hitDice);
        if (!removed.isEmpty()) kept.add(removed);
        character.setLevelDownUses(kept);
    }

    /**
     * Spends again what the level-down of this character level took away, on the pools, slot levels and Hit Dice the
     * level gained back gives, after the Derived Stats were worked out again; the record goes, with any for a lower
     * level, which can't come back without passing through this one.
     */
    static void restoreGained(PlayerCharacter character, int gainedLevel) {
        List<LevelDownUses> all = orEmpty(character.getLevelDownUses());
        if (all.isEmpty()) return;
        all.stream().filter(uses -> uses.characterLevel() == gainedLevel).findFirst()
                .ifPresent(uses -> spendAgain(character, uses));
        character.setLevelDownUses(all.stream().filter(uses -> uses.characterLevel() > gainedLevel).toList());
    }

    /** A long rest restores everything a level-down's record keeps spent, so every record goes. */
    static void afterLongRest(PlayerCharacter character) {
        character.setLevelDownUses(List.of());
    }

    /** A short rest restores the short-rest pools and the pact slots, so their counts go from every record. */
    static void afterShortRest(PlayerCharacter character) {
        List<LevelDownUses> kept = new ArrayList<>();
        for (LevelDownUses uses : orEmpty(character.getLevelDownUses())) {
            Map<String, Integer> slots = new LinkedHashMap<>(uses.slots());
            slots.remove(LevelDownUses.PACT);
            LevelDownUses rested = new LevelDownUses(uses.characterLevel(), uses.pools(), Map.of(), slots,
                    uses.hitDice());
            if (!rested.isEmpty()) kept.add(rested);
        }
        character.setLevelDownUses(kept);
    }

    private static void spendAgain(PlayerCharacter character, LevelDownUses uses) {
        Map<String, Integer> poolUses = new LinkedHashMap<>(uses.pools());
        poolUses.putAll(uses.shortRestPools());
        if (!poolUses.isEmpty() && character.getResourcePools() != null) {
            character.setResourcePools(new ArrayList<>(character.getResourcePools().stream()
                    .map(pool -> poolUses.containsKey(pool.poolId()) && !pool.unlimited()
                            ? pool.withCurrentUses(Math.max(0, pool.currentUses() - poolUses.get(pool.poolId())))
                            : pool)
                    .toList()));
        }
        if (!uses.slots().isEmpty() && character.getSpellSlots() != null) {
            Map<String, SpellSlot> slots = new LinkedHashMap<>();
            character.getSpellSlots().forEach((key, slot) -> {
                Integer lost = key.startsWith(PACT_SLOT) ? uses.slots().get(LevelDownUses.PACT) : uses.slots().get(key);
                slots.put(key, slot != null && lost != null
                        ? new SpellSlot(slot.max(), slot.used() + lost, slot.created()).clamped() : slot);
            });
            character.setSpellSlots(slots);
        }
        if (!uses.hitDice().isEmpty() && character.getHitDice() != null) {
            Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
            character.getHitDice().forEach((className, pool) -> {
                Integer lost = uses.hitDice().get(className);
                hitDice.put(className, pool != null && lost != null
                        ? new HitDicePool(pool.dieSize(), pool.total(), Math.max(0, pool.remaining() - lost)) : pool);
            });
            character.setHitDice(hitDice);
        }
    }

    private static int spent(ResourcePoolEntry pool) {
        return Math.max(0, pool.maxUses() - pool.currentUses());
    }

    private static int spent(HitDicePool pool) {
        return Math.max(0, pool.total() - pool.remaining());
    }

    // The pact slots of every level share their used count, as CharacterStats keeps it
    private static int pactUsed(Map<String, SpellSlot> slots) {
        return slots.entrySet().stream()
                .filter(slot -> slot.getKey().startsWith(PACT_SLOT) && slot.getValue() != null)
                .mapToInt(slot -> slot.getValue().used())
                .max().orElse(0);
    }
}
