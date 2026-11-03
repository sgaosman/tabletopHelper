package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.HitDice.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resting under the 2014 rules (PHB p.186), typed out here independently of {@link Rests} and {@link HitDice}: a Hit
 * Die spent on a short rest heals its roll plus the Constitution modifier, never less than 0, and a long rest
 * restores spent Hit Dice up to half the character's total, at least one, the largest dice first (D158).
 */
class RestRulesTest {

    @ParameterizedTest(name = "d{0} rolling {1} with CON {2}: {3} HP")
    @CsvSource({
            "8, 5, 2, 7",
            "10, 10, 0, 10",
            "12, 1, 3, 4",
            "6, 4, -1, 3",
            // A roll that the modifier would take below 0 heals nothing
            "6, 1, -3, 0",
            "8, 2, -5, 0",
    })
    @DisplayName("A Hit Die spent on a short rest heals the roll plus the Constitution modifier, and never less than 0")
    void shortRestHealing(int hitDie, int roll, int constitutionModifier, int healed) {
        assertEquals(healed, Rests.hitDieHealing(hitDie, roll, constitutionModifier));
    }

    @ParameterizedTest(name = "d{0} rolling {1}")
    @CsvSource({"8, 0", "8, 9", "6, -1", "12, 13"})
    @DisplayName("A Hit Die roll outside 1 to the die's size is refused")
    void impossibleRoll(int hitDie, int roll) {
        assertThrows(IllegalArgumentException.class, () -> Rests.hitDieHealing(hitDie, roll, 0));
    }

    @ParameterizedTest(name = "{0} Hit Dice: up to {1} back")
    @CsvSource({"1, 1", "2, 1", "3, 1", "4, 2", "5, 2", "8, 4", "11, 5", "20, 10"})
    @DisplayName("A long rest restores up to half the character's Hit Dice, rounded down, and at least one")
    void longRestRecovery(int totalDice, int recovered) {
        assertEquals(recovered, HitDice.longRestRecovery(totalDice));
    }

    @Test
    @DisplayName("A character with eight Hit Dice regains four spent ones (PHB p.186's example)")
    void eightHitDiceRegainFour() {
        assertEquals(List.of(new Pool(8, 8, 6)), HitDice.afterLongRest(List.of(new Pool(8, 8, 2))));
    }

    @Test
    @DisplayName("A long rest restores no more Hit Dice than were spent")
    void noMoreThanWereSpent() {
        assertEquals(List.of(new Pool(10, 6, 6)), HitDice.afterLongRest(List.of(new Pool(10, 6, 5))));
        assertEquals(List.of(new Pool(10, 6, 6)), HitDice.afterLongRest(List.of(new Pool(10, 6, 6))));
        // One die is always restored, even with a single Hit Die
        assertEquals(List.of(new Pool(12, 1, 1)), HitDice.afterLongRest(List.of(new Pool(12, 1, 0))));
    }

    @Test
    @DisplayName("Larger Hit Dice come back first, whatever order the pools are in")
    void largerDiceFirst() {
        // Wizard 3 and Fighter 5, all spent: 8 dice, so 4 come back, all of them d10s
        assertEquals(List.of(new Pool(6, 3, 0), new Pool(10, 5, 4)),
                HitDice.afterLongRest(List.of(new Pool(6, 3, 0), new Pool(10, 5, 0))));

        // Wizard 6 with all spent and Fighter 2 with one spent: the d10 first, then three d6s
        assertEquals(List.of(new Pool(6, 6, 3), new Pool(10, 2, 2)),
                HitDice.afterLongRest(List.of(new Pool(6, 6, 0), new Pool(10, 2, 1))));

        // Barbarian 2, Fighter 2 and Wizard 2, all spent: 6 dice, so 3 back, the d12s and then one d10
        assertEquals(List.of(new Pool(6, 2, 0), new Pool(10, 2, 1), new Pool(12, 2, 2)),
                HitDice.afterLongRest(List.of(new Pool(6, 2, 0), new Pool(10, 2, 0), new Pool(12, 2, 0))));
    }

    @Test
    @DisplayName("Pools with the same die size are restored in the order given")
    void sameDieSizeInOrderGiven() {
        // Fighter 3 and Paladin 3, all spent: 6 dice, so 3 back, all to the first d10 pool
        assertEquals(List.of(new Pool(10, 3, 3), new Pool(10, 3, 0)),
                HitDice.afterLongRest(List.of(new Pool(10, 3, 0), new Pool(10, 3, 0))));
    }

    @Test
    @DisplayName("A pool can't have more dice left than it holds, or fewer than none")
    void impossiblePools() {
        assertThrows(IllegalArgumentException.class, () -> new Pool(8, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new Pool(8, 3, -1));
        assertThrows(IllegalArgumentException.class, () -> new Pool(8, -1, 0));
    }
}
