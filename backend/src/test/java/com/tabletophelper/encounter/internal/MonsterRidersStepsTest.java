package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Monster riders, steps 3 to 5 of the bestiary survey (D203): what a hit leaves on its target through the spells'
 * machinery (a lower hit point maximum, life drain, no healing, speed and roll debuffs, exhaustion), conditions tied to
 * another (a restraint until the grapple ends, a paralysis while poisoned), damage at the start of each turn, a
 * stirge's attachment, immunity after a successful save, creature types a rider passes over, a grapple's limit, the
 * rolled escape, and a grapple that ends with its incapacitated grappler. The fighter has every ability at 10 (saves
 * and checks +0), 60 hit points and AC 10.
 */
class MonsterRidersStepsTest {

    private CombatFixture f;
    private EncounterParticipant fighter;
    private EncounterParticipant monster;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 60, 10);
        monster = f.monster("Horror", 100, 12);
        monster.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [
                   {"name": "Life Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 2", "damageType": "necrotic"},
                                {"effectType": "HP_MAX_REDUCTION", "until": "LONG_REST", "saveDC": 13,
                                 "saveAbility": "CON", "diesAtZero": true}]},
                   {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 4", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "3d6", "damageType": "necrotic"},
                                {"effectType": "HP_MAX_REDUCTION", "until": "LONG_REST", "damageType": "necrotic",
                                 "diesAtZero": true},
                                {"effectType": "LIFE_DRAIN", "damageType": "necrotic"}]},
                   {"name": "Shadow Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "necrotic"},
                                {"effectType": "LIFE_DRAIN", "tempHp": true}]},
                   {"name": "Beard", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d8 + 2", "damageType": "piercing"},
                                {"effectType": "CONDITION", "condition": "poisoned", "saveDC": 12,
                                 "saveAbility": "CON", "durationRounds": 10, "saveToEndEachTurn": true},
                                {"effectType": "MODIFIER", "modifier": {"kind": "NO_HEALING"},
                                 "endsWith": "poisoned", "saveDC": 12, "saveAbility": "CON"}]},
                   {"name": "Frost Shard", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "cold"},
                                {"effectType": "MODIFIER", "modifier": {"kind": "SPEED", "flat": -10},
                                 "until": "TARGET_TURN_END"},
                                {"effectType": "MODIFIER", "modifier": {"kind": "NO_REACTIONS"},
                                 "until": "TARGET_TURN_START"}]},
                   {"name": "Entropic Touch", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4", "damageType": "necrotic"},
                                {"effectType": "EXHAUSTION", "levels": 1, "saveDC": 15, "saveAbility": "CON"}]},
                   {"name": "Tentacles", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4", "damageType": "poison"},
                                {"effectType": "CONDITION", "condition": "poisoned", "saveDC": 13,
                                 "saveAbility": "CON", "durationRounds": 10, "saveToEndEachTurn": true},
                                {"effectType": "CONDITION", "condition": "paralyzed", "saveDC": 13,
                                 "saveAbility": "CON", "endsWith": "poisoned"}]},
                   {"name": "Constrict", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "grappleLimit": 1, "grappleLimitBlocks": true,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "bludgeoning"},
                                {"effectType": "CONDITION", "condition": "grappled", "escapeDC": 16},
                                {"effectType": "CONDITION", "condition": "restrained", "escapeDC": 16,
                                 "endsWith": "grappled"}]},
                   {"name": "Chain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "grappleLimit": 1,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "slashing"},
                                {"effectType": "CONDITION", "condition": "grappled", "escapeDC": 14},
                                {"effectType": "CONDITION", "condition": "restrained", "endsWith": "grappled"},
                                {"effectType": "ONGOING_DAMAGE", "damageDice": "2d6", "damageType": "piercing",
                                 "endsWith": "grappled"}]},
                   {"name": "Blood Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4 + 3", "damageType": "piercing"},
                                {"effectType": "CONDITION", "condition": "attached"},
                                {"effectType": "ONGOING_DAMAGE", "damageDice": "1d4 + 3", "turnOf": "USER",
                                 "endsWith": "attached", "total": 10}]},
                   {"name": "Claws", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d4 + 2", "damageType": "slashing"},
                                {"effectType": "CONDITION", "condition": "paralyzed", "saveDC": 10,
                                 "saveAbility": "CON", "durationRounds": 10, "saveToEndEachTurn": true,
                                 "exemptTypes": ["elf", "undead"]}]},
                   {"name": "Net", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "bludgeoning",
                                 "flatDamage": true},
                                {"effectType": "CONDITION", "condition": "restrained", "escapeDC": 10}]},
                   {"name": "Frightful Presence", "deliveryMethod": "SAVING_THROW", "saveDC": 15,
                    "saveAbility": "WIS", "immuneAfterSave": "for the next 24 hours",
                    "effects": [{"effectType": "CONDITION", "condition": "frightened", "durationRounds": 10,
                                 "saveToEndEachTurn": true, "saveToEndDC": 15, "saveToEndAbility": "WIS"}]},
                   {"name": "Wail", "deliveryMethod": "SAVING_THROW", "saveDC": 13, "saveAbility": "CON",
                    "exemptTypes": ["construct", "undead"],
                    "effects": [{"effectType": "DAMAGE", "damageDice": "3d6", "damageType": "psychic"}]}]}"""));
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
    @DisplayName("Step 3: the spells' mechanics on a monster's hit")
    class Step3 {

        @Test
        @DisplayName("A failed save lowers the hit point maximum by the necrotic damage taken until a long rest, held "
                + "by the monster, and a second hit lowers it further (MM p.300)")
        void hitPointMaximumReduced() {
            // 15 + 4 hits AC 10; 1d6 4 + 2 = 6 necrotic; CON save 5 fails DC 13
            act("Life Drain", fighter, 15, 4, 5);
            assertEquals(54, CreatureStats.hpMax(fighter));
            assertEquals(54, fighter.getHpCurrent());
            assertTrue(logged("Fighter's hit point maximum is reduced by 6 (Horror's Life Drain) until it finishes a "
                    + "long rest; it is now 54. If the rest comes after the encounter, note it on the sheet after "
                    + "Write-back."));
            SpellEffectEntry held = riders(SpellEffectEntry.MonsterRider.HP_MAX).getFirst();
            assertEquals("LONG_REST", held.monsterRider().rest());
            assertEquals(List.of(fighter.getId()), held.affected());

            // 1d6 2 + 2 = 4 more, the save failing again: one effect, now -10
            act("Life Drain", fighter, 15, 2, 3);
            assertEquals(50, CreatureStats.hpMax(fighter));
            assertEquals(1, riders(SpellEffectEntry.MonsterRider.HP_MAX).size());
        }

        @Test
        @DisplayName("A successful save keeps the maximum, and the log names what the save guarded")
        void savedMaximum() {
            act("Life Drain", fighter, 15, 4, 18);
            assertEquals(60, CreatureStats.hpMax(fighter));
            assertTrue(riders(SpellEffectEntry.MonsterRider.HP_MAX).isEmpty());
            assertTrue(logged("succeeds against hit point maximum reduced"), f.descriptions().getLast());
        }

        @Test
        @DisplayName("The creature dies if the reduction takes its maximum to 0, even dying at 0 hit points")
        void diesAtZeroMaximum() {
            fighter.setHpMax(8);
            fighter.setHpCurrent(8);
            // 1d6 6 + 2 = 8 necrotic drops it to 0; CON save 2 fails; the maximum goes to 0
            act("Life Drain", fighter, 15, 6, 2);
            assertEquals(LifeState.DEAD, fighter.getLifeState());
            assertTrue(logged("Fighter is killed outright by Horror's Life Drain (its hit point maximum reduced to 0)"));
        }

        @Test
        @DisplayName("A vampire's bite lowers the maximum by the necrotic damage only, and the vampire regains that "
                + "many hit points (MM p.297)")
        void vampireBite() {
            monster.setHpCurrent(80);
            // 1d6 3 + 4 = 7 piercing; 3d6 4 + 4 + 4 = 12 necrotic
            act("Bite", fighter, 15, 3, 4, 4, 4);
            assertEquals(60 - 19, fighter.getHpCurrent());
            assertEquals(48, CreatureStats.hpMax(fighter));
            assertEquals(92, monster.getHpCurrent());
            assertTrue(logged("Horror regains 12 HP (Horror's Bite: the damage dealt)"));
        }

        @Test
        @DisplayName("Life drain as temporary hit points gives the monster that many")
        void drainAsTemporaryHitPoints() {
            act("Shadow Bite", fighter, 15, 3, 4);
            assertEquals(7, monster.getHpTemp());
        }

        @Test
        @DisplayName("Can't regain hit points while poisoned in this way: healing is refused, and it ends with the "
                + "poisoning (MM p.70)")
        void noHealingWhilePoisoned() {
            // 1d8 4 + 2 = 6; CON save 3 fails DC 12
            act("Beard", fighter, 15, 4, 3);
            assertTrue(condition(fighter, "poisoned").isPresent());
            f.effectApplier.heal(f.encounter, fighter, fighter, 5, "Potion");
            assertEquals(54, fighter.getHpCurrent());
            assertTrue(logged("can't regain hit points: Horror's Beard"), f.descriptions().getLast());

            ConditionEntry poisoned = condition(fighter, "poisoned").orElseThrow();
            f.dice.script(20);
            f.effectApplier.repeatSave(f.encounter, fighter, poisoned.id());
            assertTrue(condition(fighter, "poisoned").isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.MODIFIER).isEmpty());
            assertTrue(logged("Horror's Beard ends (its poisoned has ended)"));
            f.effectApplier.heal(f.encounter, fighter, fighter, 5, "Potion");
            assertEquals(59, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("A save against the poison keeps the healing ban away too")
        void savedPoisonNoBan() {
            act("Beard", fighter, 15, 4, 18);
            assertTrue(riders(SpellEffectEntry.MonsterRider.MODIFIER).isEmpty());
        }

        @Test
        @DisplayName("Speed -10 until the end of its next turn and no reactions until the start of it are Ongoing "
                + "Modifiers with their own ends (PHB p.271, p.275)")
        void debuffs() {
            act("Frost Shard", fighter, 15, 3);
            List<OngoingModifier> on = ActiveModifiers.modifiersOn(fighter);
            assertTrue(on.stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.SPEED && m.flat() == -10
                    && m.until() == OngoingModifier.Until.TARGET_TURN_END), on::toString);
            assertTrue(on.stream().anyMatch(m -> m.kind() == OngoingModifier.Kind.NO_REACTIONS
                    && m.until() == OngoingModifier.Until.TARGET_TURN_START), on::toString);

            // The start of the fighter's turn ends the lost reactions; the speed lasts to the end of that turn
            f.effectApplier.modifiersAtTurnStart(f.encounter, fighter);
            assertTrue(ActiveModifiers.modifiersOn(fighter).stream()
                    .noneMatch(m -> m.kind() == OngoingModifier.Kind.NO_REACTIONS));
            assertTrue(ActiveModifiers.modifiersOn(fighter).stream()
                    .anyMatch(m -> m.kind() == OngoingModifier.Kind.SPEED));
            f.effectApplier.modifiersAtTurnEnd(f.encounter, fighter);
            assertTrue(ActiveModifiers.modifiersOn(fighter).isEmpty());
        }

        @Test
        @DisplayName("A failed save gives a level of exhaustion, which stays on the creature (PHB p.291)")
        void exhaustion() {
            act("Entropic Touch", fighter, 15, 2, 4);
            assertEquals(1, fighter.getExhaustionLevel());
            act("Entropic Touch", fighter, 15, 2, 19);
            assertEquals(1, fighter.getExhaustionLevel());
        }
    }

    @Nested
    @DisplayName("Step 4: linked conditions, damage each turn, attachment, immunity and exemptions")
    class Step4 {

        @Test
        @DisplayName("A paralysis \"until this poison ends\" is tied to the poisoning and ends with it (MM p.37)")
        void paralysisWithThePoison() {
            act("Tentacles", fighter, 15, 2, 4);
            ConditionEntry poisoned = condition(fighter, "poisoned").orElseThrow();
            ConditionEntry paralyzed = condition(fighter, "paralyzed").orElseThrow();
            assertEquals(poisoned.id(), paralyzed.linkedTo());
            assertNull(paralyzed.repeatSave());
            assertEquals("Tentacles", paralyzed.sourceAction());

            f.effectApplier.removeCondition(f.encounter, fighter, "poisoned");
            assertTrue(condition(fighter, "paralyzed").isEmpty());
            assertTrue(logged("paralyzed ends on Fighter (what it lasted while has ended)"));
        }

        @Test
        @DisplayName("A creature immune to poison isn't paralyzed by it either")
        void noPoisonNoParalysis() {
            EncounterParticipant golem = f.monster("Golem", 50, 10);
            golem.getMonster().setConditionImmunities("[\"poisoned\"]");
            act("Tentacles", golem, 15, 2, 4);
            assertTrue(CombatFixture.conditionNames(golem).isEmpty(), CombatFixture.conditionNames(golem)::toString);
            assertTrue(logged("it lasts only while Golem is poisoned by Tentacles, which it isn't"));
        }

        @Test
        @DisplayName("Damage at the start of each of the creature's turns while it is grappled, and none once the "
                + "grapple ends (MM p.72)")
        void damageWhileGrappled() {
            // 2d6 3 + 3 = 6 slashing
            act("Chain", fighter, 15, 3, 3);
            assertEquals(54, fighter.getHpCurrent());
            assertEquals(condition(fighter, "grappled").orElseThrow().id(),
                    condition(fighter, "restrained").orElseThrow().linkedTo());

            f.dice.script(2, 5);
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(47, fighter.getHpCurrent());
            assertTrue(logged("Horror's Chain deals its damage to Fighter"));

            f.effectApplier.removeCondition(f.encounter, fighter, "grappled");
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.TURN_DAMAGE).isEmpty());
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(47, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("A stirge attaches and drains at the start of its own turns until it has drained 10, then "
                + "detaches (MM p.284)")
        void stirge() {
            act("Blood Drain", fighter, 15, 2);
            assertEquals(55, fighter.getHpCurrent());
            assertTrue(condition(fighter, "attached").isPresent());

            f.dice.script(3);
            f.effectApplier.effectsAtTurnStart(f.encounter, monster);
            assertEquals(49, fighter.getHpCurrent());
            assertTrue(condition(fighter, "attached").isPresent());

            f.dice.script(4);
            f.effectApplier.effectsAtTurnStart(f.encounter, monster);
            assertEquals(42, fighter.getHpCurrent());
            assertTrue(condition(fighter, "attached").isEmpty());
            assertTrue(logged("attached ends on Fighter (Horror's Blood Drain ends)"));
            // Nothing drains on the fighter's own turn
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(42, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("A creature that saves against Frightful Presence is immune to it; one that failed becomes "
                + "immune once its fear ends (MM p.87)")
        void immuneAfterSave() {
            EncounterParticipant rogue = f.player("Rogue", 30, 12);
            act("Frightful Presence", fighter, 20);
            assertTrue(logged("Fighter is immune to Horror's Frightful Presence for the next 24 hours."));
            act("Frightful Presence", fighter);
            assertTrue(logged("Fighter is immune to Horror's Frightful Presence (for the next 24 hours after its "
                    + "save or once the effect ended for it)."));

            act("Frightful Presence", rogue, 2);
            assertTrue(condition(rogue, "frightened").isPresent());
            // Still frightened, so not immune yet: it saves again
            act("Frightful Presence", rogue, 3);
            f.effectApplier.removeCondition(f.encounter, rogue, "frightened");
            act("Frightful Presence", rogue);
            assertTrue(condition(rogue, "frightened").isEmpty());
        }

        @Test
        @DisplayName("A ghoul's paralysis passes over undead and elves, with no save rolled (MM p.148)")
        void exemptTypes() {
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("Undead");
            act("Claws", zombie, 15, 1, 1);
            assertTrue(CombatFixture.conditionNames(zombie).isEmpty());
            assertTrue(logged("Claws doesn't leave Zombie paralyzed: it passes over elf and undead creatures."));

            EncounterParticipant drow = f.monster("Drow", 13, 10);
            drow.getMonster().setType("Humanoid");
            drow.getMonster().setSubtype("elf");
            act("Claws", drow, 15, 1, 1);
            assertTrue(CombatFixture.conditionNames(drow).isEmpty());

            act("Claws", fighter, 15, 1, 1, 2);
            assertTrue(condition(fighter, "paralyzed").isPresent());
        }

        @Test
        @DisplayName("A banshee's wail has no effect on constructs and undead: they make no save (MM p.23)")
        void wholeActionExempt() {
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("Undead");
            act("Wail", zombie);
            assertEquals(22, zombie.getHpCurrent());
            assertTrue(logged("Wail has no effect on Zombie: it passes over construct and undead creatures."));
        }
    }

    @Nested
    @DisplayName("Step 5: a grapple's limit and the rolled escape")
    class Step5 {

        @Test
        @DisplayName("A constriction holds one creature: another is refused while it lasts, the held one isn't, and the "
                + "restraint ends with the grapple (MM p.324)")
        void oneTargetAtATime() {
            EncounterParticipant rogue = f.player("Rogue", 30, 10);
            act("Constrict", fighter, 15, 3, 3);
            ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
            assertEquals("Constrict", grappled.sourceAction());
            assertTrue(grappled.repeatSave().escapes());
            assertEquals(16, grappled.repeatSave().dc());
            assertEquals(grappled.id(), condition(fighter, "restrained").orElseThrow().linkedTo());

            f.dice.script(15, 3, 3);
            ConflictException refused = assertThrows(ConflictException.class, () -> f.monsterActions.monsterAction(
                    f.encounterId(), new MonsterActionRequest(monster.getId(), "Constrict", "ACTION",
                            List.of(rogue.getId()), null, null, null, null, null), f.dmUserId));
            assertEquals("Horror's Constrict already holds Fighter, and it can't be used on another creature until "
                    + "that grapple ends", refused.getMessage());
            // The held fighter is restrained, so the attack has advantage: two d20s
            act("Constrict", fighter, 15, 15, 3, 3);
            assertEquals(48, fighter.getHpCurrent());

            f.effectApplier.removeCondition(f.encounter, fighter, "grappled");
            act("Constrict", rogue, 15, 3, 3);
            assertTrue(condition(rogue, "grappled").isPresent());
        }

        @Test
        @DisplayName("A chain grapples only while the devil grapples no one else: another target is hit, but not "
                + "grappled or restrained (MM p.72)")
        void limitWithoutRefusal() {
            EncounterParticipant rogue = f.player("Rogue", 30, 10);
            act("Chain", fighter, 15, 3, 3);
            act("Chain", rogue, 15, 3, 3);
            assertEquals(24, rogue.getHpCurrent());
            assertTrue(CombatFixture.conditionNames(rogue).isEmpty());
            assertTrue(logged("Horror already holds 1 creature with Chain, so Rogue isn't grappled."));
        }

        @Test
        @DisplayName("The escape is the creature's better of Athletics and Acrobatics against the escape DC, with its "
                + "action, and a success ends the restraint too (PHB p.195)")
        void escape() {
            act("Constrict", fighter, 15, 3, 3);
            ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
            f.turnOf(fighter);
            CombatFixture.newTurn(fighter);
            f.dice.script(12);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(), grappled.id()),
                    f.dmUserId);
            assertTrue(logged("Fighter uses its action to escape the grapple (Horror's Constrict): Strength "
                    + "(Athletics) check d20(12) + 0 = 12 vs DC 16 — fails, and stays grappled."));
            assertTrue(condition(fighter, "grappled").isPresent());

            fighter.getCharacter().setDexterity(14);
            CombatFixture.newTurn(fighter);
            f.dice.script(14);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(), grappled.id()),
                    f.dmUserId);
            assertTrue(logged("Dexterity (Acrobatics) check d20(14) + 2 = 16 vs DC 16 — escapes, and grappled ends."));
            assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
        }

        @Test
        @DisplayName("A poisoned creature makes its escape check with disadvantage (PHB p.292)")
        void escapeWhilePoisoned() {
            act("Constrict", fighter, 15, 3, 3);
            f.effectApplier.addCondition(f.encounter, fighter, "poisoned", null);
            ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
            f.turnOf(fighter);
            CombatFixture.newTurn(fighter);
            f.dice.script(18, 6);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(), grappled.id()),
                    f.dmUserId);
            assertTrue(logged("2d20(18, 6) with disadvantage from poisoned → 6 + 0 = 6 vs DC 16 — fails"));
        }

        @Test
        @DisplayName("A grapple ends when its grappler is incapacitated, its restraint with it (PHB p.290)")
        void grapplerIncapacitated() {
            act("Constrict", fighter, 15, 3, 3);
            f.effectApplier.addCondition(f.encounter, monster, "stunned", 1);
            assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
            assertTrue(logged("grappled ends on Fighter (Horror is stunned, so it can't hold the grapple)"));
        }

        @Test
        @DisplayName("A net is broken with a Strength check made as an action against its DC")
        void netCheck() {
            act("Net", fighter, 15);
            ConditionEntry restrained = condition(fighter, "restrained").orElseThrow();
            assertTrue(restrained.repeatSave().madeByAction());
            assertTrue(restrained.repeatSave().madeAsCheck());
            f.turnOf(fighter);
            CombatFixture.newTurn(fighter);
            f.dice.script(11);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(),
                    restrained.id()), f.dmUserId);
            assertTrue(condition(fighter, "restrained").isEmpty());
            assertTrue(logged("Fighter uses its action on a STR check against restrained: d20(11) + 0 = 11 vs DC 10"));
        }
    }
}
