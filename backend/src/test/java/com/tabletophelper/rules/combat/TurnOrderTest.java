package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.TurnOrder.Combatant;
import com.tabletophelper.rules.combat.TurnOrder.NextTurn;
import com.tabletophelper.rules.combat.TurnOrder.PreviousTurn;
import com.tabletophelper.rules.combat.TurnOrder.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TurnOrderTest {

    private static final String LAIR = "Lair";
    private static final List<String> NAMES = List.of("Aria", "Brom", "Cael", "Dax", "Goblin", "Ogre", "Wolf");

    // A combatant named for the test; its id is derived from the name
    private static Combatant pc(String name, Integer initiative, int dexterity, int sortOrder) {
        return new Combatant(id(name), initiative, dexterity, sortOrder, false, false);
    }

    private static Combatant monster(String name, Integer initiative, int dexterity, int sortOrder) {
        return new Combatant(id(name), initiative, dexterity, sortOrder, true, false);
    }

    private static Combatant dead(Combatant combatant) {
        return new Combatant(combatant.id(), combatant.initiative(), combatant.dexterity(), combatant.sortOrder(),
                combatant.monster(), true);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    // The order as names, with the Lair's turn as "Lair"
    private static List<String> names(List<Turn> order) {
        return order.stream()
                .map(turn -> turn.isLair() ? LAIR : NAMES.stream()
                        .filter(name -> id(name).equals(turn.combatant().id()))
                        .findFirst()
                        .orElseThrow())
                .toList();
    }

    // ── Ordering ──────────────────────────────────────────────────

    @Test
    @DisplayName("Highest initiative goes first")
    void highestInitiativeFirst() {
        var aria = pc("Aria", 12, 10, 0);
        var brom = pc("Brom", 18, 10, 1);
        var goblin = monster("Goblin", 5, 14, 2);
        assertEquals(List.of("Brom", "Aria", "Goblin"),
                names(TurnOrder.order(List.of(aria, brom, goblin), false)));
    }

    @Test
    @DisplayName("A tie goes to the higher DEX score")
    void tiesGoToHigherDexterity() {
        var aria = pc("Aria", 15, 12, 0);
        var goblin = monster("Goblin", 15, 14, 1);
        assertEquals(List.of("Goblin", "Aria"),
                names(TurnOrder.order(List.of(aria, goblin), false)));
    }

    @Test
    @DisplayName("A tie in initiative and DEX keeps the DM's order")
    void tiesInDexterityKeepTheDmsOrder() {
        var aria = pc("Aria", 15, 14, 3);
        var brom = pc("Brom", 15, 14, 1);
        var cael = pc("Cael", 15, 14, 2);
        assertEquals(List.of("Brom", "Cael", "Aria"),
                names(TurnOrder.order(List.of(aria, brom, cael), false)));
    }

    @Test
    @DisplayName("Combatants without initiative come last, after negative initiatives")
    void missingInitiativeLast() {
        var aria = pc("Aria", null, 18, 0);
        var brom = pc("Brom", -1, 8, 1);
        var cael = pc("Cael", 3, 10, 2);
        assertEquals(List.of("Cael", "Brom", "Aria"),
                names(TurnOrder.order(List.of(aria, brom, cael), false)));
    }

    // ── The Lair ──────────────────────────────────────────────────

    @Test
    @DisplayName("The Lair takes a turn only when asked for, named Lair Actions")
    void lairOnlyWhenAskedFor() {
        var ogre = monster("Ogre", 10, 8, 0);
        assertFalse(TurnOrder.order(List.of(ogre), false).contains(Turn.LAIR));
        assertEquals(1, TurnOrder.order(List.of(ogre), true).stream().filter(Turn::isLair).count());
        assertEquals("Lair Actions", TurnOrder.LAIR_ACTIONS);
    }

    @Test
    @DisplayName("The Lair acts on initiative 20 and loses ties, however high their DEX")
    void lairOnTwentyLosingTies() {
        var aria = pc("Aria", 21, 10, 0);
        var brom = pc("Brom", 20, 3, 1);
        var goblin = monster("Goblin", 19, 20, 2);
        assertEquals(List.of("Aria", "Brom", LAIR, "Goblin"),
                names(TurnOrder.order(List.of(goblin, brom, aria), true)));
    }

    @Test
    @DisplayName("The Lair goes first when nobody reaches 20, and last when everybody does")
    void lairFirstOrLast() {
        var wolf = monster("Wolf", 15, 15, 0);
        assertEquals(List.of(LAIR, "Wolf"), names(TurnOrder.order(List.of(wolf), true)));

        var dax = pc("Dax", 20, 10, 0);
        var ogre = monster("Ogre", 25, 8, 1);
        assertEquals(List.of("Ogre", "Dax", LAIR), names(TurnOrder.order(List.of(dax, ogre), true)));
    }

    @Test
    @DisplayName("Without initiative a combatant still follows the Lair")
    void missingInitiativeAfterTheLair() {
        var aria = pc("Aria", null, 10, 0);
        assertEquals(List.of(LAIR, "Aria"), names(TurnOrder.order(List.of(aria), true)));
    }

    // ── Moving on ─────────────────────────────────────────────────

    @Test
    @DisplayName("The next turn skips dead monsters")
    void nextSkipsDeadMonsters() {
        var order = TurnOrder.order(List.of(
                pc("Aria", 20, 10, 0), dead(monster("Goblin", 15, 14, 1)), monster("Ogre", 10, 8, 2)), false);
        assertEquals(Optional.of(new NextTurn(2, false)), TurnOrder.next(order, 0));
    }

    @Test
    @DisplayName("Dead and dying PCs keep their turns")
    void pcsKeepTheirTurns() {
        var order = TurnOrder.order(List.of(
                monster("Ogre", 20, 8, 0), dead(pc("Aria", 15, 10, 1)), pc("Brom", 10, 10, 2)), false);
        assertEquals(Optional.of(new NextTurn(1, false)), TurnOrder.next(order, 0));
    }

    @Test
    @DisplayName("Passing the end of the order starts a new round, still skipping dead monsters")
    void wrappingStartsANewRound() {
        var order = TurnOrder.order(List.of(
                dead(monster("Goblin", 20, 14, 0)), pc("Aria", 15, 10, 1), pc("Brom", 10, 10, 2)), false);
        assertEquals(Optional.of(new NextTurn(1, true)), TurnOrder.next(order, 2));
    }

    @Test
    @DisplayName("Before the first turn, the next is the first combatant who acts, in the same round")
    void firstTurn() {
        var order = TurnOrder.order(List.of(dead(monster("Goblin", 20, 14, 0)), pc("Aria", 15, 10, 1)), false);
        assertEquals(Optional.of(new NextTurn(1, false)), TurnOrder.next(order, -1));
    }

    @Test
    @DisplayName("The Lair's turn is never skipped")
    void lairTurnIsTaken() {
        var order = TurnOrder.order(List.of(dead(monster("Ogre", 10, 8, 0))), true);
        assertEquals(Optional.of(new NextTurn(0, true)), TurnOrder.next(order, 1));
    }

    @Test
    @DisplayName("A lone combatant's next turn is its own, in a new round")
    void loneCombatant() {
        var order = TurnOrder.order(List.of(pc("Aria", 12, 10, 0)), false);
        assertEquals(Optional.of(new NextTurn(0, true)), TurnOrder.next(order, 0));
    }

    @Test
    @DisplayName("No next turn when nobody can act")
    void nobodyToAct() {
        assertEquals(Optional.empty(), TurnOrder.next(List.of(), -1));
        var order = TurnOrder.order(List.of(dead(monster("Goblin", 12, 14, 0)), dead(monster("Wolf", 8, 15, 1))), false);
        assertEquals(Optional.empty(), TurnOrder.next(order, 0));
    }

    @Test
    @DisplayName("The current turn must be in the order")
    void currentTurnInTheOrder() {
        var order = TurnOrder.order(List.of(pc("Aria", 12, 10, 0)), false);
        assertThrows(IllegalArgumentException.class, () -> TurnOrder.next(order, 1));
        assertThrows(IllegalArgumentException.class, () -> TurnOrder.next(order, -2));
        assertThrows(IllegalArgumentException.class, () -> TurnOrder.previous(order, 1));
    }

    // ── Going back ────────────────────────────────────────────────

    @Test
    @DisplayName("The previous turn passes over dead monsters, within the round")
    void previousSkipsDeadMonsters() {
        var order = TurnOrder.order(List.of(
                pc("Aria", 20, 10, 0), dead(monster("Goblin", 15, 14, 1)), monster("Ogre", 10, 8, 2)), false);
        assertEquals(Optional.of(new PreviousTurn(0, false)), TurnOrder.previous(order, 2));
    }

    @Test
    @DisplayName("Going back past the first turn returns to the last of the round before, past the Lair it keeps")
    void previousWrapsToThePreviousRound() {
        var order = TurnOrder.order(List.of(pc("Aria", 25, 10, 0), dead(monster("Goblin", 3, 14, 1))), true);
        assertEquals(Optional.of(new PreviousTurn(1, true)), TurnOrder.previous(order, 0));
        assertEquals(LAIR, names(order).get(1));
    }

    @Test
    @DisplayName("No previous turn when nobody can act")
    void noPreviousTurn() {
        assertEquals(Optional.empty(), TurnOrder.previous(List.of(), -1));
    }

    // ── Ties the DM orders ────────────────────────────────────────

    @Test
    @DisplayName("Two combatants tie only on equal initiative and equal DEX; without initiative they never do")
    void tiedOnInitiativeAndDexterity() {
        assertTrue(TurnOrder.tied(pc("Aria", 15, 14, 0), monster("Goblin", 15, 14, 1)));
        assertFalse(TurnOrder.tied(pc("Aria", 15, 12, 0), monster("Goblin", 15, 14, 1)));
        assertFalse(TurnOrder.tied(pc("Aria", 15, 14, 0), monster("Goblin", 16, 14, 1)));
        assertFalse(TurnOrder.tied(pc("Aria", null, 14, 0), monster("Goblin", null, 14, 1)));
    }

    // ── Followers (D182) ──────────────────────────────────────────

    private static Combatant following(String name, Integer initiative, int sortOrder, String leader) {
        return new Combatant(id(name), initiative, 10, sortOrder, true, false, id(leader));
    }

    @Test
    @DisplayName("A summoned spirit takes its turn right after its caster's, whatever its own initiative (TCE p.109)")
    void follower_rightAfterLeader() {
        var aria = pc("Aria", 12, 10, 0);
        var brom = pc("Brom", 15, 10, 1);
        var goblin = monster("Goblin", 10, 10, 2);
        var wolf = following("Wolf", 25, 3, "Aria");
        assertEquals(List.of("Brom", "Aria", "Wolf", "Goblin"),
                names(TurnOrder.order(List.of(wolf, aria, brom, goblin), false)));
    }

    @Test
    @DisplayName("Followers of one caster go in their sort order, the order they were made in")
    void followers_inSortOrder() {
        var aria = pc("Aria", 12, 10, 0);
        var wolf = following("Wolf", 12, 5, "Aria");
        var ogre = following("Ogre", 12, 4, "Aria");
        assertEquals(List.of("Aria", "Ogre", "Wolf"), names(TurnOrder.order(List.of(wolf, aria, ogre), false)));
    }

    @Test
    @DisplayName("With its leader gone, a follower takes its own place by its stored initiative")
    void follower_leaderGone() {
        var brom = pc("Brom", 15, 10, 1);
        var goblin = monster("Goblin", 10, 10, 2);
        var wolf = following("Wolf", 12, 3, "Aria");
        assertEquals(List.of("Brom", "Wolf", "Goblin"), names(TurnOrder.order(List.of(goblin, wolf, brom), false)));
    }

    @Test
    @DisplayName("The Lair doesn't come between a caster on 20 or more and its follower")
    void follower_lairWaits() {
        var aria = pc("Aria", 21, 10, 0);
        var wolf = following("Wolf", 5, 1, "Aria");
        var goblin = monster("Goblin", 10, 10, 2);
        assertEquals(List.of("Aria", "Wolf", "Lair", "Goblin"),
                names(TurnOrder.order(List.of(goblin, wolf, aria), true)));
    }

    @Test
    @DisplayName("A follower never ties, so the DM's tie order leaves it with its leader")
    void follower_neverTies() {
        assertFalse(TurnOrder.tied(pc("Aria", 12, 10, 0), following("Wolf", 12, 1, "Aria")));
    }
}
