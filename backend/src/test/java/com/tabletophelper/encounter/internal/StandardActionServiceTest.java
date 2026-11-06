package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.ObjectInteractionRequest;
import com.tabletophelper.encounter.dto.StandardActionCost;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.encounter.dto.TurnEconomyRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.StandardActions;
import com.tabletophelper.rules.combat.StandardActions.Action;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The standard actions, object interactions and the DM's economy edit (D174, PHB p.190–193). A player controls Rogue
 * (initiative 20, with the turn) and Fighter (15); a goblin goes last (10).
 */
class StandardActionServiceTest {

    private CombatFixture f;
    private EncounterParticipant rogue;
    private EncounterParticipant fighter;
    private EncounterParticipant goblin;
    private UUID player;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rogue = f.player("Rogue", 30, 14);
        fighter = f.player("Fighter", 40, 18);
        goblin = f.monster("Goblin", 7, 15);
        rogue.setInitiative(20);
        fighter.setInitiative(15);
        goblin.setInitiative(10);
        player = f.playerControlling(rogue);
        fighter.setControlledByUserId(player);
        rogue.getCharacter().setFeatures(List.of(new Feature("Sneak Attack", null, "Rogue"),
                new Feature("Cunning Action", null, "Rogue")));
    }

    private void act(EncounterParticipant participant, Action action, UUID userId) {
        act(participant, action, null, null, null, userId);
    }

    private void act(EncounterParticipant participant, Action action, StandardActionCost cost, UUID targetId,
                     String note, UUID userId) {
        f.standardActions.standardAction(f.encounterId(),
                new StandardActionRequest(participant.getId(), action, cost, targetId, note), userId);
    }

    private void bonus(EncounterParticipant participant, Action action, UUID userId) {
        act(participant, action, StandardActionCost.BONUS_ACTION, null, null, userId);
    }

    private void interact(EncounterParticipant participant, String description, UUID userId) {
        f.standardActions.objectInteraction(f.encounterId(),
                new ObjectInteractionRequest(participant.getId(), description), userId);
    }

    private void advance() {
        f.turns.advanceTurn(f.encounterId(), f.dmUserId);
    }

    private static TurnEconomy economy(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }

    private List<String> standardEntries() {
        return f.entries(CombatActionType.STANDARD_ACTION).stream().map(CombatLog::getDescription).toList();
    }

    @Nested
    @DisplayName("Costs and access")
    class Costs {

        @Test
        @DisplayName("Dash takes the action and shows in the economy")
        void dash() {
            act(rogue, Action.DASH, player);

            assertEquals(1, economy(rogue).actionsUsed());
            assertTrue(economy(rogue).dashed());
            assertFalse(economy(rogue).bonusActionUsed());
            assertEquals(List.of("Rogue takes the Dash action, gaining extra movement equal to its speed this turn."),
                    standardEntries());
        }

        @Test
        @DisplayName("Disengage takes the action and shows in the economy")
        void disengage() {
            act(rogue, Action.DISENGAGE, player);

            assertEquals(1, economy(rogue).actionsUsed());
            assertTrue(economy(rogue).disengaged());
        }

        @Test
        @DisplayName("A rogue's Cunning Action Dash takes the bonus action, leaving the action (PHB p.96)")
        void cunningActionDash() {
            bonus(rogue, Action.DASH, player);
            f.dice.script(10);
            act(rogue, Action.HIDE, player);

            assertTrue(economy(rogue).bonusActionUsed());
            assertTrue(economy(rogue).dashed());
            assertEquals(1, economy(rogue).actionsUsed());
            assertEquals("Rogue takes the Dash action (bonus action, Cunning Action), gaining extra movement equal "
                    + "to its speed this turn.", standardEntries().getFirst());
        }

        @Test
        @DisplayName("A non-rogue's bonus Dash is a 409 for a player, and nothing changes")
        void nonRogueBonusDashRefused() {
            f.turnOf(fighter);

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> bonus(fighter, Action.DASH, player));

            assertEquals("Dash as a bonus action needs Cunning Action", refused.getMessage());
            assertEquals(TurnEconomy.fresh(), economy(fighter));
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("The DM's bonus Dash for a non-rogue applies and is logged as over the action economy")
        void dmBonusDashLogged() {
            f.turnOf(fighter);

            bonus(fighter, Action.DASH, f.dmUserId);

            assertTrue(economy(fighter).bonusActionUsed());
            assertTrue(economy(fighter).dashed());
            assertEquals(List.of("Fighter's Dash (bonus action) is over the action economy: Dash as a bonus action "
                            + "needs Cunning Action",
                    "Fighter takes the Dash action (bonus action), gaining extra movement equal to its speed this "
                            + "turn."), standardEntries());
        }

        @Test
        @DisplayName("A goblin's Nimble Escape Disengage takes the bonus action (MM p.166)")
        void nimbleEscapeDisengage() {
            goblin.getMonster().setTraits("""
                    [{"name": "Nimble Escape", "description": "The goblin can take the Disengage or Hide action as a \
                    bonus action on each of its turns."}]""");
            f.turnOf(goblin);

            bonus(goblin, Action.DISENGAGE, f.dmUserId);

            assertTrue(economy(goblin).bonusActionUsed());
            assertTrue(economy(goblin).disengaged());
            assertEquals(0, economy(goblin).actionsUsed());
            assertEquals(List.of("Goblin takes the Disengage action (bonus action, Nimble Escape): its movement doesn't "
                    + "provoke opportunity attacks for the rest of the turn."), standardEntries());
        }

        @Test
        @DisplayName("Nimble Escape among a monster's templated bonus actions counts too, but not for Dash")
        void nimbleEscapeFromTemplates() {
            goblin.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"bonusActions": [{"name": "Nimble Escape", "description": "Disengage or Hide."}]}"""));
            f.turnOf(goblin);

            f.dice.script(10);
            bonus(goblin, Action.HIDE, f.dmUserId);
            CombatFixture.newTurn(goblin);
            bonus(goblin, Action.DASH, f.dmUserId);

            assertTrue(standardEntries().getFirst().startsWith("Goblin takes the Hide action (bonus action, Nimble "
                    + "Escape)"));
            // Over budget, but a monster's overspend isn't logged
            assertEquals("Goblin takes the Dash action (bonus action), gaining extra movement equal to its speed this "
                    + "turn.", standardEntries().getLast());
        }

        @Test
        @DisplayName("Dodge can't be a player's bonus action, even a rogue's")
        void dodgeNotBonus() {
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> bonus(rogue, Action.DODGE, player));

            assertEquals("Dodge can't be taken as a bonus action", refused.getMessage());
        }

        @Test
        @DisplayName("A second action is a 409 for a player")
        void secondAction() {
            act(rogue, Action.DASH, player);

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> act(rogue, Action.SEARCH, player));

            assertEquals("It has no action left this turn", refused.getMessage());
        }

        @Test
        @DisplayName("A player acts only on their participant's turn, and only for their own")
        void access() {
            assertThrows(ForbiddenException.class, () -> act(fighter, Action.DASH, player));
            assertThrows(ForbiddenException.class, () -> act(goblin, Action.DASH, player));
            assertTrue(f.log.isEmpty());
        }
    }

    @Nested
    @DisplayName("Dodge (PHB p.192)")
    class Dodge {

        private void sandSpray() {
            goblin.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"actions": [{"name": "Sand Spray", "deliveryMethod": "SAVING_THROW", "saveDC": 12,
                      "saveAbility": "DEX", "halfOnSave": true,
                      "effects": [{"effectType": "DAMAGE", "damageDice": "6", "damageType": "fire"}]}]}"""));
        }

        @Test
        @DisplayName("Dodge leaves a dodging entry that ends at the start of the dodger's next turn")
        void entry() {
            act(rogue, Action.DODGE, player);

            ConditionEntry dodging = rogue.getActiveConditions().getFirst();
            assertEquals(StandardActions.DODGING, dodging.name());
            assertEquals(EndRule.startOfTurn(rogue.getId()), dodging.endRule());
            assertEquals("Dodge", dodging.sourceSpellName());
            assertEquals(rogue.getId(), dodging.sourceParticipantId());
            assertEquals(1, economy(rogue).actionsUsed());
            assertEquals(List.of("Rogue takes the Dodge action until the start of its next turn: attacks against it "
                    + "have disadvantage and it has advantage on Dexterity saves."), standardEntries());
        }

        @Test
        @DisplayName("Attacks against the dodger have disadvantage, its DEX saves advantage, through the round, and "
                + "it ends at the start of its next turn")
        void acrossTurns() {
            sandSpray();
            act(rogue, Action.DODGE, player);
            advance();
            advance();
            assertEquals(goblin.getId(), f.encounter.getCurrentTurnParticipantId());
            assertEquals(List.of(StandardActions.DODGING), CombatFixture.conditionNames(rogue));

            // 18 and 5 with disadvantage: 5 + 4 = 9 misses AC 14
            AttackRollRequest attack = new AttackRollRequest();
            attack.setTargetId(rogue.getId());
            attack.setAttackBonus(4);
            attack.setDamageDice("1d6");
            f.dice.script(18, 5);
            f.attacks.rollAttack(f.encounterId(), attack, goblin.getId(), f.dmUserId);
            assertEquals(30, rogue.getHpCurrent());
            assertTrue(f.entriesAbout(rogue).getLast().getDescription()
                    .startsWith("Goblin attacks Rogue: 2d20(18, 5) with disadvantage → 5 + 4 = 9 vs AC 14 — miss"),
                    f.entriesAbout(rogue).getLast().getDescription());

            // The goblin's next action needs a new turn: 3 and 15 with advantage, 15 saves against DC 12
            CombatFixture.newTurn(goblin);
            f.dice.script(3, 15);
            f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(goblin.getId(), "Sand Spray",
                    "ACTION", List.of(rogue.getId()), null, null, null, null), f.dmUserId);
            assertEquals(27, rogue.getHpCurrent());
            String save = f.entriesAbout(rogue).getLast().getDescription();
            assertTrue(save.contains("DEX save 2d20(3, 15) with advantage → 15 + 0 = 15 vs DC 12"), save);

            advance();
            assertEquals(rogue.getId(), f.encounter.getCurrentTurnParticipantId());
            assertTrue(CombatFixture.conditionNames(rogue).isEmpty());
            assertTrue(f.descriptions().contains("dodging ends on Rogue (start of Rogue's turn)"));
        }

        @Test
        @DisplayName("Another creature's turn starting doesn't end it")
        void othersTurns() {
            act(rogue, Action.DODGE, player);
            advance();

            assertEquals(List.of(StandardActions.DODGING), CombatFixture.conditionNames(rogue));
        }

        @Test
        @DisplayName("A second Dodge replaces the first entry")
        void replaces() {
            act(rogue, Action.DODGE, player);
            CombatFixture.newTurn(rogue);
            act(rogue, Action.DODGE, player);

            assertEquals(1, rogue.getActiveConditions().size());
        }

        @Test
        @DisplayName("An incapacitated dodger loses the benefit: the attack is rolled normally")
        void incapacitated() {
            act(rogue, Action.DODGE, player);
            f.effectApplier.addCondition(f.encounter, rogue, "stunned", null);
            f.turnOf(goblin);

            AttackRollRequest attack = new AttackRollRequest();
            attack.setTargetId(rogue.getId());
            attack.setAttackBonus(4);
            attack.setDamageDice("1d6");
            f.dice.script(12, 3);
            f.attacks.rollAttack(f.encounterId(), attack, goblin.getId(), f.dmUserId);

            assertEquals(27, rogue.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Help, Hide, Ready, Search and Use an Object")
    class Others {

        @Test
        @DisplayName("Help names its creature in the log, and must have one")
        void help() {
            act(rogue, Action.HELP, null, fighter.getId(), "distracting the goblin", player);

            CombatLog entry = f.entries(CombatActionType.STANDARD_ACTION).getFirst();
            assertEquals("Rogue takes the Help action for Fighter (distracting the goblin): the advantage it gives is "
                    + "declared on the roll it helps.", entry.getDescription());
            assertEquals(fighter.getId(), entry.getTargetId());
            assertEquals(1, economy(rogue).actionsUsed());
        }

        @Test
        @DisplayName("Help without a creature, or another action with one, is a 400 that charges nothing")
        void helpTarget() {
            assertThrows(ValidationException.class, () -> act(rogue, Action.HELP, null, null, null, player));
            assertThrows(ValidationException.class,
                    () -> act(rogue, Action.DASH, null, fighter.getId(), null, player));
            assertEquals(TurnEconomy.fresh(), economy(rogue));
        }

        @Test
        @DisplayName("Hide rolls Dexterity (Stealth) from the sheet: proficiency, doubled with expertise")
        void hideFromSheet() {
            rogue.getCharacter().setDexterity(16);
            rogue.getCharacter().setSkillProficiencies(List.of("Stealth"));
            rogue.getCharacter().setSkillExpertises(List.of("stealth"));
            f.dice.script(12);

            bonus(rogue, Action.HIDE, player);

            CombatLog entry = f.entries(CombatActionType.STANDARD_ACTION).getFirst();
            assertEquals("Rogue takes the Hide action (bonus action, Cunning Action): Dexterity (Stealth) d20(12) + 7 "
                    + "= 19; the DM decides whether it is hidden.", entry.getDescription());
            assertEquals(12, entry.getRollValue());
            assertEquals(19, entry.getRollTotal());
        }

        @Test
        @DisplayName("Hide rolls a monster's Stealth from its stat block's skills, or its DEX modifier")
        void hideFromStatBlock() {
            goblin.getMonster().setSkills("{\"stealth\": \"+6\"}");
            f.turnOf(goblin);
            f.dice.script(9);
            act(goblin, Action.HIDE, f.dmUserId);
            assertEquals(15, f.entries(CombatActionType.STANDARD_ACTION).getLast().getRollTotal());

            goblin.getMonster().setSkills(null);
            goblin.getMonster().setDexterity(14);
            CombatFixture.newTurn(goblin);
            f.dice.script(9);
            act(goblin, Action.HIDE, f.dmUserId);
            assertEquals(11, f.entries(CombatActionType.STANDARD_ACTION).getLast().getRollTotal());
        }

        @Test
        @DisplayName("Ready spends the action and logs its trigger, which it must have")
        void ready() {
            assertThrows(ValidationException.class, () -> act(rogue, Action.READY, null, null, " ", player));

            act(rogue, Action.READY, null, null, "the goblin steps through the door", player);

            assertEquals(1, economy(rogue).actionsUsed());
            assertEquals(List.of("Rogue takes the Ready action, waiting for: the goblin steps through the door. Its "
                    + "response will take its reaction."), standardEntries());
            assertFalse(economy(rogue).reactionUsed());
        }

        @Test
        @DisplayName("Search and Use an Object are logged, with the note")
        void searchAndUseObject() {
            act(rogue, Action.SEARCH, player);
            CombatFixture.newTurn(rogue);
            act(rogue, Action.USE_OBJECT, null, null, "drinks a potion of healing", player);

            assertEquals(List.of("Rogue takes the Search action.",
                    "Rogue takes the Use an Object action (drinks a potion of healing)."), standardEntries());
        }
    }

    @Nested
    @DisplayName("Object interaction (PHB p.190)")
    class ObjectInteraction {

        @Test
        @DisplayName("The first each turn is free; a player's second is a 409 that says why")
        void secondRefused() {
            interact(rogue, "draws a dagger", player);

            assertTrue(economy(rogue).objectInteractionUsed());
            assertEquals(0, economy(rogue).actionsUsed());
            assertEquals("Rogue interacts with an object: draws a dagger",
                    f.entries(CombatActionType.OBJECT_INTERACTION).getFirst().getDescription());

            ConflictException refused = assertThrows(ConflictException.class,
                    () -> interact(rogue, "opens the door", player));
            assertEquals("It has already had its free object interaction this turn; another needs the Use an Object "
                    + "action", refused.getMessage());
            assertEquals(1, f.entries(CombatActionType.OBJECT_INTERACTION).size());
        }

        @Test
        @DisplayName("The DM's second applies and is logged as over the action economy")
        void dmLogged() {
            interact(rogue, "draws a dagger", f.dmUserId);
            interact(rogue, "opens the door", f.dmUserId);

            assertEquals(List.of("Rogue interacts with an object: draws a dagger",
                    "Rogue's object interaction is over the action economy: it has already had its free object "
                            + "interaction this turn; another needs the Use an Object action",
                    "Rogue interacts with an object: opens the door"), f.descriptions());
        }

        @Test
        @DisplayName("It comes back at the start of the creature's next turn")
        void nextTurn() {
            interact(rogue, "draws a dagger", player);
            advance();
            advance();
            advance();

            assertFalse(economy(rogue).objectInteractionUsed());
        }

        @Test
        @DisplayName("A player interacts only on their participant's turn")
        void offTurn() {
            assertThrows(ForbiddenException.class, () -> interact(fighter, "draws a sword", player));
        }
    }

    @Nested
    @DisplayName("The DM's economy edit")
    class Edit {

        private void set(EncounterParticipant participant, TurnEconomy economy, UUID userId) {
            f.turnEconomyEdit.setTurnEconomy(f.encounterId(), new TurnEconomyRequest(participant.getId(), economy),
                    userId);
        }

        @Test
        @DisplayName("The DM sets any part, such as Haste's extra action, logged with what changed")
        void haste() {
            set(fighter, new TurnEconomy(2, 0, false, 0, true, false, false, false, false, false, false, false),
                    f.dmUserId);

            assertEquals(2, economy(fighter).actions());
            assertTrue(economy(fighter).bonusActionUsed());
            CombatLog entry = f.entries(CombatActionType.TURN_ECONOMY_SET).getFirst();
            assertEquals("The DM sets Fighter's turn economy: actions 1 → 2, bonus action used no → yes",
                    entry.getDescription());
            assertEquals(fighter.getId(), entry.getTargetId());
        }

        @Test
        @DisplayName("An unchanged economy is logged as no change")
        void noChange() {
            set(fighter, TurnEconomy.fresh(), f.dmUserId);

            assertEquals("The DM sets Fighter's turn economy: no change",
                    f.entries(CombatActionType.TURN_ECONOMY_SET).getFirst().getDescription());
        }

        @Test
        @DisplayName("It is the DM's alone, and counts out of range are a 400")
        void dmOnly() {
            TurnEconomy surprised = new TurnEconomy(0, 0, false, 0, true, true, false, false, false, false, false,
                    false);
            assertThrows(ForbiddenException.class, () -> set(rogue, surprised, player));
            assertEquals(TurnEconomy.fresh(), economy(rogue));
            assertThrows(ValidationException.class, () -> set(rogue,
                    new TurnEconomy(1, -1, false, 0, false, false, false, false, false, false, false, false),
                    f.dmUserId));
            assertThrows(ValidationException.class, () -> set(rogue,
                    new TurnEconomy(11, 0, false, 0, false, false, false, false, false, false, false, false),
                    f.dmUserId));

            set(rogue, surprised, f.dmUserId);
            assertEquals(surprised, economy(rogue));
            assertThrows(ConflictException.class, () -> act(rogue, Action.DASH, player));
        }
    }
}
