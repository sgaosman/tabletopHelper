package com.tabletophelper.character;

import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelDownUses;
import com.tabletophelper.character.state.LevelDownUses.PoolReset;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.character.HitDice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Keeps what a level-down takes away spent, so that gaining the level back doesn't refill it (D222). A pool, slot level
 * or class's Hit Dice that a level-down removes, or whose maximum it lowers, loses the uses spent beyond what the sheet
 * can still hold; they are recorded as a {@link LevelDownUses} for the character level removed, and spent again when
 * that character level is gained back, in whatever class, on the pools, slot levels and Hit Dice that come back with
 * it. So are the hit points the lower maximum would have taken off below 0 (D234). A short rest drops the short-rest
 * pools' and pact slots' counts. A long rest drops the slots, the pools it fills and the hit points, but restores only
 * as many Hit Dice as it would have at the character's full level, and leaves a pool that resets to an amount as the
 * rest would at that level (D234, amending D222). Package-private and pure.
 */
final class LevelDownSpending {

    private static final String PACT_SLOT = "pact_";
    private static final String SHORT_REST = "shortRest";
    private static final String LONG_REST = "longRest";
    private static final Set<String> NO_RESTS = Set.of(ResourcePoolEntry.NO_RESET, "never");

    private LevelDownSpending() {}

    /** The sheet's spent counts and hit points before a level-down or a rest, to compare with after it. */
    record Before(List<ResourcePoolEntry> pools, Map<String, SpellSlot> slots, Map<String, HitDicePool> hitDice,
                  Integer hpCurrent, Integer hpMax, boolean dead) {

        static Before of(PlayerCharacter character) {
            return new Before(List.copyOf(orEmpty(character.getResourcePools())),
                    character.getSpellSlots() != null ? Map.copyOf(character.getSpellSlots()) : Map.of(),
                    character.getHitDice() != null ? Map.copyOf(character.getHitDice()) : Map.of(),
                    character.getHpCurrent(), character.getHpMax(), character.isDead());
        }
    }

