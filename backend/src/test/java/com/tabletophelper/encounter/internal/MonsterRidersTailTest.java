package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Monster riders, steps 6 and 7 of the bestiary survey (D204): swallowing and engulfing (a gullet with total cover,
 * blindness and restraint tied to it, acid at the start of the swallower's turns, a limit, regurgitation on enough
 * damage from inside, the escape from a corpse), and the long tail on the existing models: petrification over two saves
 * (D187's counted saves), an ability score drained, what a drop to 0 hit points brings, what rises from the slain, and a
 * ghost's possession (D187's control transfer, D185's Banished). The fighter has every ability at 10 (saves and checks
 * +0), 60 hit points and AC 10; the horror's Constitution save is +0 too.
 */
class MonsterRidersTailTest {

    private CombatFixture f;
    private EncounterParticipant fighter;
    private EncounterParticipant monster;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 60, 10);
        monster = f.monster("Horror", 100, 12);
        monster.getMonster().setSize("Huge");
        monster.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [
                   {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "piercing"},
                                {"effectType": "CONDITION", "condition": "swallowed", "maxTargetSize": "Medium",
                                 "swallow": {"limit": 1, "regurgitateDamage": 10, "regurgitateDC": 15,
                                             "escapeFeet": 10}},
                                {"effectType": "CONDITION", "condition": "blinded", "endsWith": "swallowed"},
                                {"effectType": "CONDITION", "condition": "restrained", "endsWith": "swallowed"},
                                {"effectType": "ONGOING_DAMAGE", "damageDice": "2d6", "damageType": "acid",
                                 "endsWith": "swallowed", "turnOf": "USER"}]},
                   {"name": "Grab", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "bludgeoning",
                                 "flatDamage": true},
                                {"effectType": "CONDITION", "condition": "grappled", "escapeDC": 13},
                                {"effectType": "CONDITION", "condition": "restrained", "endsWith": "grappled"}]},
                   {"name": "Gulp", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing",
                                 "flatDamage": true},
                                {"effectType": "CONDITION", "condition": "swallowed",
                                 "onlyIf": {"targetCondition": "grappled", "byAttacker": true,
                                            "text": "a creature it is grappling"},
                                 "swallow": {}},
                                {"effectType": "CONDITION", "condition": "blinded", "endsWith": "swallowed"}]},
                   {"name": "Swallow", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "targetGrappled": true,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing",
                                 "flatDamage": true},
                                {"effectType": "CONDITION", "condition": "swallowed", "swallow": {"limit": 1}}]},
                   {"name": "Engulf", "deliveryMethod": "SAVING_THROW", "saveDC": 12, "saveAbility": "DEX",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "acid"},
                                {"effectType": "CONDITION", "condition": "engulfed", "escapeDC": 12,
                                 "swallow": {}},
                                {"effectType": "CONDITION", "condition": "restrained", "endsWith": "engulfed"}]},
                   {"name": "Petrifying Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4", "damageType": "piercing"},
                                {"effectType": "CONDITION", "condition": "restrained", "saveDC": 11,
                                 "saveAbility": "CON"},
                                {"effectType": "COUNTED_SAVES", "saveDC": 11, "saveAbility": "CON",
                                 "saveToEndDC": 11, "saveToEndAbility": "CON",
                                 "counted": {"successes": 1, "failures": 1, "endsConditions": ["restrained"],
                                             "failureCondition": "petrified",
                                             "onFailures": "Petrified for 24 hours."}}]},
                   {"name": "Gaze", "deliveryMethod": "SAVING_THROW", "saveDC": 14, "saveAbility": "CON",
                    "effects": [{"effectType": "CONDITION", "condition": "restrained"},
                                {"effectType": "COUNTED_SAVES", "saveToEndDC": 14, "saveToEndAbility": "CON",
                                 "counted": {"successes": 1, "failures": 1, "endsConditions": ["restrained"],
                                             "failureCondition": "petrified", "failBy": 5}}]},
                   {"name": "Strength Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "necrotic",
                                 "flatDamage": true},
                                {"effectType": "ABILITY_REDUCTION", "ability": "STR", "damageDice": "1d4",
                                 "diesAtZero": true, "until": "SHORT_REST"}]},
                   {"name": "Poison Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing",
                                 "flatDamage": true},
                                {"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "poison", "saveDC": 11,
                                 "saveAbility": "CON", "halfOnSave": true},
                                {"effectType": "AT_ZERO", "damageType": "poison",
                                 "atZero": {"stable": true, "condition": "poisoned", "durationRounds": 600,
                                            "linked": ["paralyzed"]}}]},
                   {"name": "Reaping", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "necrotic"},
                                {"effectType": "AT_ZERO", "atZero": {"dies": true}}]},
                   {"name": "Life Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "necrotic"},
                                {"effectType": "ON_KILL", "onKill": {"creatureTypes": ["humanoid"], "form": "Zombie",
                                 "text": "A humanoid slain by this attack rises 24 hours later as a zombie."}}]},
                   {"name": "Raise", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "necrotic"},
                                {"effectType": "ON_KILL", "onKill": {"creatureTypes": ["humanoid"], "form": "Zombie",
                                 "immediately": true}}]},
                   {"name": "Possession", "deliveryMethod": "SAVING_THROW", "saveDC": 13, "saveAbility": "CHA",
                    "targetTypes": ["humanoid"], "immuneAfterSave": "for 24 hours",
                    "effects": [{"effectType": "CONDITION", "condition": "possessed"},
                                {"effectType": "POSSESSION"}]}]}"""));
    }

    private MonsterActionResponse act(String action, EncounterParticipant target, int... rolls) {
        f.dice.script(rolls);
        MonsterActionResponse response = f.monsterActions.monsterAction(f.encounterId(),
                new MonsterActionRequest(monster.getId(), action, "ACTION", List.of(target.getId()), null, null, null,
                        null, null), f.dmUserId);
        assertEquals(0, f.dice.remaining(), "every scripted roll is used");
        return response;
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line.contains(text));
    }

    private static Optional<ConditionEntry> condition(EncounterParticipant creature, String name) {
        return (creature.getActiveConditions() != null ? creature.getActiveConditions() : List.<ConditionEntry>of())
                .stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private List<SpellEffectEntry> riders(String kind) {
        return EffectApplier.effects(monster).stream()
                .filter(e -> e.monsterRider() != null && kind.equals(e.monsterRider().kind()))
                .toList();
    }

    @Nested
    @DisplayName("Step 6: swallowing and engulfing")
    class Swallowing {

        @Test
        @DisplayName("A hit swallows a Medium creature: blinded and restrained while swallowed, in the horror's gullet "
                + "(MM p.255)")
        void swallows() {
            // 15 + 4 hits AC 10; 1d6 3 piercing
            act("Bite", fighter, 15, 3);
            ConditionEntry swallowed = condition(fighter, "swallowed").orElseThrow();
            assertEquals(monster.getId(), swallowed.sourceParticipantId());
            assertEquals(swallowed.id(), condition(fighter, "blinded").orElseThrow().linkedTo());
            assertEquals(swallowed.id(), condition(fighter, "restrained").orElseThrow().linkedTo());
            SpellEffectEntry gullet = riders(SpellEffectEntry.MonsterRider.SWALLOW).getFirst();
            assertEquals(List.of(fighter.getId()), gullet.affected());
            assertEquals(10, gullet.monsterRider().swallow().regurgitateDamage());
            assertEquals(monster.getId(), CombatAccess.swallowedBy(fighter));
            assertTrue(logged("Fighter is swallowed by Horror (Bite): it has total cover against attacks and other "
                    + "effects outside it; 10 damage or more from inside on a single turn makes Horror save (DC 15 "
                    + "Constitution) or regurgitate it."));
        }

        @Test
        @DisplayName("A creature too big isn't swallowed, nor blinded or restrained by it")
        void tooBig() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            ogre.getMonster().setSize("Large");
            act("Bite", ogre, 15, 3);
            assertTrue(CombatFixture.conditionNames(ogre).isEmpty(), CombatFixture.conditionNames(ogre)::toString);
            assertTrue(logged("Ogre is Large, too big to be swallowed by Bite (Medium or smaller)."));
            assertTrue(riders(SpellEffectEntry.MonsterRider.SWALLOW).isEmpty());
        }

        @Test
        @DisplayName("Total cover: nothing outside reaches the swallowed creature, which reaches only the swallower")
        void totalCover() {
            EncounterParticipant cleric = f.player("Cleric", 40, 14);
            act("Bite", fighter, 15, 3);
            ConflictException outside = assertThrows(ConflictException.class,
                    () -> CombatAccess.requireReachable(cleric, List.of(fighter)));
            assertTrue(outside.getMessage().contains("Fighter is swallowed"), outside.getMessage());
            ConflictException inside = assertThrows(ConflictException.class,
                    () -> CombatAccess.requireReachable(fighter, List.of(cleric)));
            assertTrue(inside.getMessage().contains("can reach only the creature that holds it"), inside.getMessage());
            assertDoesNotThrow(() -> CombatAccess.requireReachable(fighter, List.of(monster)));
            assertDoesNotThrow(() -> CombatAccess.requireReachable(monster, List.of(fighter)));
            assertDoesNotThrow(() -> CombatAccess.requireReachable(null, List.of(fighter)));
        }

        @Test
        @DisplayName("Acid at the start of each of the swallower's turns, none on the creature's own")
        void acidEachTurn() {
            act("Bite", fighter, 15, 3);
            assertEquals(57, fighter.getHpCurrent());
            f.dice.script(4, 5);
            f.effectApplier.effectsAtTurnStart(f.encounter, monster);
            assertEquals(48, fighter.getHpCurrent());
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(48, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("10 damage from inside in one turn: the horror fails its Constitution save at the turn's end and "
                + "regurgitates the fighter, prone, free of the blindness, restraint and acid")
        void regurgitates() {
            act("Bite", fighter, 15, 3);
            f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 6, "slashing");
            f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 5, "slashing");
            assertEquals(11, riders(SpellEffectEntry.MonsterRider.SWALLOW).getFirst().monsterRider().swallow()
                    .takenThisTurn());
            f.dice.script(9);
            f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
            assertTrue(condition(fighter, "swallowed").isEmpty());
            assertTrue(condition(fighter, "blinded").isEmpty());
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(condition(fighter, "prone").isPresent());
            assertTrue(riders(SpellEffectEntry.MonsterRider.SWALLOW).isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.TURN_DAMAGE).isEmpty());
            assertTrue(logged("Horror took 11 damage from inside it this turn (Horror's Bite (swallowed)) and makes a "
                    + "Constitution save: d20(9) + 0 = 9 vs DC 15 — fails."), f.descriptions().toString());
            assertTrue(logged("Horror regurgitates Fighter, which lands within 10 feet of it; it is prone."));
        }

        @Test
        @DisplayName("A save keeps the fighter down, and the count starts again the next turn")
        void keepsItDown() {
            act("Bite", fighter, 15, 3);
            f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 12, "slashing");
            f.dice.script(15);
            f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
            assertTrue(condition(fighter, "swallowed").isPresent());
            assertEquals(0, riders(SpellEffectEntry.MonsterRider.SWALLOW).getFirst().monsterRider().swallow()
                    .takenThisTurn());
            // Less than 10 on the next turn calls for no save
            f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 9, "slashing");
            f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
            assertTrue(condition(fighter, "swallowed").isPresent());
        }

        @Test
        @DisplayName("Damage from outside doesn't count toward regurgitation")
        void outsideDamageDoesNotCount() {
            EncounterParticipant cleric = f.player("Cleric", 40, 14);
            act("Bite", fighter, 15, 3);
            f.effectApplier.damage(f.encounter, cleric, "Cleric", monster, 20, "radiant");
            assertEquals(0, riders(SpellEffectEntry.MonsterRider.SWALLOW).getFirst().monsterRider().swallow()
                    .takenThisTurn());
        }

        @Test
        @DisplayName("The gullet holds one: a second creature is bitten but not swallowed")
        void gulletFull() {
            EncounterParticipant rogue = f.player("Rogue", 30, 10);
            act("Bite", fighter, 15, 3);
            act("Bite", rogue, 15, 2);
            assertTrue(condition(rogue, "swallowed").isEmpty());
            assertTrue(condition(rogue, "blinded").isEmpty());
            assertEquals(28, rogue.getHpCurrent());
            assertTrue(logged("Horror already holds 1 creature swallowed, so Rogue isn't swallowed."));
        }

        @Test
        @DisplayName("The swallower dies: the fighter escapes the corpse prone (MM p.255)")
        void swallowerDies() {
            act("Bite", fighter, 15, 3);
            f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 100, "slashing");
            assertEquals(LifeState.DEAD, monster.getLifeState());
            assertTrue(condition(fighter, "swallowed").isEmpty());
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(condition(fighter, "prone").isPresent());
            assertTrue(logged("Fighter escapes from Horror's corpse using 10 feet of movement, exiting prone"));
        }

        @Test
        @DisplayName("A bite that swallows only a creature it grapples: not the free fighter, then the grappled one, "
                + "whose grapple ends (MM p.197)")
        void swallowsOnlyTheGrappled() {
            act("Gulp", fighter, 15);
            assertTrue(condition(fighter, "swallowed").isEmpty());
            assertTrue(logged("Fighter isn't swallowed by Gulp: only a creature it is grappling."));

            act("Grab", fighter, 15);
            assertTrue(condition(fighter, "grappled").isPresent());
            // Restrained by the grapple, the fighter is attacked with advantage
            act("Gulp", fighter, 15, 15);
            assertTrue(condition(fighter, "swallowed").isPresent());
            assertTrue(condition(fighter, "grappled").isEmpty());
            assertTrue(condition(fighter, "restrained").isEmpty(), "the grapple's restraint ends with it");
            assertTrue(condition(fighter, "blinded").isPresent());
        }

        @Test
        @DisplayName("A Swallow that is only for a grappled creature is refused on another (MM p.329)")
        void swallowActionRefused() {
            ConflictException refused = assertThrows(ConflictException.class, () -> act("Swallow", fighter));
            assertEquals("Horror's Swallow can be used only on a creature it is grappling, and it isn't grappling "
                    + "Fighter", refused.getMessage());
            act("Grab", fighter, 15);
            act("Swallow", fighter, 15, 15);
            assertTrue(condition(fighter, "swallowed").isPresent());
        }

        @Test
        @DisplayName("Engulfed on a failed save, restrained while engulfed, and out with a Strength check made as an "
                + "action (MM p.242)")
        void engulfs() {
            // DEX save 5 fails DC 12; 1d6 4 acid
            act("Engulf", fighter, 5, 4);
            ConditionEntry engulfed = condition(fighter, "engulfed").orElseThrow();
            assertEquals(engulfed.id(), condition(fighter, "restrained").orElseThrow().linkedTo());
            assertEquals(monster.getId(), CombatAccess.swallowedBy(fighter));
            f.dice.script(14);
            f.effectApplier.repeatSave(f.encounter, fighter, engulfed.id());
            assertTrue(condition(fighter, "engulfed").isEmpty());
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(condition(fighter, "prone").isEmpty());
        }
    }

    @Nested
    @DisplayName("Step 7: the long tail on the existing models")
    class LongTail {

        @Test
        @DisplayName("A cockatrice's bite: restrained as it turns to stone, then petrified when the save at the end of "
                + "its next turn fails (MM p.42)")
        void petrifiedOverTwoSaves() {
            // 15 + 4 hits; 1d4 2; CON save 4 fails DC 11
            act("Petrifying Bite", fighter, 15, 2, 4);
            assertTrue(condition(fighter, "restrained").isPresent());
            assertEquals(1, riders(SpellEffectEntry.MonsterRider.COUNTED).size());
            f.dice.script(6);
            f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(condition(fighter, "petrified").isPresent());
            assertTrue(logged("Petrified for 24 hours."), f.descriptions().toString());
            assertTrue(riders(SpellEffectEntry.MonsterRider.COUNTED).isEmpty());
        }

        @Test
        @DisplayName("A success at the end of its next turn ends it, with no petrification")
        void shakesItOff() {
            act("Petrifying Bite", fighter, 15, 2, 4);
            f.dice.script(16);
            f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(condition(fighter, "petrified").isEmpty());
        }

        @Test
        @DisplayName("A first save made keeps both the restraint and the count away")
        void savedAtOnce() {
            act("Petrifying Bite", fighter, 15, 2, 18);
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.COUNTED).isEmpty());
        }

        @Test
        @DisplayName("A medusa's gaze failed by 5 or more petrifies at once (MM p.214)")
        void failedByFive() {
            act("Gaze", fighter, 9);
            assertTrue(condition(fighter, "petrified").isPresent());
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.COUNTED).isEmpty());
        }

        @Test
        @DisplayName("A shadow's Strength Drain lowers Strength, and the saves and checks with it, until a rest "
                + "(MM p.269)")
        void strengthDrain() {
            // 15 + 4 hits; 1d4 3
            act("Strength Drain", fighter, 15, 3);
            assertEquals(7, f.stats.abilityScore(fighter, Ability.STR));
            assertEquals(-2, f.stats.saveModifier(fighter, Ability.STR));
            assertTrue(logged("Fighter's STR score is reduced by 3 (Horror's Strength Drain) until it finishes a short "
                    + "or long rest; it is now 7."));
            act("Strength Drain", fighter, 15, 2);
            assertEquals(5, f.stats.abilityScore(fighter, Ability.STR));
            assertEquals(1, riders(SpellEffectEntry.MonsterRider.ABILITY).size());
        }

        @Test
        @DisplayName("The drained creature dies when its Strength reaches 0")
        void drainedToDeath() {
            fighter.getCharacter().setStrength(3);
            act("Strength Drain", fighter, 15, 4);
            assertEquals(LifeState.DEAD, fighter.getLifeState());
            assertTrue(logged("Fighter is killed outright by Horror's Strength Drain (its STR reduced to 0)."));
        }

        @Test
        @DisplayName("Poison that drops the fighter to 0 leaves it stable but poisoned for an hour, and paralyzed "
                + "while poisoned (MM p.328)")
        void stableButPoisoned() {
            fighter.setHpCurrent(8);
            // 15 + 4 hits; 1 piercing; CON save 3 fails; 2d8 4 + 5 = 9 poison
            act("Poison Bite", fighter, 15, 3, 4, 5);
            assertEquals(LifeState.STABLE, fighter.getLifeState());
            ConditionEntry poisoned = condition(fighter, "poisoned").orElseThrow();
            assertEquals(600, poisoned.duration());
            assertEquals(poisoned.id(), condition(fighter, "paralyzed").orElseThrow().linkedTo());
            assertTrue(logged("Fighter is stable at 0 hit points (Horror's Poison Bite)"));
        }

        @Test
        @DisplayName("A hit that leaves the fighter up brings nothing")
        void staysUp() {
            act("Poison Bite", fighter, 15, 3, 4, 5);
            assertEquals(LifeState.ALIVE, fighter.getLifeState());
            assertTrue(condition(fighter, "poisoned").isEmpty());
        }

        @Test
        @DisplayName("\"The target dies if this attack reduces it to 0 hit points\": no death saves")
        void diesAtZero() {
            fighter.setHpCurrent(5);
            act("Reaping", fighter, 15, 4, 4);
            assertEquals(LifeState.DEAD, fighter.getLifeState());
            assertTrue(logged("Fighter is killed outright by Horror's Reaping (it dropped to 0 hit points)."));
        }

        @Test
        @DisplayName("A humanoid slain rises later: the log reminds the DM (MM p.300)")
        void risesLater() {
            EncounterParticipant guard = f.monster("Guard", 5, 10);
            guard.getMonster().setType("humanoid");
            act("Life Drain", guard, 15, 4, 4);
            assertEquals(LifeState.DEAD, guard.getLifeState());
            assertTrue(logged("Guard was slain by Horror's Life Drain: A humanoid slain by this attack rises 24 hours "
                    + "later as a zombie. (the DM's, after the fight)."));
        }

        @Test
        @DisplayName("A slain beast doesn't rise, and one that rises at once is raised when its stat block is loaded, "
                + "else the log tells the DM")
        void risesAtOnce() {
            EncounterParticipant wolf = f.monster("Wolf", 5, 10);
            wolf.getMonster().setType("beast");
            act("Raise", wolf, 15, 4, 4);
            assertFalse(logged("Wolf rises"));
            EncounterParticipant guard = f.monster("Guard", 5, 10);
            guard.getMonster().setType("humanoid");
            act("Raise", guard, 15, 4, 4);
            assertTrue(logged("Guard should rise as a Zombie under Horror's command (Horror's Raise), but its stat "
                    + "block isn't loaded; the DM adds it"), f.descriptions().toString());
        }

        @Test
        @DisplayName("A ghost possesses the fighter: the body is the DM's, the ghost can't be targeted, and both come "
                + "back when the body drops to 0 (MM p.147)")
        void possession() {
            UUID player = f.playerControlling(fighter);
            // CHA save 5 fails DC 13
            act("Possession", fighter, 5);
            assertTrue(condition(fighter, "possessed").isPresent());
            assertTrue(condition(monster, "possessing").isPresent());
            assertNull(fighter.getControlledByUserId());
            ConflictException untouchable = assertThrows(ConflictException.class,
                    () -> CombatAccess.requireNotDead(List.of(monster)));
            assertTrue(untouchable.getMessage().contains("inside the body it possesses"), untouchable.getMessage());

            f.effectApplier.damage(f.encounter, null, "DM", fighter, 60, null);
            assertTrue(condition(fighter, "possessed").isEmpty());
            assertTrue(condition(monster, "possessing").isEmpty());
            assertEquals(player, fighter.getControlledByUserId());
            assertTrue(logged("Horror reappears within 5 feet of the body it possessed"));
            assertTrue(riders(SpellEffectEntry.MonsterRider.POSSESSION).isEmpty());
        }

        @Test
        @DisplayName("Possession takes only a humanoid, and a creature it has possessed is immune afterwards")
        void possessionLimits() {
            EncounterParticipant wolf = f.monster("Wolf", 11, 13);
            wolf.getMonster().setType("beast");
            act("Possession", wolf);
            assertTrue(condition(wolf, "possessed").isEmpty());
            assertTrue(logged("Possession has no effect on Wolf: it affects only humanoid creatures."));

            act("Possession", fighter, 5);
            f.effectApplier.removeCondition(f.encounter, monster, "possessing");
            assertTrue(condition(fighter, "possessed").isEmpty());
            act("Possession", fighter);
            assertTrue(logged("Fighter is immune to Horror's Possession"));
        }
    }
}
