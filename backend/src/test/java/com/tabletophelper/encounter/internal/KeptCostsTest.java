package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.TurnEconomy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a creature keeps of its turn economy when Shield or Counterspell takes back its command (D216, D196): the
 * action stays spent, but what only a hit records goes back to before it (D242 review).
 */
class KeptCostsTest {

    @Test
    @DisplayName("A hit taken back keeps the action spent but frees Genie's Wrath and Sneak Attack for the turn's next hit")
    void hitRecordsGoBack() {
        EncounterParticipant warlock = new EncounterParticipant();
        TurnEconomy before = TurnEconomy.fresh();
        TurnEconomy afterHit = new TurnEconomy(1, 1, true, 1, false, false, false, false, false, false, false, false,
                true, "round-1", false, 0, null, false, false, true);
        warlock.setTurnEconomy(afterHit);

        KeptCosts kept = KeptCosts.of(KeptCosts.Kind.ATTACK, warlock);
        warlock.setTurnEconomy(before); // undo's revert of the hit's command
        kept.putBack(warlock);

        TurnEconomy now = warlock.getTurnEconomy();
        assertEquals(1, now.actionsUsed());
        assertTrue(now.attackActionTaken());
        assertEquals(1, now.attacksLeft());
        assertFalse(now.geniesWrathUsed());
        assertNull(now.sneakAttackTurn());
        assertNull(now.lastMeleeHit());
    }

    @Test
    @DisplayName("A countered spell's caster keeps its spent action but not the Genie's Wrath its spell attack dealt")
    void counteredSpell() {
        EncounterParticipant warlock = new EncounterParticipant();
        TurnEconomy afterCast = TurnEconomy.fresh().withGeniesWrathUsed();
        warlock.setTurnEconomy(afterCast);

        KeptCosts kept = KeptCosts.of(KeptCosts.Kind.SPELL, warlock);
        warlock.setTurnEconomy(TurnEconomy.fresh());
        kept.putBack(warlock);

        assertFalse(warlock.getTurnEconomy().geniesWrathUsed());
    }
}