    /**
     * Records what the level-down of this character level took away spent, after the Derived Stats were worked out
     * again; a level-down that took nothing spent records nothing.
     */
    static void recordRemoved(PlayerCharacter character, int removedLevel, Before before) {
        Map<String, Integer> pools = new LinkedHashMap<>();
        Map<String, Integer> shortRestPools = new LinkedHashMap<>();
        Map<String, PoolReset> poolResets = new LinkedHashMap<>();
        Map<String, ResourcePoolEntry> after = new LinkedHashMap<>();
        orEmpty(character.getResourcePools()).forEach(pool -> after.put(pool.poolId(), pool));
        for (ResourcePoolEntry pool : before.pools()) {
            if (pool == null || pool.unlimited()) continue;
            ResourcePoolEntry now = after.get(pool.poolId());
            boolean stillThere = now != null && !now.unlimited();
            int lost = spent(pool) - (stillThere ? spent(now) : 0);
            // A pool a long rest resets to an amount is kept even with nothing spent, if the level-down took any of
            // its maximum, since the rest can leave it spent: Power Surge resets to one of three (D234)
            boolean maximumLost = !stillThere || now.maxUses() < pool.maxUses();
            boolean resetsToAmount = resetsToAmount(pool);
            if (lost > 0 || resetsToAmount && maximumLost) {
                (SHORT_REST.equals(pool.resetOn()) ? shortRestPools : pools).put(pool.poolId(), Math.max(0, lost));
                if (resetsToAmount || NO_RESTS.contains(pool.resetOn())) {
                    poolResets.put(pool.poolId(), new PoolReset(pool.maxUses(), pool.resetOn(), pool.resetAmount()));
                }
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
        Map<String, Integer> hitDieSizes = new LinkedHashMap<>();
        before.hitDice().forEach((className, pool) -> {
            if (pool == null) return;
            HitDicePool now = diceAfter.get(className);
            int lost = spent(pool) - (now != null ? spent(now) : 0);
            if (lost > 0) {
                hitDice.put(className, lost);
                hitDieSizes.put(className, pool.dieSize());
            }
        });

        LevelDownUses removed = new LevelDownUses(removedLevel, pools, shortRestPools, slots, hitDice, hitDieSizes,
                poolResets, hpShortfall(character, before));
        List<LevelDownUses> kept = new ArrayList<>(orEmpty(character.getLevelDownUses()).stream()
                .filter(uses -> uses.characterLevel() != removedLevel)
                .toList());
        if (!removed.isEmpty()) kept.add(removed);
        character.setLevelDownUses(kept);
    }

    /**
     * Spends again what the level-down of this character level took away, on the pools, slot levels and Hit Dice the
     * level gained back gives, and takes off again the hit points the lower maximum couldn't, after the Derived Stats
     * were worked out again; the record goes, with any for a lower level, which can't come back without passing
     * through this one.
     */
    static void restoreGained(PlayerCharacter character, int gainedLevel) {
        List<LevelDownUses> all = orEmpty(character.getLevelDownUses());
        if (all.isEmpty()) return;
        all.stream().filter(uses -> uses.characterLevel() == gainedLevel).findFirst()
                .ifPresent(uses -> spendAgain(character, uses));
        character.setLevelDownUses(all.stream().filter(uses -> uses.characterLevel() > gainedLevel).toList());
    }

    /**
     * After a long rest (D234, amending D222, which dropped every record). The rest fills every slot, every pool but
     * those that reset to an amount and restores every hit point, so those counts go. But it restores only half the
     * character's Hit Dice, which at the lower level is fewer than at the character's full level, the highest level a
     * record keeps: the records keep the dice the rest would have left spent at that level beyond those the sheet
     * still has spent, the largest dice restored first, and the records of the highest levels give theirs up first; a
     * multiclass sheet's own dice are moved to match where the two rests restored different classes. A
     * pool that resets to an amount, such as Power Surge's one, is kept as spent as the rest would leave it at its
     * maximum before the level-down; one no rest restores keeps its count.
     *
     * @param beforeRest     the sheet before the rest
     * @param longRestUses   the uses a pool has after a long rest, by its reset amount
     */
    static void afterLongRest(PlayerCharacter character, Before beforeRest,
                              ToIntFunction<ResourcePoolEntry> longRestUses) {
        List<LevelDownUses> records = orEmpty(character.getLevelDownUses());
        if (records.isEmpty()) return;
        // Highest level first: the records that give up what the rest restores first
        List<LevelDownUses> highestFirst = records.stream()
                .sorted(Comparator.comparingInt(LevelDownUses::characterLevel).reversed())
                .toList();
        Map<Integer, Map<String, Integer>> hitDice = keptHitDice(character, beforeRest, highestFirst);
        Map<Integer, Map<String, Integer>> pools = keptPools(character, beforeRest, highestFirst, longRestUses);

        List<LevelDownUses> kept = new ArrayList<>();
        for (LevelDownUses uses : records) {
            Map<String, Integer> recordPools = pools.getOrDefault(uses.characterLevel(), Map.of());
            Map<String, PoolReset> resets = new LinkedHashMap<>(uses.poolResets());
            resets.keySet().retainAll(recordPools.keySet());
            // A pool that resets to an amount is kept at 0 too, since a later long rest can leave it spent; Hit Dice
            // at 0 go
            Map<String, Integer> recordDice = new LinkedHashMap<>(hitDice.getOrDefault(uses.characterLevel(),
                    Map.of()));
            recordDice.values().removeIf(count -> count == 0);
            Map<String, Integer> sizes = new LinkedHashMap<>(uses.hitDieSizes());
            sizes.keySet().retainAll(recordDice.keySet());
            LevelDownUses rested = new LevelDownUses(uses.characterLevel(), recordPools, Map.of(), Map.of(),
                    recordDice, sizes, resets, 0);
            if (!rested.isEmpty()) kept.add(rested);
        }
        character.setLevelDownUses(kept);
    }

    /** A short rest restores the short-rest pools and the pact slots, so their counts go from every record. */
    static void afterShortRest(PlayerCharacter character) {
        List<LevelDownUses> kept = new ArrayList<>();
        for (LevelDownUses uses : orEmpty(character.getLevelDownUses())) {
            Map<String, Integer> slots = new LinkedHashMap<>(uses.slots());
            slots.remove(LevelDownUses.PACT);
            LevelDownUses rested = new LevelDownUses(uses.characterLevel(), uses.pools(), Map.of(), slots,
                    uses.hitDice(), uses.hitDieSizes(), uses.poolResets(), uses.hpShortfall());
            if (!rested.isEmpty()) kept.add(rested);
        }
        character.setLevelDownUses(kept);
    }

    // ── Long rest (D234) ─────────────────────────────────────────

    // The Hit Dice each record keeps after a long rest, by level and class
    private static Map<Integer, Map<String, Integer>> keptHitDice(PlayerCharacter character, Before beforeRest,
                                                                   List<LevelDownUses> highestFirst) {
        Map<String, Integer> recorded = new LinkedHashMap<>();
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (LevelDownUses uses : highestFirst) {
            uses.hitDice().forEach((className, count) -> recorded.merge(className, count, Integer::sum));
            uses.hitDieSizes().forEach(sizes::putIfAbsent);
        }
        if (recorded.isEmpty()) return Map.of();
        beforeRest.hitDice().forEach((className, pool) -> {
            if (pool != null) sizes.put(className, pool.dieSize());
        });

        // At the full level: the sheet's spent dice and the recorded ones, of which the rest restores half the
        // character's dice, at least one, the largest first
        int fullLevel = Math.max(character.getLevel() != null ? character.getLevel() : 1,
                highestFirst.getFirst().characterLevel());
        Set<String> classes = new LinkedHashSet<>(beforeRest.hitDice().keySet());
        classes.addAll(recorded.keySet());
        Map<String, Integer> fullSpent = new LinkedHashMap<>();
        for (String className : classes) {
            HitDicePool pool = beforeRest.hitDice().get(className);
            fullSpent.put(className, (pool != null ? spent(pool) : 0) + recorded.getOrDefault(className, 0));
        }
        int recovery = HitDice.longRestRecovery(fullLevel);
        Map<String, Integer> spentAfter = new LinkedHashMap<>(fullSpent);
        for (String className : classes.stream()
                .sorted(Comparator.comparingInt((String c) -> sizes.getOrDefault(c, 0)).reversed()).toList()) {
            int restored = Math.min(recovery, spentAfter.get(className));
            spentAfter.put(className, spentAfter.get(className) - restored);
            recovery -= restored;
        }

        // The sheet keeps what its own rest left spent where it can, and the records the rest of what the full level
        // leaves spent, so the two add up to the full level's rest. Where the full level's rest restores a class's
        // dice that the sheet's didn't, because it restored the larger dice the records hold, the sheet gives them
        // back too; and where the sheet's rest restored dice the full level's didn't, it takes them back, so a
        // multiclass character's round trip ends as a rest at its full level would
        Map<String, HitDicePool> sheetAfter = character.getHitDice() != null ? character.getHitDice() : Map.of();
        Map<String, HitDicePool> sheet = new LinkedHashMap<>();
        Map<String, Integer> keep = new LinkedHashMap<>();
        sheetAfter.forEach((className, pool) -> {
            if (pool == null) {
                sheet.put(className, null);
                return;
            }
            int full = spentAfter.getOrDefault(className, 0);
            int held = recorded.getOrDefault(className, 0);
            int sheetSpent = Math.clamp(Math.max(spent(pool), full - held), 0, Math.min(full, pool.total()));
            sheet.put(className, new HitDicePool(pool.dieSize(), pool.total(), pool.total() - sheetSpent));
            if (held > 0) keep.put(className, Math.clamp(full - sheetSpent, 0, held));
        });
        recorded.forEach((className, count) -> {
            if (!sheetAfter.containsKey(className)) keep.put(className, Math.clamp(spentAfter.get(className), 0, count));
        });
        if (character.getHitDice() != null) character.setHitDice(sheet);
        return distribute(highestFirst, LevelDownUses::hitDice, keep);
    }

    // The pool uses each record keeps after a long rest, by level and pool: only those a long rest doesn't fill
    private static Map<Integer, Map<String, Integer>> keptPools(PlayerCharacter character, Before beforeRest,
                                                                 List<LevelDownUses> highestFirst,
                                                                 ToIntFunction<ResourcePoolEntry> longRestUses) {
        Map<String, Integer> recorded = new LinkedHashMap<>();
        Map<String, PoolReset> resets = new LinkedHashMap<>();
        for (LevelDownUses uses : highestFirst) {
            uses.pools().forEach((poolId, count) -> {
                if (uses.poolResets().containsKey(poolId)) recorded.merge(poolId, count, Integer::sum);
            });
            // The highest level's record has the pool's maximum at the full level
            uses.poolResets().forEach(resets::putIfAbsent);
        }
        if (recorded.isEmpty()) return Map.of();
        Map<String, ResourcePoolEntry> sheetBefore = new LinkedHashMap<>();
        beforeRest.pools().forEach(pool -> sheetBefore.put(pool.poolId(), pool));
        Map<String, ResourcePoolEntry> sheetAfter = new LinkedHashMap<>();
        orEmpty(character.getResourcePools()).forEach(pool -> sheetAfter.put(pool.poolId(), pool));

        Map<String, Integer> keep = new LinkedHashMap<>();
        recorded.forEach((poolId, count) -> {
            PoolReset reset = resets.get(poolId);
            if (NO_RESTS.contains(reset.resetOn())) {
                // A pool no rest restores keeps its count
                keep.put(poolId, count);
                return;
            }
            ResourcePoolEntry before = sheetBefore.get(poolId);
            ResourcePoolEntry after = sheetAfter.get(poolId);
            int fullSpent = Math.min(reset.maxUses(), (before != null ? spent(before) : 0) + count);
            ResourcePoolEntry full = new ResourcePoolEntry(poolId, poolId, "CLASS", null, reset.maxUses(), null,
                    reset.maxUses() - fullSpent, LONG_REST, reset.resetAmount(), null, null, null, null);
            int fullSpentAfter = reset.maxUses() - Math.clamp(longRestUses.applyAsInt(full), 0, reset.maxUses());
            keep.put(poolId, Math.max(0, fullSpentAfter - (after != null ? spent(after) : 0)));
        });
        return distribute(highestFirst, LevelDownSpending::poolsWithResets, keep);
    }

    // The record's pools that a long rest doesn't simply fill
    private static Map<String, Integer> poolsWithResets(LevelDownUses uses) {
        Map<String, Integer> pools = new LinkedHashMap<>(uses.pools());
        pools.keySet().retainAll(uses.poolResets().keySet());
        return pools;
    }

    // Shares each total among the records that keep that key: the highest level's record gives up first what the
    // total is short of their sum, and takes what it is over, by level and key
    private static Map<Integer, Map<String, Integer>> distribute(List<LevelDownUses> highestFirst,
                                                                 Function<LevelDownUses, Map<String, Integer>> counts,
                                                                 Map<String, Integer> totals) {
        Map<Integer, Map<String, Integer>> shared = new LinkedHashMap<>();
        totals.forEach((key, total) -> {
            List<LevelDownUses> holding = highestFirst.stream()
                    .filter(uses -> counts.apply(uses).containsKey(key)).toList();
            int sum = holding.stream().mapToInt(uses -> counts.apply(uses).get(key)).sum();
            int giveUp = Math.max(0, sum - total);
            int extra = Math.max(0, total - sum);
            for (LevelDownUses uses : holding) {
                int count = counts.apply(uses).get(key) + extra;
                extra = 0;
                int given = Math.min(giveUp, count);
                giveUp -= given;
                shared.computeIfAbsent(uses.characterLevel(), level -> new LinkedHashMap<>())
                        .put(key, count - given);
            }
        });
        return shared;
    }

    // ── Spending again ───────────────────────────────────────────

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
        // The level's raise of the maximum gave back the hit points its removal couldn't take, so they come off again,
        // never below 0 (D234, amending D222)
        if (uses.hpShortfall() > 0 && character.getHpCurrent() != null && !character.isDead()) {
            character.setHpCurrent(Math.max(0, character.getHpCurrent() - uses.hpShortfall()));
        }
    }

    // The hit points the level-down's lower maximum would have taken off below 0 (D234): a Fighter at 2 of 30 who
    // loses a 7-point level would be at -5, so 5 are recorded. A dead character's stay at 0 whatever the maximum does
    private static int hpShortfall(PlayerCharacter character, Before before) {
        if (before.dead() || before.hpCurrent() == null || before.hpMax() == null || character.getHpMax() == null) {
            return 0;
        }
        int fallen = before.hpMax() - character.getHpMax();
        return Math.max(0, fallen - before.hpCurrent());
    }

    private static boolean resetsToAmount(ResourcePoolEntry pool) {
        return LONG_REST.equals(pool.resetOn()) && pool.resetAmount() != null && !pool.resetAmount().isBlank();
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
