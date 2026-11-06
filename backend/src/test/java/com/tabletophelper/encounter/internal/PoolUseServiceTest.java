package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pool-use command (D174): who may use a pool, what the use costs from the turn, Action Surge once per turn, a
 * use in place of an attack, and the spend's own refusals (D127, D169). Vex, a Fighter 5 Psi Warrior controlled by a
 * player, has the turn; a goblin goes after her.
 */
class PoolUseServiceTest {

    private static final String SECOND_WIND = "class:fighter-second-wind";
    private static final String ACTION_SURGE = "class:fighter-action-surge";
    private static final String DICE = "subclass:psi-warrior-psionic-energy";
    private static final String RECOVERY = "subclass:psi-warrior-psionic-recovery";
    private static final String WARDING = "subclass:cavalier-warding-maneuver";
    private static final String KNACK = "class:test-knack";
    private static final String STRIKE = "class:test-strike";
    private static final String RAGE = "class:barbarian-rage";
    private static final String LR = "monster:legendary-resistance";

    private CombatFixture f;
    private EncounterParticipant vex;
    private EncounterParticipant goblin;
    private UUID player;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        vex = f.player("Vex", 44, 18);
        goblin = f.monster("Goblin", 7, 15);
        player = f.playerControlling(vex);
        vex.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Fighter", null, null, 5)));
        vex.setResourcePoolsCurrent(new ArrayList<>(List.of(
                pool(SECOND_WIND, "Second Wind", "BONUS_ACTION", 1, 1, null),
                pool(ACTION_SURGE, "Action Surge", "FREE", 2, 2, Map.of("grantsAction", true)),
                pool(DICE, "Psionic Energy Dice", "FREE", 4, 3, Map.of("die", "d8")),
                pool(RECOVERY, "Psionic Recovery", "BONUS_ACTION", 1, 1, Map.of("recovers", DICE)),
                pool(WARDING, "Warding Maneuver", "REACTION", 3, 3, null),
                pool(KNACK, "Knack", "ACTION", 2, 2, null),
                pool(STRIKE, "Strike", "REPLACE_ATTACK", 5, 5, null),
                pool(RAGE, "Rage", "BONUS_ACTION", 6, 0, Map.of("unlimited", true)))));
        goblin.setResourcePoolsCurrent(new ArrayList<>(List.of(pool(LR, "Legendary Resistance", "FREE", 3, 3, null))));
    }

    private static ResourcePoolEntry pool(String poolId, String name, String cost, int max, int current,
                                          Map<String, Object> metadata) {
        return new ResourcePoolEntry(poolId, name, "CLASS", null, max, String.valueOf(max), current, "shortRest",
                null, null, cost, "zap", metadata);
    }

    private void use(EncounterParticipant participant, String poolId, UUID userId) {
        use(participant, poolId, null, userId);
    }

    private void use(EncounterParticipant participant, String poolId, Integer uses, UUID userId) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(participant.getId(), poolId, uses), userId);
    }

    private static ResourcePoolEntry poolOf(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream().filter(pool -> pool.poolId().equals(poolId))
                .findFirst().orElseThrow();
    }

    private static int usesOf(EncounterParticipant participant, String poolId) {
        return poolOf(participant, poolId).currentUses();
    }

    private static TurnEconomy economy(EncounterParticipant participant) {
        return participant.getTurnEconomy();
    }

    @Nested
    @DisplayName("Access (D028)")
    class Access {

        @Test
        @DisplayName("A player can't use another participant's pool")
        void notTheirs() {
            assertThrows(ForbiddenException.class, () -> use(goblin, LR, player));
            assertEquals(3, usesOf(goblin, LR));
        }

        @Test
        @DisplayName("The DM uses any participant's pool, on any turn when it costs nothing")
        void dmUsesAnyone() {
            use(goblin, LR, f.dmUserId);

            assertEquals(2, usesOf(goblin, LR));
            assertEquals(TurnEconomy.fresh(), economy(goblin));
            assertEquals("Goblin uses Legendary Resistance; 2/3 left",
                    f.entries(CombatActionType.POOL_USE).getFirst().getDescription());
        }

        @Test
        @DisplayName("A player's action or bonus action pool needs their turn")
        void bonusActionOffTurn() {
            f.turnOf(goblin);

            assertThrows(ForbiddenException.class, () -> use(vex, SECOND_WIND, player));
            assertThrows(ForbiddenException.class, () -> use(vex, KNACK, player));
            assertEquals(1, usesOf(vex, SECOND_WIND));
            assertTrue(f.entries(CombatActionType.POOL_USE).isEmpty());
        }

        @Test
        @DisplayName("A player's reaction pool is used off-turn and takes the reaction; a free one takes nothing")
        void reactionOffTurn() {
            f.turnOf(goblin);

            use(vex, WARDING, player);
            use(vex, DICE, player);

            assertEquals(2, usesOf(vex, WARDING));
            assertEquals(2, usesOf(vex, DICE));
            assertTrue(economy(vex).reactionUsed());
            assertEquals(0, economy(vex).actionsUsed());
            assertEquals(List.of("Vex uses Warding Maneuver (reaction); 2/3 left",
                    "Vex uses Psionic Energy Dice; 2/4 left"), f.descriptions());
        }

        @Test
        @DisplayName("A second reaction before Vex's turn comes round again is a 409 that spends nothing")
        void secondReaction() {
            f.turnOf(goblin);
            use(vex, WARDING, player);

            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, WARDING, player));

            assertEquals("It has already taken its reaction; it comes back at the start of its turn",
                    refused.getMessage());
            assertEquals(2, usesOf(vex, WARDING));
        }
    }

    @Nested
    @DisplayName("Costs (D174)")
    class Costs {

        @Test
        @DisplayName("A bonus action pool takes the bonus action and is logged with its cost")
        void bonusAction() {
            use(vex, SECOND_WIND, player);

            assertEquals(0, usesOf(vex, SECOND_WIND));
            assertTrue(economy(vex).bonusActionUsed());
            assertEquals(List.of("Vex uses Second Wind (bonus action); 0/1 left"), f.descriptions());
            assertEquals(vex.getId(), f.entries(CombatActionType.POOL_USE).getFirst().getActorId());
        }

        @Test
        @DisplayName("A second bonus action that turn is a 409 that spends nothing")
        void secondBonusAction() {
            use(vex, SECOND_WIND, player);

            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, RECOVERY, player));

            assertEquals("It has already taken its bonus action this turn", refused.getMessage());
            assertEquals(1, usesOf(vex, RECOVERY));
            assertEquals(3, usesOf(vex, DICE));
        }

        @Test
        @DisplayName("The DM's second bonus action applies and is logged as over the action economy")
        void dmOverBudget() {
            use(vex, SECOND_WIND, f.dmUserId);
            use(vex, RECOVERY, f.dmUserId);

            assertEquals(0, usesOf(vex, RECOVERY));
            assertTrue(f.descriptions().contains("Vex's Psionic Recovery (bonus action) is over the action economy: "
                    + "it has already taken its bonus action this turn"));
        }

        @Test
        @DisplayName("An action pool takes the action, and a second one is a 409")
        void action() {
            use(vex, KNACK, player);

            assertEquals(1, economy(vex).actionsUsed());
            assertThrows(ConflictException.class, () -> use(vex, KNACK, player));
            assertEquals(1, usesOf(vex, KNACK));
        }

        @Test
        @DisplayName("An incapacitated player can't use a bonus action pool, but can a free one")
        void incapacitated() {
            vex.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("stunned", null, 1))));

            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, SECOND_WIND, player));
            use(vex, DICE, player);

            assertTrue(refused.getMessage().contains("stunned"), refused.getMessage());
            assertEquals(2, usesOf(vex, DICE));
        }

        @Test
        @DisplayName("A use in place of an attack opens the Attack action and uses its attacks; then a 409")
        void replaceAttack() {
            use(vex, STRIKE, player);
            assertTrue(economy(vex).attackActionTaken());
            assertEquals(1, economy(vex).attacksLeft());
            assertEquals(1, economy(vex).actionsUsed());

            use(vex, STRIKE, player);
            assertEquals(0, economy(vex).attacksLeft());
            assertEquals(1, economy(vex).actionsUsed());

            assertThrows(ConflictException.class, () -> use(vex, STRIKE, player));
            assertEquals(3, usesOf(vex, STRIKE));
            assertEquals("Vex uses Strike (in place of an attack); 4/5 left", f.descriptions().getFirst());
        }
    }

    @Nested
    @DisplayName("Action Surge (PHB p.72)")
    class ActionSurge {

        @Test
        @DisplayName("It adds an action, so a second action is allowed")
        void addsAnAction() {
            use(vex, KNACK, player);
            use(vex, ACTION_SURGE, player);
            use(vex, KNACK, player);

            assertEquals(2, economy(vex).actions());
            assertEquals(2, economy(vex).actionsUsed());
            assertTrue(economy(vex).actionSurgeUsed());
            assertEquals(1, usesOf(vex, ACTION_SURGE));
            assertTrue(f.descriptions().contains("Vex uses Action Surge and takes an additional action; 1/2 left"));
        }

        @Test
        @DisplayName("A second Action Surge on the same turn is a 409 that spends nothing, even with a use left")
        void oncePerTurn() {
            use(vex, ACTION_SURGE, player);

            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, ACTION_SURGE, player));

            assertEquals("It has already used Action Surge this turn; it can only once on the same turn",
                    refused.getMessage());
            assertEquals(1, usesOf(vex, ACTION_SURGE));
            assertEquals(2, economy(vex).actions());
        }

        @Test
        @DisplayName("It comes back on Vex's next turn")
        void nextTurn() {
            use(vex, ACTION_SURGE, player);
            CombatFixture.newTurn(vex);

            use(vex, ACTION_SURGE, player);

            assertEquals(0, usesOf(vex, ACTION_SURGE));
            assertEquals(2, economy(vex).actions());
        }

        @Test
        @DisplayName("A player can't use it off-turn, though it costs nothing, nor more than one at a time")
        void ownTurnOnly() {
            assertThrows(ValidationException.class, () -> use(vex, ACTION_SURGE, 2, player));
            f.turnOf(goblin);
            assertThrows(ForbiddenException.class, () -> use(vex, ACTION_SURGE, player));
            assertEquals(2, usesOf(vex, ACTION_SURGE));
        }

        @Test
        @DisplayName("The DM's second Action Surge applies and is logged as over the action economy")
        void dmSecondSurge() {
            use(vex, ACTION_SURGE, f.dmUserId);
            use(vex, ACTION_SURGE, f.dmUserId);

            assertEquals(3, economy(vex).actions());
            assertEquals(0, usesOf(vex, ACTION_SURGE));
            assertTrue(f.descriptions().contains("Vex's Action Surge is over the action economy: it has already "
                    + "used Action Surge this turn; it can only once on the same turn"));
        }
    }

    @Nested
    @DisplayName("The spend (D127, D169)")
    class Spend {

        @Test
        @DisplayName("Psionic Recovery regains an expended die")
        void recovery() {
            use(vex, RECOVERY, player);

            assertEquals(0, usesOf(vex, RECOVERY));
            assertEquals(4, usesOf(vex, DICE));
            assertEquals(List.of("Vex uses Psionic Recovery (bonus action) and regains a use of Psionic Energy Dice "
                    + "(4/4); 0/1 left"), f.descriptions());
        }

        @Test
        @DisplayName("Psionic Recovery with no die expended is a 409 that takes no bonus action")
        void recoveryWithNoneExpended() {
            vex.setResourcePoolsCurrent(new ArrayList<>(vex.getResourcePoolsCurrent().stream()
                    .map(pool -> pool.poolId().equals(DICE) ? pool.withCurrentUses(4) : pool).toList()));

            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, RECOVERY, player));

            assertTrue(refused.getMessage().contains("nothing to regain"), refused.getMessage());
            assertFalse(economy(vex).bonusActionUsed());
            assertEquals(1, usesOf(vex, RECOVERY));
        }

        @Test
        @DisplayName("An unlimited pool is never refused and keeps its uses")
        void unlimited() {
            use(vex, RAGE, player);

            assertEquals(0, usesOf(vex, RAGE));
            assertTrue(economy(vex).bonusActionUsed());
            assertEquals(List.of("Vex uses Rage (bonus action); unlimited"), f.descriptions());
        }

        @Test
        @DisplayName("Too few uses is a 409 that takes nothing from the turn")
        void tooFew() {
            ConflictException refused = assertThrows(ConflictException.class, () -> use(vex, KNACK, 3, player));

            assertEquals("Knack has 2 of 2 uses left, not 3", refused.getMessage());
            assertEquals(0, economy(vex).actionsUsed());
            assertTrue(f.entries(CombatActionType.POOL_USE).isEmpty());
        }

        @Test
        @DisplayName("More than one use is logged with the count")
        void severalUses() {
            use(vex, DICE, 2, player);

            assertEquals(1, usesOf(vex, DICE));
            assertEquals(List.of("Vex uses Psionic Energy Dice ×2; 1/4 left"), f.descriptions());
        }

        @Test
        @DisplayName("A pool the participant hasn't is a 400")
        void unknownPool() {
            ValidationException refused = assertThrows(ValidationException.class,
                    () -> use(vex, "class:monk-ki", player));

            assertEquals("Vex has no pool class:monk-ki", refused.getMessage());
        }
    }
}
