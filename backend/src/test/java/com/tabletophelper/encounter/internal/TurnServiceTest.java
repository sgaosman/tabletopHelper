package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.DeathSaveRequest;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The turn order as the DM moves through it, and what happens at each turn boundary (D162, D163). From the old
 * CombatServiceTest: the turn cases 10.30 to 10.32, condition expiry 10.27, only the DM advancing, an unknown
 * encounter, and the Completed and Preparing lifecycle case. The rest came with R5: the computed order,
 * the Lair's turn, end rules, Repeat Saves, recharges and death saves due.
 */
class TurnServiceTest {

    private CombatFixture f;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
    }

    private EncounterParticipant pc(String name, int initiative) {
        EncounterParticipant pc = f.player(name, 30, 15);
        pc.setInitiative(initiative);
        return pc;
    }

    private EncounterParticipant monster(String name, int initiative) {
        EncounterParticipant monster = f.monster(name, 30, 12);
        monster.setInitiative(initiative);
        return monster;
    }

    private void advance() {
        f.turns.advanceTurn(f.encounterId(), f.dmUserId);
    }

    // A turn economy with its action, bonus action, reaction and object interaction spent, in an Attack action (D174)
    private static TurnEconomy spent() {
        return new TurnEconomy(1, 1, true, 1, true, true, true, false, false, true, false);
    }

    private static void give(EncounterParticipant participant, ConditionEntry... conditions) {
        participant.setActiveConditions(new ArrayList<>(List.of(conditions)));
    }

    private static ConditionEntry condition(String name, Integer duration, int appliedRound, UUID source,
                                            EndRule endRule, RepeatSave repeatSave) {
        return new ConditionEntry(UUID.randomUUID(), name, duration, appliedRound, null, source, null, null, endRule,
                repeatSave);
    }

    // ── Moving through the order ─────────────────────────────────

    @Nested
    class Order {

        @Test
        @DisplayName("10.30 Advancing moves to the next participant in initiative order, whatever the stored order")
        void advanceTurnNextParticipant() {
            EncounterParticipant goblin = monster("Goblin", 7);
            EncounterParticipant fighter = pc("Fighter", 18);
            EncounterParticipant wizard = pc("Wizard", 12);
            wizard.setTurnEconomy(spent());
            f.turnOf(fighter);

            advance();

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, wizard.getId()));
            assertEquals(TurnEconomy.fresh(), wizard.getTurnEconomy());
            assertEquals(1, f.encounter.getRoundNumber());
            assertEquals(List.of("Turn passes to Wizard (Round 1)"), f.descriptions());
            assertEquals(wizard.getId(), f.log.getFirst().getTargetId());
            assertFalse(EncounterTurns.isCurrentTurn(f.encounter, goblin.getId()));
        }

        @Test
        @DisplayName("10.31 Advancing past the end of the order wraps and starts a new round")
        void advanceTurnWrapsRound() {
            EncounterParticipant fighter = pc("Fighter", 18);
            EncounterParticipant goblin = monster("Goblin", 7);
            f.turnOf(goblin);

            advance();

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertEquals(2, f.encounter.getRoundNumber());
            assertEquals(List.of("Turn passes to Fighter (Round 2)"), f.descriptions());
        }

        @Test
        @DisplayName("A tie in initiative goes to the higher DEX score, and a full tie to the DM's order (D163)")
        void tiesBreakByDexterityThenTheDmsOrder() {
            EncounterParticipant first = pc("First", 20);
            pc("Aria", 15);
            EncounterParticipant goblin = monster("Goblin", 15);
            goblin.getMonster().setDexterity(14);
            EncounterParticipant brom = pc("Brom", 15);
            EncounterParticipant cael = pc("Cael", 15);
            brom.setSortOrder(9);
            cael.setSortOrder(8);
            f.turnOf(first);

            List<String> turns = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                advance();
                turns.add(EncounterTurns.currentParticipant(f.encounter).orElseThrow().getDisplayName());
            }

            assertEquals(List.of("Goblin", "Aria", "Cael", "Brom"), turns);
        }

        @Test
        @DisplayName("A dead monster's turn is skipped, and a dead or dying PC keeps theirs (D163)")
        void deadMonstersAreSkipped() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant goblin = monster("Goblin", 15);
            EncounterParticipant rogue = pc("Rogue", 10);
            EncounterParticipant wizard = pc("Wizard", 5);
            CombatFixture.dead(goblin);
            CombatFixture.dead(rogue);
            CombatFixture.downed(wizard, LifeState.DYING, 0);
            f.turnOf(fighter);

            advance();
            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, rogue.getId()));
            advance();
            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, wizard.getId()));
            advance();
            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertEquals(2, f.encounter.getRoundNumber());
        }

        @Test
        @DisplayName("10.32 Going back only moves the marker: nothing is redone or undone (D163)")
        void previousTurnOnlyMovesTheMarker() {
            EncounterParticipant fighter = pc("Fighter", 18);
            EncounterParticipant wizard = pc("Wizard", 12);
            fighter.setTurnEconomy(spent());
            give(fighter, ConditionEntry.manual("blinded", 1, 1));
            f.encounter.setRoundNumber(3);
            f.turnOf(wizard);

            f.turns.previousTurn(f.encounterId(), f.dmUserId);

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertEquals(spent(), fighter.getTurnEconomy());
            assertEquals(List.of("blinded"), CombatFixture.conditionNames(fighter));
            assertEquals(3, f.encounter.getRoundNumber());
            assertEquals(List.of("Turn returns to Fighter (Round 3)"), f.descriptions());
            assertEquals(CombatActionType.TURN_BACK, f.log.getFirst().getActionType());
        }

        @Test
        @DisplayName("Going back from the first turn returns to the last turn of the round before, past dead monsters")
        void previousTurnWrapsBackARound() {
            EncounterParticipant fighter = pc("Fighter", 18);
            EncounterParticipant wizard = pc("Wizard", 12);
            EncounterParticipant goblin = monster("Goblin", 3);
            CombatFixture.dead(goblin);
            f.encounter.setRoundNumber(2);
            f.turnOf(fighter);

            f.turns.previousTurn(f.encounterId(), f.dmUserId);

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, wizard.getId()));
            assertEquals(1, f.encounter.getRoundNumber());
        }

        @Test
        @DisplayName("When nobody can take a turn, advancing is a 409 that changes nothing")
        void nobodyToAct() {
            EncounterParticipant goblin = monster("Goblin", 10);
            CombatFixture.dead(goblin);

            ConflictException ex = assertThrows(ConflictException.class, TurnServiceTest.this::advance);

            assertEquals("Nobody in the turn order can take a turn", ex.getMessage());
            assertTrue(f.log.isEmpty());
        }
    }

    // ── The Lair (D163) ──────────────────────────────────────────

    @Nested
    class Lair {

        private EncounterParticipant ogre;
        private EncounterParticipant fighter;
        private EncounterParticipant dragon;

        @BeforeEach
        void lairFight() {
            ogre = monster("Ogre", 22);
            fighter = pc("Fighter", 15);
            dragon = monster("Dragon", 10);
            f.encounter.setMonstersInLair(new ArrayList<>(List.of(dragon.getId())));
            f.turnOf(ogre);
        }

        @Test
        @DisplayName("The Lair takes a turn on initiative 20, losing ties, while its monster lives")
        void lairTurnOnTwenty() {
            fighter.setInitiative(20);

            advance();
            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()), "A creature on 20 goes first");
            advance();

            assertTrue(f.encounter.getLairTurn());
            assertNull(f.encounter.getCurrentTurnParticipantId());
            assertEquals(List.of("Turn passes to Fighter (Round 1)", "Turn passes to Lair Actions (Round 1)"),
                    f.descriptions());
        }

        @Test
        @DisplayName("Its turn names the lair monster, comes and goes with it, and starts nobody's turn")
        void lairTurnEntry() {
            fighter.setTurnEconomy(spent());

            advance();

            assertTrue(EncounterTurns.isLairTurn(f.encounter));
            assertEquals(List.of("Turn passes to Lair Actions (Round 1)"), f.descriptions());
            assertEquals(dragon.getId(), f.log.getFirst().getTargetId());
            assertEquals(spent(), fighter.getTurnEconomy());

            advance();
            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertFalse(f.encounter.getLairTurn());
        }

        @Test
        @DisplayName("When its monster is dead, the Lair takes no turn")
        void noLairOnceItsMonsterDies() {
            CombatFixture.dead(dragon);

            advance();

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertFalse(EncounterTurns.order(f.encounter).stream().anyMatch(turn -> turn.isLair()));
        }

        @Test
        @DisplayName("A Lair whose monster dies during its turn finishes that turn, then leaves the order")
        void lairFinishesItsTurn() {
            advance();
            CombatFixture.dead(dragon);

            assertTrue(EncounterTurns.order(f.encounter).stream().anyMatch(turn -> turn.isLair()));
            advance();

            assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
            assertFalse(EncounterTurns.order(f.encounter).stream().anyMatch(turn -> turn.isLair()));
        }

        @Test
        @DisplayName("The DM's lair list must name the encounter's monsters")
        void lairStatusNamesMonsters() {
            f.turns.setLairStatus(f.encounterId(), List.of(ogre.getId(), ogre.getId()), f.dmUserId);
            assertEquals(List.of(ogre.getId()), f.encounter.getMonstersInLair());

            ValidationException pc = assertThrows(ValidationException.class,
                    () -> f.turns.setLairStatus(f.encounterId(), List.of(fighter.getId()), f.dmUserId));
            assertThrows(ValidationException.class,
                    () -> f.turns.setLairStatus(f.encounterId(), List.of(UUID.randomUUID()), f.dmUserId));

            assertEquals("Only monsters fight in their lair", pc.getMessage());
            assertEquals(List.of(ogre.getId()), f.encounter.getMonstersInLair());
            f.turns.setLairStatus(f.encounterId(), null, f.dmUserId);
            assertEquals(List.of(), f.encounter.getMonstersInLair());
        }
    }

    // ── The turn that starts ─────────────────────────────────────

    @Nested
    class StartOfTurn {

        @Test
        @DisplayName("10.27 A condition with no caster ends on its creature's turn once its rounds have passed")
        void conditionAutoExpires() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant goblin = monster("Goblin", 10);
            give(goblin, ConditionEntry.manual("blinded", 2, 1), ConditionEntry.manual("prone", null, 1));
            f.encounter.setRoundNumber(3);
            f.turnOf(fighter);

            advance();

            assertEquals(List.of("prone"), CombatFixture.conditionNames(goblin), "Blinded should end after 2 rounds");
            assertTrue(f.descriptions().contains("blinded ends on Goblin (its duration ends)"));
        }

        @Test
        @DisplayName("A spell's duration ends at the start of its caster's turn, not its target's (D162)")
        void durationEndsOnTheCastersTurn() {
            EncounterParticipant wizard = pc("Wizard", 20);
            EncounterParticipant goblin = monster("Goblin", 10);
            give(goblin, ConditionEntry.fromEffect("blinded", 1, 1, "Blindness/Deafness", wizard.getId(), null));
            f.turnOf(wizard);

            advance();
            assertEquals(List.of("blinded"), CombatFixture.conditionNames(goblin), "The goblin's own turn doesn't end it");
            assertEquals(EndRule.afterRounds(wizard.getId()), goblin.getActiveConditions().getFirst().endRule());

            advance();
            assertEquals(List.of(), CombatFixture.conditionNames(goblin));
            assertEquals(List.of("Turn passes to Goblin (Round 1)", "Turn passes to Wizard (Round 2)",
                    "blinded ends on Goblin (its duration ends)"), f.descriptions());
        }

        @Test
        @DisplayName("A duration whose caster is a dead monster ends on its creature's own turn instead")
        void durationOfADeadCaster() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant mage = monster("Mage", 15);
            CombatFixture.dead(mage);
            give(fighter, ConditionEntry.fromEffect("poisoned", 1, 1, "Ray of Sickness", mage.getId(), null));
            f.encounter.setRoundNumber(2);
            EncounterParticipant goblin = monster("Goblin", 5);
            f.turnOf(goblin);

            advance();

            assertEquals(List.of(), CombatFixture.conditionNames(fighter));
        }

        @Test
        @DisplayName("A condition that lasts until the start of a creature's turn ends then, on whoever has it")
        void startOfTurnRule() {
            EncounterParticipant ettin = monster("Ettin", 20);
            EncounterParticipant fighter = pc("Fighter", 10);
            give(fighter, condition("stunned", null, 1, ettin.getId(), EndRule.startOfTurn(ettin.getId()), null));
            f.turnOf(ettin);

            advance();
            assertEquals(List.of("stunned"), CombatFixture.conditionNames(fighter));
            advance();

            assertEquals(List.of(), CombatFixture.conditionNames(fighter));
            assertEquals("stunned ends on Fighter (start of Ettin's turn)", f.descriptions().getLast());
        }

        @Test
        @DisplayName("A Concentration Instance whose duration runs out ends at the start of its caster's turn")
        void concentrationRunsOut() {
            EncounterParticipant cleric = pc("Cleric", 20);
            EncounterParticipant goblin = monster("Goblin", 10);
            f.turnOf(goblin);
            ConcentrationInstance bless = new ConcentrationInstance(UUID.randomUUID(), "Bless", 1, 1, 10);
            cleric.setConcentration(bless);
            give(cleric, ConditionEntry.fromEffect("blessed", 10, 1, "Bless", cleric.getId(), bless));
            f.encounter.setRoundNumber(10);

            advance();

            assertNull(cleric.getConcentration());
            assertEquals(List.of(), CombatFixture.conditionNames(cleric));
            assertTrue(f.descriptions().contains("Cleric loses concentration on Bless (its duration ends)"));
        }

        @Test
        @DisplayName("A legendary creature's spent legendary actions return at the start of its own turn only")
        void perTurnPoolsReset() {
            pc("Fighter", 20);
            EncounterParticipant dragon = monster("Dragon", 10);
            EncounterParticipant wizard = pc("Wizard", 5);
            dragon.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry("monster:legendary-actions",
                    "Legendary Actions", "MONSTER", "Dragon", 3, "3", 0, "turn", null, null, "FREE", "zap", null))));
            f.turnOf(wizard);

            advance();
            assertEquals(0, dragon.getResourcePoolsCurrent().getFirst().currentUses(), "Not on the fighter's turn");
            advance();

            assertEquals(3, dragon.getResourcePoolsCurrent().getFirst().currentUses());
        }

        @Test
        @DisplayName("Each spent Recharge ability rolls a d6 at the start of its turn, logged either way")
        void rechargeRolls() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant dragon = monster("Dragon", 10);
            dragon.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry("monster:recharge:fire-breath",
                    "Fire Breath", "MONSTER", "Dragon", 1, "1", 0, null, null, "1d6>=5", "ACTION", "flame", null))));
            f.turnOf(fighter);

            advance();

            CombatLog roll = f.log.stream()
                    .filter(entry -> entry.getActionType() == CombatActionType.RECHARGE_SUCCESS
                            || entry.getActionType() == CombatActionType.RECHARGE_FAILURE)
                    .findFirst().orElseThrow();
            boolean recharged = roll.getRollValue() >= 5;
            assertEquals(recharged ? CombatActionType.RECHARGE_SUCCESS : CombatActionType.RECHARGE_FAILURE,
                    roll.getActionType());
            assertEquals(recharged ? 1 : 0, dragon.getResourcePoolsCurrent().getFirst().currentUses());
            assertTrue(roll.getDescription().startsWith("Dragon rolls 1d6 (" + roll.getRollValue() + ")."));
        }

        @Test
        @DisplayName("A Dying PC is due a death save as its turn starts; rolling it clears the prompt (D163)")
        void deathSaveDue() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant rogue = pc("Rogue", 10);
            EncounterParticipant cleric = pc("Cleric", 5);
            CombatFixture.downed(rogue, LifeState.DYING, 0);
            CombatFixture.downed(cleric, LifeState.STABLE, 0);
            f.turnOf(fighter);

            advance();
            assertTrue(rogue.getDeathSaveDue());
            assertTrue(f.log.stream().noneMatch(entry -> entry.getActionType() == CombatActionType.DEATH_SAVE));

            advance();
            assertFalse(cleric.getDeathSaveDue(), "A Stable PC makes no death saves");
            DeathSaveRequest request = new DeathSaveRequest();
            request.setParticipantId(rogue.getId());
            f.dice.script(12);
            f.hitPoints.rollDeathSave(f.encounterId(), request, f.dmUserId);

            assertFalse(rogue.getDeathSaveDue());
            assertEquals(1, rogue.getDeathSaveSuccesses());
        }

        @Test
        @DisplayName("Going back to a Dying PC's turn doesn't prompt a death save again")
        void goingBackDoesNotPrompt() {
            EncounterParticipant rogue = pc("Rogue", 20);
            EncounterParticipant fighter = pc("Fighter", 10);
            CombatFixture.downed(rogue, LifeState.DYING, 0);
            f.turnOf(fighter);

            f.turns.previousTurn(f.encounterId(), f.dmUserId);

            assertFalse(rogue.getDeathSaveDue());
        }
    }

    // ── The turn that ends ───────────────────────────────────────

    @Nested
    class EndOfTurn {

        private static final RepeatSave WIS_15 = new RepeatSave(Ability.WIS, 15, false);

        @Test
        @DisplayName("A condition that lasts until the end of a creature's turn ends then, on whoever has it")
        void endOfTurnRule() {
            EncounterParticipant wizard = pc("Wizard", 20);
            EncounterParticipant goblin = monster("Goblin", 10);
            give(goblin, condition("blinded", null, 1, wizard.getId(), EndRule.endOfTurn(wizard.getId()), null));
            f.turnOf(wizard);

            advance();

            assertEquals(List.of(), CombatFixture.conditionNames(goblin));
            assertEquals(List.of("blinded ends on Goblin (end of Wizard's turn)", "Turn passes to Goblin (Round 1)"),
                    f.descriptions());
        }

        @Test
        @DisplayName("The server rolls a monster's Repeat Save as its turn ends: a success ends the condition (D162)")
        void monsterRepeatSaveSucceeds() {
            EncounterParticipant goblin = monster("Goblin", 20);
            EncounterParticipant fighter = pc("Fighter", 10);
            give(goblin, condition("paralyzed", 10, 1, fighter.getId(), EndRule.REPEAT_SAVE, WIS_15));
            f.turnOf(goblin);
            f.dice.script(15);

            advance();

            assertEquals(List.of(), CombatFixture.conditionNames(goblin));
            CombatLog save = f.entries(CombatActionType.REPEAT_SAVE).getFirst();
            assertEquals("Goblin repeats the WIS save against paralyzed: d20(15) + 0 = 15 vs DC 15 — succeeds, "
                    + "and paralyzed ends.", save.getDescription());
            assertEquals(15, save.getRollValue());
            assertNull(save.getLegendaryResistanceEligible());
        }

        @Test
        @DisplayName("A failed Repeat Save keeps the condition, and offers Legendary Resistance, which ends it (D163)")
        void failedRepeatSaveOffersLegendaryResistance() {
            EncounterParticipant dragon = monster("Dragon", 20);
            EncounterParticipant fighter = pc("Fighter", 10);
            dragon.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
            ConditionEntry paralyzed = condition("paralyzed", 10, 1, fighter.getId(), EndRule.REPEAT_SAVE, WIS_15);
            give(dragon, paralyzed);
            f.turnOf(dragon);
            f.dice.script(4);

            advance();

            assertEquals(List.of("paralyzed"), CombatFixture.conditionNames(dragon));
            CombatLog save = f.entries(CombatActionType.REPEAT_SAVE).getFirst();
            assertTrue(save.getDescription().endsWith("— fails, and stays paralyzed."));
            assertTrue(save.getLegendaryResistanceEligible());
            assertEquals(List.of(paralyzed.id()), save.getLrConditionIds());
            assertEquals(0, save.getLrSuccessDamage());

            when(f.combatLogRepository.findById(save.getId())).thenReturn(Optional.of(save));
            f.legendaryResistance.useLegendaryResistance(f.encounterId(), save.getId(), f.dmUserId);
            assertEquals(List.of(), CombatFixture.conditionNames(dragon));
        }

        @Test
        @DisplayName("The server rolls a PC's Repeat Save too while the campaign doesn't prompt players")
        void pcRepeatSaveRolledByTheServer() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant ghoul = monster("Ghoul", 10);
            give(fighter, condition("paralyzed", 10, 1, ghoul.getId(), EndRule.REPEAT_SAVE,
                    new RepeatSave(Ability.CON, 10, false)));
            f.turnOf(fighter);
            f.dice.script(9);

            advance();

            assertEquals(List.of("paralyzed"), CombatFixture.conditionNames(fighter));
            assertFalse(fighter.getActiveConditions().getFirst().repeatSave().awaitingRoll());
            assertEquals(1, f.entries(CombatActionType.REPEAT_SAVE).size());
        }

        @Test
        @DisplayName("When the campaign prompts players, a PC's Repeat Save waits for its player, who rolls it (D162)")
        void pcRepeatSaveRolledByThePlayer() {
            f.campaign.setPromptPlayerRepeatSaves(true);
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant ghoul = monster("Ghoul", 10);
            UUID playerUserId = f.playerControlling(fighter);
            ConditionEntry paralyzed = condition("paralyzed", 10, 1, ghoul.getId(), EndRule.REPEAT_SAVE,
                    new RepeatSave(Ability.CON, 10, false));
            give(fighter, paralyzed);
            f.turnOf(fighter);

            advance();

            assertTrue(fighter.getActiveConditions().getFirst().repeatSave().awaitingRoll());
            assertTrue(f.entries(CombatActionType.REPEAT_SAVE).isEmpty());

            f.dice.script(10);
            f.conditionService.rollRepeatSave(f.encounterId(),
                    new RepeatSaveRequest(fighter.getId(), paralyzed.id()), playerUserId);

            assertEquals(List.of(), CombatFixture.conditionNames(fighter));
            assertEquals("Fighter repeats the CON save against paralyzed: d20(10) + 0 = 10 vs DC 10 — succeeds, "
                    + "and paralyzed ends.", f.entries(CombatActionType.REPEAT_SAVE).getFirst().getDescription());
        }

        @Test
        @DisplayName("A Repeat Save with no roll waiting is a 409, an unknown condition a 400, and another's PC a 403")
        void repeatSaveRefusals() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant rogue = pc("Rogue", 10);
            ConditionEntry paralyzed = condition("paralyzed", 10, 1, null, EndRule.REPEAT_SAVE,
                    new RepeatSave(Ability.CON, 10, false));
            give(fighter, paralyzed);
            UUID rogueUser = f.playerControlling(rogue);

            ConflictException notWaiting = assertThrows(ConflictException.class, () -> f.conditionService
                    .rollRepeatSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(), paralyzed.id()), f.dmUserId));
            assertThrows(ValidationException.class, () -> f.conditionService.rollRepeatSave(f.encounterId(),
                    new RepeatSaveRequest(fighter.getId(), UUID.randomUUID()), f.dmUserId));
            assertThrows(ForbiddenException.class, () -> f.conditionService.rollRepeatSave(f.encounterId(),
                    new RepeatSaveRequest(fighter.getId(), paralyzed.id()), rogueUser));

            assertEquals("No Repeat Save against paralyzed is waiting for a roll", notWaiting.getMessage());
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("A dead PC repeats no saves as its turn ends")
        void deadPcRepeatsNoSaves() {
            EncounterParticipant fighter = pc("Fighter", 20);
            EncounterParticipant goblin = monster("Goblin", 10);
            CombatFixture.dead(fighter);
            give(fighter, condition("paralyzed", 10, 1, goblin.getId(), EndRule.REPEAT_SAVE, WIS_15));
            f.turnOf(fighter);

            advance();

            assertTrue(f.entries(CombatActionType.REPEAT_SAVE).isEmpty());
        }
    }

    // ── Leaving mid-fight ────────────────────────────────────────

    @Test
    @DisplayName("A participant leaving on its turn passes the turn to the next, which starts, and ends its concentration")
    void leavingOnItsTurn() {
        EncounterParticipant mage = monster("Mage", 20);
        EncounterParticipant fighter = pc("Fighter", 10);
        fighter.setTurnEconomy(spent());
        ConcentrationInstance web = new ConcentrationInstance(UUID.randomUUID(), "Web", 2, 1, 600);
        mage.setConcentration(web);
        give(fighter, ConditionEntry.fromEffect("restrained", 600, 1, "Web", mage.getId(), web));
        f.encounter.setMonstersInLair(new ArrayList<>(List.of(mage.getId())));
        f.turnOf(mage);

        f.turnProcessor.leave(f.encounter, mage);
        f.encounter.getParticipants().remove(mage);

        assertTrue(EncounterTurns.isCurrentTurn(f.encounter, fighter.getId()));
        assertEquals(TurnEconomy.fresh(), fighter.getTurnEconomy());
        assertEquals(List.of(), CombatFixture.conditionNames(fighter));
        assertEquals(List.of(), f.encounter.getMonstersInLair());
        assertEquals("Turn passes to Fighter (Round 1)", f.descriptions().getLast());
    }

    @Test
    @DisplayName("The last creature that can act leaving leaves nobody's turn")
    void lastCreatureLeaving() {
        EncounterParticipant fighter = pc("Fighter", 10);
        EncounterParticipant goblin = monster("Goblin", 5);
        CombatFixture.dead(goblin);
        f.turnOf(fighter);

        f.turnProcessor.leave(f.encounter, fighter);

        assertNull(f.encounter.getCurrentTurnParticipantId());
        assertFalse(f.encounter.getLairTurn());
    }

    // ── Access and lifecycle ─────────────────────────────────────

    @Test
    @DisplayName("Only the DM can advance the turn")
    void playerCannotAdvanceTurn() {
        EncounterParticipant rogue = pc("Rogue", 20);
        UUID playerUserId = f.playerControlling(rogue);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.turns.advanceTurn(f.encounterId(), playerUserId));

        assertEquals("Only the DM can perform this action", ex.getMessage());
    }

    @Test
    @DisplayName("An unknown encounter is a NotFoundException, and nothing is locked")
    void unknownEncounterIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(f.encounterRepository.findCampaignIdById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> f.turns.advanceTurn(unknownId, f.dmUserId));
        verify(f.encounterRepository, never()).lockById(any());
    }

    @Test
    @DisplayName("Turns in a Completed or Preparing encounter are a 409")
    void turnsOutsideActiveEncounterAreRefused() {
        monster("Goblin", 7);

        f.encounter.setStatus(EncounterStatus.COMPLETED);
        ConflictException completed = assertThrows(ConflictException.class,
                () -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));
        f.encounter.setStatus(EncounterStatus.PREPARING);
        ConflictException preparing = assertThrows(ConflictException.class,
                () -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));

        assertEquals("The encounter has ended", completed.getMessage());
        assertEquals("The encounter hasn't started yet", preparing.getMessage());
        assertEquals(1, f.encounter.getRoundNumber());
        assertTrue(f.log.isEmpty());
    }
}
