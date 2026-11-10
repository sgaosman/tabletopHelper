package com.tabletophelper.rules.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the source of a frightened creature's fear is within its line of sight (PHB p.290, D201), worked out without a
 * map from blindness, invisibility, the special senses (MM p.8-9) and the source still being there, with the DM's mark
 * over all of them.
 */
class FearTest {

    private static final AttackRules.Side PLAIN = AttackRules.Side.of(List.of("frightened"));
    private static final AttackRules.Side BLINDED = AttackRules.Side.of(List.of("frightened", "blinded"));
    private static final Fear.Source DRAGON = new Fear.Source(AttackRules.Side.NONE, true);
    private static final Fear.Source INVISIBLE_DRAGON = new Fear.Source(AttackRules.Side.of(List.of("invisible")),
            true);

    @Test
    @DisplayName("A source that is there and visible is in sight")
    void inSight() {
        assertEquals(Fear.Sight.IN_SIGHT, Fear.sight(PLAIN, DRAGON, false));
        assertTrue(Fear.sight(PLAIN, DRAGON, false).inSight());
    }

    @Test
    @DisplayName("The DM's mark takes the source out of sight, whatever else holds")
    void markedOut() {
        assertEquals(Fear.Sight.MARKED_OUT_OF_SIGHT, Fear.sight(PLAIN, DRAGON, true));
        assertEquals(Fear.Sight.MARKED_OUT_OF_SIGHT, Fear.sight(PLAIN, Fear.Source.NONE, true));
        assertEquals("marked out of sight by the DM", Fear.Sight.MARKED_OUT_OF_SIGHT.words());
    }

    @Test
    @DisplayName("A blinded creature sees no source, unless it has blindsight (PHB p.290, MM p.8)")
    void blinded() {
        assertEquals(Fear.Sight.BLINDED, Fear.sight(BLINDED, DRAGON, false));
        AttackRules.Side blindsight = new AttackRules.Side(List.of("frightened", "blinded"),
                new AttackRules.Senses(true, false));
        assertEquals(Fear.Sight.IN_SIGHT, Fear.sight(blindsight, DRAGON, false));
    }

    @Test
    @DisplayName("An invisible source is out of sight, unless the creature has blindsight, truesight or See "
            + "Invisibility; truesight and See Invisibility are sight, so a blinded creature loses them")
    void invisibleSource() {
        assertEquals(Fear.Sight.SOURCE_INVISIBLE, Fear.sight(PLAIN, INVISIBLE_DRAGON, false));
        for (AttackRules.Senses senses : List.of(new AttackRules.Senses(true, false),
                new AttackRules.Senses(false, true), new AttackRules.Senses(false, false, true))) {
            assertEquals(Fear.Sight.IN_SIGHT, Fear.sight(new AttackRules.Side(List.of("frightened"), senses),
                    INVISIBLE_DRAGON, false), senses.toString());
        }
        AttackRules.Side blindTruesight = new AttackRules.Side(List.of("frightened", "blinded"),
                new AttackRules.Senses(false, true));
        assertEquals(Fear.Sight.BLINDED, Fear.sight(blindTruesight, INVISIBLE_DRAGON, false));
    }

    @Test
    @DisplayName("A source no longer there, dead, gone or banished, is out of sight")
    void sourceGone() {
        assertEquals(Fear.Sight.SOURCE_GONE, Fear.sight(PLAIN, new Fear.Source(null, false), false));
    }

    @Test
    @DisplayName("A fear with no recorded source counts as in sight, unless the creature is blinded")
    void noSource() {
        assertEquals(Fear.Sight.IN_SIGHT, Fear.sight(PLAIN, Fear.Source.NONE, false));
        assertEquals(Fear.Sight.IN_SIGHT, Fear.sight(PLAIN, null, false));
        assertEquals(Fear.Sight.BLINDED, Fear.sight(BLINDED, Fear.Source.NONE, false));
    }

    @Test
    @DisplayName("Phantasmal Killer, Weird and Illusory Dragon frighten with an illusion rather than their caster")
    void illusorySources() {
        assertTrue(Fear.illusorySource("Phantasmal Killer"));
        assertTrue(Fear.illusorySource("weird"));
        assertTrue(Fear.illusorySource("Illusory Dragon"));
        assertFalse(Fear.illusorySource("Cause Fear"));
        assertFalse(Fear.illusorySource(null));
        assertTrue(Fear.isFrightened(" Frightened "));
        assertFalse(Fear.isFrightened("charmed"));
    }
}
