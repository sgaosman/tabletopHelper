package com.tabletophelper.rules.character;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Hit Dice and what a long rest restores of them (PHB p.186). A character has one Hit Die per level, of its class's
 * size, kept as a pool for each size or class.
 */
public final class HitDice {

    /** A character's Hit Dice of one size: how many it has, and how many of those are left to spend. */
    public record Pool(int dieSize, int total, int remaining) {
        public Pool {
            requireHitDie(dieSize);
            if (total < 0 || remaining < 0 || remaining > total) {
                throw new IllegalArgumentException("Impossible Hit Dice: " + remaining + " of " + total + "d" + dieSize);
            }
        }
    }

    private HitDice() {}

    /** The most spent Hit Dice a long rest restores: half the character's total, rounded down, and at least one. */
    public static int longRestRecovery(int totalDice) {
        return Math.max(1, totalDice / 2);
    }

    /**
     * The pools after a long rest, in the order given: spent dice come back up to {@link #longRestRecovery} of the
     * character's total, the largest dice first (D158), and pools of the same size in the order given.
     */
    public static List<Pool> afterLongRest(List<Pool> pools) {
        int recovery = longRestRecovery(pools.stream().mapToInt(Pool::total).sum());
        List<Pool> after = new ArrayList<>(pools);
        List<Integer> largestFirst = IntStream.range(0, pools.size()).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> pools.get(i).dieSize()).reversed())
                .toList();
        for (int i : largestFirst) {
            Pool pool = pools.get(i);
            int restored = Math.min(recovery, pool.total() - pool.remaining());
            after.set(i, new Pool(pool.dieSize(), pool.total(), pool.remaining() + restored));
            recovery -= restored;
        }
        return List.copyOf(after);
    }

    /** Fails unless the size is a d6, d8, d10 or d12, the only Hit Dice classes give. */
    static void requireHitDie(int dieSize) {
        if (dieSize != 6 && dieSize != 8 && dieSize != 10 && dieSize != 12) {
            throw new IllegalArgumentException("A Hit Die is a d6, d8, d10 or d12, not a d" + dieSize);
        }
    }
}
