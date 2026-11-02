package com.tabletophelper;

import com.tabletophelper.rules.Rng;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combat's dice for the integration tier: random, unless a test scripts the next rolls. One bean serves the
 * whole suite, so scripting needs no context of its own; {@link IntegrationTest} clears the script after each
 * test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedDiceConfiguration {

    @Bean
    @Primary
    ScriptedDice scriptedDice() {
        return new ScriptedDice();
    }

    /** Returns the scripted rolls in order, then random ones. A scripted roll the die can't show fails. */
    public static class ScriptedDice implements Rng {

        private final Queue<Integer> script = new ConcurrentLinkedQueue<>();

        public void script(int... rolls) {
            for (int roll : rolls) {
                script.add(roll);
            }
        }

        public void clear() {
            script.clear();
        }

        public int remaining() {
            return script.size();
        }

        @Override
        public int roll(int sides) {
            Integer roll = script.poll();
            if (roll == null) {
                return ThreadLocalRandom.current().nextInt(1, sides + 1);
            }
            if (roll < 1 || roll > sides) {
                throw new IllegalStateException("A d" + sides + " can't roll the scripted " + roll);
            }
            return roll;
        }
    }
}
