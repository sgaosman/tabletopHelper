package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wild Shape's rules, typed out from the 2014 PHB: the Beast Shapes table (p.66), Circle Forms and Combat Wild Shape
 * (p.69), the duration and reverting at 0 hit points (p.67).
 */
class BeastShapesTest {

    // Druid levels 1 to 20: the highest challenge rating, then whether flying and swimming beasts are allowed, as the
    // Beast Shapes table gives them (PHB p.66)
    private static final String[] TABLE_CR = {null, "1/4", "1/4", "1/2", "1/2", "1/2", "1/2", "1", "1", "1", "1", "1",
            "1", "1", "1", "1", "1", "1", "1", "1"};
    private static final boolean[] TABLE_FLY = {false, false, false, false, false, false, false, true, true, true, true,
            true, true, true, true, true, true, true, true, true};
    private static final boolean[] TABLE_SWIM = {false, false, false, true, true, true, true, true, true, true, true,
            true, true, true, true, true, true, true, true, true};

    // Circle Forms (PHB p.69): CR 1 from 2nd level, then the druid level divided by 3, rounded down, from 6th
    private static final String[] MOON_CR = {null, "1", "1", "1", "1", "2", "2", "2", "3", "3", "3", "4", "4", "4", "5",
            "5", "5", "6", "6", "6"};

    @Test
    @DisplayName("The Beast Shapes table at every druid level, with no Wild Shape at 1st")
    void beastShapesTable() {
        for (int level = 1; level <= 20; level++) {
            Optional<BeastShapes.Limit> limit = BeastShapes.limit(level, false);
            if (TABLE_CR[level - 1] == null) {
                assertTrue(limit.isEmpty(), "level " + level);
                continue;
            }
            assertEquals(TABLE_CR[level - 1], limit.orElseThrow().label(), "level " + level);
            assertEquals(TABLE_FLY[level - 1], limit.get().flyingAllowed(), "fly at level " + level);
            assertEquals(TABLE_SWIM[level - 1], limit.get().swimmingAllowed(), "swim at level " + level);
        }
    }

    @Test
    @DisplayName("Circle Forms raise the challenge rating but keep the table's movement limits")
    void circleForms() {
        for (int level = 1; level <= 20; level++) {
            Optional<BeastShapes.Limit> limit = BeastShapes.limit(level, true);
            if (MOON_CR[level - 1] == null) {
                assertTrue(limit.isEmpty(), "level " + level);
                continue;
            }
            assertEquals(MOON_CR[level - 1], limit.orElseThrow().label(), "level " + level);
            assertEquals(TABLE_FLY[level - 1], limit.get().flyingAllowed(), "fly at level " + level);
            assertEquals(TABLE_SWIM[level - 1], limit.get().swimmingAllowed(), "swim at level " + level);
        }
    }

    @Test
    @DisplayName("A 2nd-level druid can become a wolf, but not a CR 1/2 ape, a reef shark that swims or an eagle that "
            + "flies")
    void refusals() {
        BeastShapes.Limit second = BeastShapes.limit(2, false).orElseThrow();
        assertEquals(Optional.empty(), BeastShapes.refusal(second, "Wolf", "1/4", Map.of("walk", 40)));
        assertEquals(Optional.of("Ape is challenge rating 1/2, above the limit of 1/4"),
                BeastShapes.refusal(second, "Ape", "1/2", Map.of("walk", 30, "climb", 30)));
        assertEquals(Optional.of("Constrictor Snake has a swimming speed, which needs druid level 4"),
                BeastShapes.refusal(second, "Constrictor Snake", "1/4", Map.of("walk", 30, "swim", 30)));
        assertEquals(Optional.of("Eagle has a flying speed, which needs druid level 8"),
                BeastShapes.refusal(second, "Eagle", "0", Map.of("walk", 10, "fly", 60)));
        assertEquals(Optional.of("Mystery's challenge rating can't be read"),
                BeastShapes.refusal(second, "Mystery", "", Map.of()));
        // A Moon druid of 2nd level may be a brown bear, CR 1
        assertEquals(Optional.empty(), BeastShapes.refusal(BeastShapes.limit(2, true).orElseThrow(), "Brown Bear",
                "1", Map.of("walk", 40, "climb", 30)));
    }

    @Test
    @DisplayName("Challenge ratings read as numbers: fractions, whole numbers, and nothing for text")
    void challengeRatings() {
        assertEquals(0.125, BeastShapes.challengeRating("1/8").orElseThrow());
        assertEquals(0.25, BeastShapes.challengeRating(" 1/4 ").orElseThrow());
        assertEquals(0, BeastShapes.challengeRating("0").orElseThrow());
        assertEquals(6, BeastShapes.challengeRating("6").orElseThrow());
        assertTrue(BeastShapes.challengeRating("Unknown").isEmpty());
        assertTrue(BeastShapes.challengeRating(null).isEmpty());
    }

    @Test
    @DisplayName("Wild Shape is an action, a bonus action for the Circle of the Moon, and lasts half the level in hours")
    void costAndDuration() {
        assertEquals(ActionCost.ACTION, BeastShapes.cost(false));
        assertEquals(ActionCost.BONUS_ACTION, BeastShapes.cost(true));
        assertEquals(1, BeastShapes.durationHours(2));
        assertEquals(2, BeastShapes.durationHours(5));
        assertEquals(10, BeastShapes.durationHours(20));
    }

    @Test
    @DisplayName("Damage that drops the beast to 0 carries what is left over to the normal form (PHB p.67)")
    void carriedOver() {
        // A wolf with 11 HP and no temporary HP takes 15: 4 carries over
        assertTrue(BeastShapes.dropsForm(15, 11, 0));
        assertEquals(4, BeastShapes.carriedOver(15, 11, 0));
        // Exactly 11 drops it to 0 with nothing left over
        assertTrue(BeastShapes.dropsForm(11, 11, 0));
        assertEquals(0, BeastShapes.carriedOver(11, 11, 0));
        // Temporary hit points take damage first
        assertFalse(BeastShapes.dropsForm(13, 11, 3));
        assertEquals(1, BeastShapes.carriedOver(15, 11, 3));
        assertFalse(BeastShapes.dropsForm(0, 11, 0));
    }

    @Test
    @DisplayName("Combat Wild Shape heals 1d8 per slot level, and a save or skill uses the higher bonus")
    void healingAndBonuses() {
        assertEquals(new Dice(3, 8, 0), BeastShapes.combatHealing(3));
        assertThrows(IllegalArgumentException.class, () -> BeastShapes.combatHealing(0));
        assertEquals(5, BeastShapes.bonus(2, 5));
        assertEquals(4, BeastShapes.bonus(4, 3));
        assertEquals(1, BeastShapes.bonus(1, null));
    }
}
