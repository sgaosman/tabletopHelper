package com.tabletophelper.rules;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where dice get their numbers. Code that rolls takes an Rng, so a test can script its dice:
 * {@link #random()} in play, {@link #scripted} in tests.
 */
@FunctionalInterface
public interface Rng {

    /** One roll of a die with the given number of sides: a whole number from 1 to sides. */
    int roll(int sides);

    /** Uniformly random rolls. */
    static Rng random() {
        return sides -> ThreadLocalRandom.current().nextInt(1, sides + 1);
    }

    /** Returns the given rolls in order, whatever die each is used for. */
    static Scripted scripted(int... rolls) {
        return new Scripted(rolls);
    }

    /**
     * Rolls fixed in advance. Running out of rolls, or a roll that can't come up on the die it is used for,
     * throws, so a test notices dice it didn't expect.
     */
    final class Scripted implements Rng {

        private final Queue<Integer> rolls = new ConcurrentLinkedQueue<>();

        private Scripted(int... rolls) {
            for (int roll : rolls) {
                this.rolls.add(roll);
            }
        }

        @Override
        public int roll(int sides) {
            Integer roll = rolls.poll();
            if (roll == null) {
                throw new IllegalStateException("No scripted roll left for a d" + sides);
            }
            if (roll < 1 || roll > sides) {
                throw new IllegalStateException("A d" + sides + " can't roll the scripted " + roll);
            }
            return roll;
        }

        /** How many scripted rolls are still unused. */
        public int remaining() {
            return rolls.size();
        }
    }
}
