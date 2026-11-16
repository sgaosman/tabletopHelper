package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.HitDice.Pool;
import com.tabletophelper.rules.character.Rests.SlotRecovery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Test
    @DisplayName("Natural Recovery recovers expended slots adding up to half the druid level, rounded up, none above "
            + "5th (PHB p.68, D222)")
    void naturalRecovery() {
        Map<Integer, Integer> expended = Map.of(1, 4, 2, 3, 3, 3, 5, 1, 6, 1);
        SlotRecovery natural = SlotRecovery.NATURAL_RECOVERY;

        assertEquals(Optional.empty(), Rests.slotRecoveryRefusal(natural, 5, List.of(2, 1), expended));
        assertEquals(Optional.empty(), Rests.slotRecoveryRefusal(natural, 9, List.of(5), expended));
        assertEquals(Optional.of("Natural Recovery recovers slots whose levels add up to 3 at most (half of druid "
                        + "level 5, rounded up), not 4"),
                Rests.slotRecoveryRefusal(natural, 5, List.of(3, 1), expended));
        assertEquals(Optional.of("Natural Recovery recovers slots of 1st to 5th level, not 6"),
                Rests.slotRecoveryRefusal(natural, 20, List.of(6), expended));
        assertEquals(Optional.of("Only 1 level 5 spell slot is expended, not 2"),
                Rests.slotRecoveryRefusal(natural, 20, List.of(5, 5), expended));
        assertEquals(Optional.of("Natural Recovery is a Circle of the Land druid's feature"),
                Rests.slotRecoveryRefusal(natural, 0, List.of(1), expended));
        assertEquals(Optional.of("Arcane Recovery is a wizard's feature"),
                Rests.arcaneRecoveryRefusal(0, List.of(1), expended));
    }

    // Song of Rest (PHB p.54, D223): "1d6", rising to 1d8 at 9th level, 1d10 at 13th and 1d12 at 17th
    @ParameterizedTest(name = "Bard {0}: d{1}")
    @CsvSource({"2, 6", "8, 6", "9, 8", "12, 8", "13, 10", "16, 10", "17, 12", "20, 12"})
    @DisplayName("Song of Rest heals a d6 from Bard 2, a d8 at 9, a d10 at 13 and a d12 at 17")
    void songOfRestDie_byBardLevel(int bardLevel, int die) {
        assertEquals(Optional.of(die), Rests.songOfRestDie(bardLevel));
    }

    @Test
    @DisplayName("A bard below 2nd level has no Song of Rest")
    void songOfRestDie_belowSecondLevel_none() {
        assertEquals(Optional.empty(), Rests.songOfRestDie(1));
        assertEquals(Optional.empty(), Rests.songOfRestDie(0));
    }

    @Test
    @DisplayName("Song of Rest heals only a creature that spent Hit Dice and regained hit points by them")
    void earnsSongOfRest_needsHitDiceThatHealed() {
        assertEquals(true, Rests.earnsSongOfRest(1, 4));
        assertEquals(false, Rests.earnsSongOfRest(0, 0));
        assertEquals(false, Rests.earnsSongOfRest(2, 0));
    }
}
