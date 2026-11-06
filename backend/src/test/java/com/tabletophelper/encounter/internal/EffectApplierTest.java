package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.HealRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The EffectApplier (D160) applies each rule of D161 to D163 on every path that changes a participant: a
 * weapon attack, a spell, a monster action, a monster spell and the DM's changes by hand. Each situation runs
 * once per path it can arise on, over scripted dice; the damage is flat, so a path deals exactly the amount
 * asked for. The old CombatServiceTest cases about damage, dying and concentration, 10.1 to 10.6 and 10.22 to
 * 10.25, have a counterpart here on every path, and on the manual path in HitPointServiceTest.
 */
class EffectApplierTest {

    /** A way to change a participant. */
    enum Path { WEAPON, SPELL, MONSTER_ACTION, MONSTER_SPELL, MANUAL }

    private CombatFixture f;
    private EncounterParticipant rogue;
    private EncounterParticipant wizard;
    private EncounterParticipant ogre;
    private EncounterParticipant mage;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rogue = f.player("Rogue", 30, 14);
        wizard = f.player("Wizard", 30, 12);
        wizard.setSpellAttackBonus(5);
        wizard.setSpellSaveDc(15);
        wizard.setSpellSlotsCurrent(Map.of("1", new SpellSlot(9, 0)));
        ogre = f.monster("Ogre", 59, 11);
        mage = f.monster("Mage", 40, 12);
        mage.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"spellcasting": {"ability": "INT", "attackBonus": 5, "saveDC": 15, "slots": {"1": 9},
                 "spellsByLevel": {"1": ["Test Spell"]}}}"""));
    }

    // ── Paths ────────────────────────────────────────────────────

    /** Deals exactly amount damage of the type, not a critical hit, then rolls any further dice given. */
    private void damage(Path path, EncounterParticipant target, int amount, String type, int... laterRolls) {
        String dice = String.valueOf(amount);
        switch (path) {
            case WEAPON -> {
                // Beyond 5 feet an unconscious target is attacked normally, so one d20 decides the hit
                f.dice.script(concat(new int[]{15}, laterRolls));
                f.attacks.rollAttack(f.encounterId(), attack(target, dice, type, false), rogue.getId(), f.dmUserId);
            }
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, spellTemplate("AUTO_HIT", dice, type, "null", ""));
                f.dice.script(laterRolls);
                cast(path, target);
            }
            case MONSTER_ACTION -> {
                ogreAction("{\"name\": \"Slam\", \"deliveryMethod\": \"AUTO_DAMAGE\", \"effects\": "
                        + damageEffects(dice, type) + "}");
                f.dice.script(laterRolls);
                f.monsterActions.monsterAction(f.encounterId(), monsterAction("Slam", target), f.dmUserId);
            }
            case MANUAL -> {
                f.dice.script(laterRolls);
                DamageRequest request = new DamageRequest();
                request.setTargetId(target.getId());
                request.setAmount(amount);
                request.setDamageType(type);
                f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
            }
        }
    }

    /** A critical hit for amount damage: a natural 20 on an attack roll from beyond 5 feet. */
    private void criticalHit(Path path, EncounterParticipant target, int amount) {
        String dice = String.valueOf(amount);
        f.dice.script(20);
        switch (path) {
            case WEAPON -> f.attacks.rollAttack(f.encounterId(), attack(target, dice, null, false), rogue.getId(),
                    f.dmUserId);
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, spellTemplate("SPELL_ATTACK", dice, null, "null",
                        ", \"spellAttackType\": \"RANGED\""));
                cast(path, target);
            }
            case MONSTER_ACTION -> {
                ogreAction("{\"name\": \"Rock\", \"deliveryMethod\": \"ATTACK_ROLL\", \"attackBonus\": 5, "
                        + "\"attackType\": \"RANGED_WEAPON\", \"effects\": " + damageEffects(dice, null) + "}");
                f.monsterActions.monsterAction(f.encounterId(), monsterAction("Rock", target), f.dmUserId);
            }
            case MANUAL -> throw new IllegalArgumentException("The DM's damage is never a critical hit");
        }
    }

    /** An attack roll for 5 flat damage, with the d20s given, from within 5 feet or beyond. */
    private void attackRoll(Path path, EncounterParticipant target, boolean withinFiveFeet, int... d20s) {
        f.dice.script(d20s);
        switch (path) {
            case WEAPON -> f.attacks.rollAttack(f.encounterId(), attack(target, "5", null, withinFiveFeet),
                    rogue.getId(), f.dmUserId);
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, spellTemplate("SPELL_ATTACK", "5", null, "null",
                        ", \"spellAttackType\": \"" + (withinFiveFeet ? "MELEE" : "RANGED") + "\""));
                cast(path, target);
            }
            case MONSTER_ACTION -> {
                ogreAction("{\"name\": \"Claw\", \"deliveryMethod\": \"ATTACK_ROLL\", \"attackBonus\": 5, "
                        + "\"attackType\": \"" + (withinFiveFeet ? "MELEE_WEAPON" : "RANGED_WEAPON")
                        + "\", \"effects\": " + damageEffects("5", null) + "}");
                f.monsterActions.monsterAction(f.encounterId(), monsterAction("Claw", target), f.dmUserId);
            }
            case MANUAL -> throw new IllegalArgumentException("The DM's damage has no attack roll");
        }
    }

    /** A DC 15 saving throw of the ability, for the damage, with the conditions on a failure. */
    private void savingThrow(Path path, List<EncounterParticipant> targets, String ability, boolean half,
                             String dice, String type, List<String> conditions, int... rolls) {
        f.dice.script(rolls);
        String conditionJson = conditions.isEmpty() ? "null"
                : "[" + String.join(", ", conditions.stream().map(c -> "\"" + c + "\"").toList()) + "]";
        switch (path) {
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, spellTemplate("SAVING_THROW", dice, type, conditionJson,
                        ", \"saveAbility\": \"" + ability + "\", \"halfOnSave\": " + half));
                cast(path, targets.toArray(EncounterParticipant[]::new));
            }
            case MONSTER_ACTION -> {
                StringBuilder effects = new StringBuilder("[");
                effects.append("{\"effectType\": \"DAMAGE\", \"damageDice\": \"").append(dice).append("\"")
                        .append(type != null ? ", \"damageType\": \"" + type + "\"" : "").append("}");
                for (String condition : conditions) {
                    effects.append(", {\"effectType\": \"CONDITION\", \"condition\": \"").append(condition).append("\"}");
                }
                ogreAction("{\"name\": \"Roar\", \"deliveryMethod\": \"SAVING_THROW\", \"saveDC\": 15, "
                        + "\"saveAbility\": \"" + ability + "\", \"halfOnSave\": " + half + ", \"effects\": "
                        + effects.append("]") + "}");
                f.monsterActions.monsterAction(f.encounterId(),
                        monsterAction("Roar", targets.toArray(EncounterParticipant[]::new)), f.dmUserId);
            }
            default -> throw new IllegalArgumentException(path + " has no saving throw");
        }
    }

    /** Heals amount: a spell that needs no roll, or the DM's healing. */
    private void heal(Path path, EncounterParticipant target, int amount) {
        f.dice.script();
        switch (path) {
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, """
                        {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "concentration": false, "effects": [],
                         "healing": {"healingDice": "%d"}, "conditionsInflicted": null}""".formatted(amount));
                cast(path, target);
            }
            case MANUAL -> {
                HealRequest request = new HealRequest();
                request.setTargetId(target.getId());
                request.setAmount(amount);
                f.hitPoints.applyHealing(f.encounterId(), request, null, f.dmUserId);
            }
            default -> throw new IllegalArgumentException(path + " can't heal");
        }
    }

    /** Gives the target a condition: an Effect that needs no roll, or the DM's condition. */
    private void condition(Path path, EncounterParticipant target, String condition) {
        f.dice.script();
        switch (path) {
            case SPELL, MONSTER_SPELL -> {
                f.spell("Test Spell", 1, spellTemplate("AUTO_HIT", null, null, "[\"" + condition + "\"]", ""));
                cast(path, target);
            }
            case MONSTER_ACTION -> {
                ogreAction("{\"name\": \"Gaze\", \"deliveryMethod\": \"AUTO_HIT\", \"effects\": "
                        + "[{\"effectType\": \"CONDITION\", \"condition\": \"" + condition + "\"}]}");
                f.monsterActions.monsterAction(f.encounterId(), monsterAction("Gaze", target), f.dmUserId);
            }
            case MANUAL -> {
                ConditionRequest request = new ConditionRequest();
                request.setTargetId(target.getId());
                request.setCondition(condition);
                f.conditionService.addCondition(f.encounterId(), request, f.dmUserId);
            }
            default -> throw new IllegalArgumentException(path + " gives no condition");
        }
    }

    // The wizard or the mage casts the level 1 Test Spell with a level 1 slot, through the one cast path (D165)
    private void cast(Path path, EncounterParticipant... targets) {
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName("Test Spell");
        request.setSlotLevel(1);
        request.setTargetIds(Arrays.stream(targets).map(EncounterParticipant::getId).toList());
        if (path == Path.SPELL) {
            f.knows(wizard, "Test Spell");
            f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), f.dmUserId);
        } else {
            f.spellcasting.castSpell(f.encounterId(), request, mage.getId(), f.dmUserId);
        }
    }

    private static AttackRollRequest attack(EncounterParticipant target, String dice, String type, boolean within5Ft) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setAttackBonus(30);
        request.setDamageDice(dice);
        request.setDamageType(type);
        request.setWithin5Ft(within5Ft);
        return request;
    }

    // The ogre's action against the targets
    private MonsterActionRequest monsterAction(String name, EncounterParticipant... targets) {
        return new MonsterActionRequest(ogre.getId(), name, "ACTION",
                Arrays.stream(targets).map(EncounterParticipant::getId).toList(), null, null, null, null);
    }

    private void ogreAction(String action) {
        ogre.getMonster().setActionTemplates(MonsterTemplates.parse("{\"actions\": [" + action + "]}"));
    }

    private static String damageEffects(String dice, String type) {
        return "[{\"effectType\": \"DAMAGE\", \"damageDice\": \"" + dice + "\""
                + (type != null ? ", \"damageType\": \"" + type + "\"" : "") + "}]";
    }

    private static String spellTemplate(String delivery, String dice, String type, String conditions, String extra) {
        String effects = dice == null ? "[]" : damageEffects(dice, type);
        return "{\"spellLevel\": 1, \"deliveryMethod\": \"" + delivery + "\", \"concentration\": false, "
                + "\"effects\": " + effects + ", \"conditionsInflicted\": " + conditions + extra + "}";
    }

    private static int[] concat(int[] first, int[] second) {
        int[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static ConcentrationInstance concentrate(EncounterParticipant caster, String spell) {
        ConcentrationInstance instance = ConcentrationInstance.start(spell, 2, 1, 10);
        caster.setConcentration(instance);
        return instance;
    }

    // ── Damage types ─────────────────────────────────────────────

    @Nested
    @DisplayName("Damage types (D161)")
    class DamageTypes {

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Resistance halves the damage, rounding down")
        void resistance(Path path) {
            EncounterParticipant elemental = f.monster("Elemental", 50, 10);
            elemental.getMonster().setDamageResistances("[\"fire\"]");

            damage(path, elemental, 13, "fire");

            assertEquals(44, elemental.getHpCurrent());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Immunity makes the damage 0")
        void immunity(Path path) {
            EncounterParticipant elemental = f.monster("Elemental", 50, 10);
            elemental.getMonster().setDamageImmunities("[\"fire\"]");

            damage(path, elemental, 13, "fire");

            assertEquals(50, elemental.getHpCurrent());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Vulnerability doubles the damage")
        void vulnerability(Path path) {
            EncounterParticipant skeleton = f.monster("Skeleton", 50, 13);
            skeleton.getMonster().setDamageVulnerabilities("[\"bludgeoning\"]");

            damage(path, skeleton, 13, "bludgeoning");

            assertEquals(24, skeleton.getHpCurrent());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A PC's resistances count, and a conditional stat block entry is the DM's call")
        void playerResistanceAndConditionalEntries(Path path) {
            EncounterParticipant tiefling = f.player("Tiefling", 40, 12);
            tiefling.getCharacter().setDamageResistances(List.of("fire"));
            EncounterParticipant golem = f.monster("Golem", 50, 10);
            golem.getMonster().setDamageResistances(
                    "[\"{\\\"resist\\\":[\\\"bludgeoning\\\"],\\\"note\\\":\\\"from nonmagical attacks\\\",\\\"cond\\\":true}\"]");

            damage(path, tiefling, 9, "fire");
            damage(path, golem, 9, "bludgeoning");

            assertEquals(36, tiefling.getHpCurrent());
            assertEquals(41, golem.getHpCurrent());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Untyped damage meets no defence")
        void untypedDamage(Path path) {
            EncounterParticipant elemental = f.monster("Elemental", 50, 10);
            elemental.getMonster().setDamageImmunities("[\"fire\"]");

            damage(path, elemental, 13, null);

            assertEquals(37, elemental.getHpCurrent());
        }
    }

    // ── Saving throws ────────────────────────────────────────────

    @Nested
    @DisplayName("Saving throws (D161)")
    class SavingThrows {

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Half of 1 damage is 0 on a successful save")
        void halvingHasNoMinimum(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);

            savingThrow(path, List.of(goblin), "DEX", true, "1", "fire", List.of(), 20);

            assertEquals(7, goblin.getHpCurrent());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Area damage is rolled once, and each target saves and resists on its own")
        void areaDamageRolledOnce(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 40, 15);
            EncounterParticipant elemental = f.monster("Elemental", 40, 10);
            elemental.getMonster().setDamageResistances("[\"fire\"]");

            // 4d6 rolled once, then the goblin fails and the elemental succeeds
            savingThrow(path, List.of(goblin, elemental), "DEX", true, "4d6", "fire", List.of(), 6, 5, 4, 3, 2, 18);

            assertEquals(40 - 18, goblin.getHpCurrent());
            // Half of 18 is 9, and resistance halves it again to 4
            assertEquals(40 - 4, elemental.getHpCurrent());
            assertEquals(0, f.dice.remaining());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("STR and DEX saves fail automatically while paralyzed, with no roll")
        void automaticFailure(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 40, 15);
            goblin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("paralyzed", null, 1))));

            savingThrow(path, List.of(goblin), "DEX", true, "10", "fire", List.of());

            assertEquals(30, goblin.getHpCurrent());
            CombatLog entry = f.entriesAbout(goblin).getFirst();
            assertNull(entry.getRollValue());
            assertTrue(entry.getDescription().contains("fails automatically"), entry.getDescription());
        }
    }

    // ── Hit points and dying ─────────────────────────────────────

    @Nested
    @DisplayName("Hit points and dying (D161)")
    class Dying {

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Temporary HP absorb damage first (CombatServiceTest 10.2)")
        void temporaryHitPoints(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            fighter.setHpTemp(10);

            damage(path, fighter, 15, null);

            assertEquals(0, fighter.getHpTemp());
            assertEquals(25, fighter.getHpCurrent());
            assertEquals(LifeState.ALIVE, fighter.getLifeState());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A PC dropping to 0 is Dying, unconscious and prone, with its death saves reset (10.3)")
        void pcDropsToZero(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 10, 18);
            fighter.setDeathSaveFailures(2);

            damage(path, fighter, 15, null);

            assertEquals(0, fighter.getHpCurrent());
            assertEquals(LifeState.DYING, fighter.getLifeState());
            assertEquals(0, fighter.getDeathSaveFailures());
            assertEquals(List.of("unconscious", "prone"), CombatFixture.conditionNames(fighter));
            assertEquals(EndRule.HP_REGAINED, fighter.getActiveConditions().getFirst().endRule());
            assertTrue(f.descriptions().contains("Fighter drops to 0 HP, falls unconscious and prone, and is dying"));
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A PC dies outright when the damage left after reaching 0 is at least its HP maximum")
        void massiveDamageFromPositiveHp(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 20, 18);
            fighter.setHpCurrent(10);

            damage(path, fighter, 30, null);

            assertEquals(LifeState.DEAD, fighter.getLifeState());
            assertTrue(f.descriptions().contains("Fighter drops to 0 HP with massive damage and dies instantly"));
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Damage to a Dying PC adds one death save failure (10.5)")
        void damageAtZeroAddsAFailure(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 1);

            damage(path, fighter, 5, null);

            assertEquals(2, fighter.getDeathSaveFailures());
            assertEquals(LifeState.DYING, fighter.getLifeState());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("A critical hit on a Dying PC adds two failures")
        void criticalHitAtZeroAddsTwoFailures(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            criticalHit(path, fighter, 5);

            assertEquals(2, fighter.getDeathSaveFailures());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Damage makes a Stable PC Dying again")
        void stableBecomesDying(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.STABLE, 0);

            damage(path, fighter, 5, null);

            assertEquals(LifeState.DYING, fighter.getLifeState());
            assertEquals(1, fighter.getDeathSaveFailures());
            assertTrue(f.descriptions().contains("Fighter suffers 1 death save failure (1/3) and is no longer stable"));
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Damage of at least its HP maximum kills a PC at 0 HP (10.4)")
        void massiveDamageAtZero(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            damage(path, fighter, 30, null);

            assertEquals(LifeState.DEAD, fighter.getLifeState());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A monster that drops to 0 is Dead, with no unconscious or prone (10.3)")
        void monsterDies(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);

            damage(path, goblin, 9, null);

            assertEquals(0, goblin.getHpCurrent());
            assertEquals(LifeState.DEAD, goblin.getLifeState());
            assertTrue(CombatFixture.conditionNames(goblin).isEmpty());
            assertTrue(f.descriptions().contains("Goblin is killed"));
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_SPELL", "MANUAL"})
        @DisplayName("Healing a Dying PC wakes it with its death saves reset; it stays prone (10.8)")
        void healingTheDying(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 1);
            fighter.setDeathSaveSuccesses(2);
            fighter.getActiveConditions().add(ConditionEntry.manual("unconscious", null, 1));

            heal(path, fighter, 5);

            assertEquals(5, fighter.getHpCurrent());
            assertEquals(LifeState.ALIVE, fighter.getLifeState());
            assertEquals(0, fighter.getDeathSaveSuccesses());
            assertEquals(0, fighter.getDeathSaveFailures());
            // Only the unconsciousness dropping to 0 gave ends; one from elsewhere stays, and so does prone
            assertEquals(List.of("prone", "unconscious"), CombatFixture.conditionNames(fighter));
            assertEquals(EndRule.MANUAL, fighter.getActiveConditions().get(1).endRule());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_SPELL", "MANUAL"})
        @DisplayName("Healing can't reach the dead: a 409 that changes nothing (10.9 changed by D161)")
        void healingTheDead(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.dead(fighter);
            fighter.setDeathSaveFailures(3);

            assertThrows(ConflictException.class, () -> heal(path, fighter, 10));

            assertEquals(0, fighter.getHpCurrent());
            assertEquals(LifeState.DEAD, fighter.getLifeState());
        }
    }

    // ── Attacks on downed targets ────────────────────────────────

    @Nested
    @DisplayName("Attack rolls against downed targets (D161)")
    class DownedTargets {

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Within 5 feet, an unconscious PC is attacked with advantage and a hit is a critical: two failures")
        void unconsciousWithinFiveFeet(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            attackRoll(path, fighter, true, 3, 17);

            assertEquals(17, f.entriesAbout(fighter).getFirst().getRollValue());
            assertEquals(2, fighter.getDeathSaveFailures());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Beyond 5 feet, advantage and the prone disadvantage cancel and a hit isn't critical: one failure")
        void unconsciousBeyondFiveFeet(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 18);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            attackRoll(path, fighter, false, 17);

            assertEquals(17, f.entriesAbout(fighter).getFirst().getRollValue());
            assertEquals(1, fighter.getDeathSaveFailures());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("A paralyzed target gives advantage from anywhere, and a critical only from within 5 feet")
        void paralyzedTarget(Path path) {
            EncounterParticipant near = f.monster("Near Goblin", 40, 15);
            near.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("paralyzed", null, 1))));
            EncounterParticipant far = f.monster("Far Goblin", 40, 15);
            far.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("paralyzed", null, 1))));

            attackRoll(path, near, true, 3, 17);
            attackRoll(path, far, false, 3, 17);

            assertTrue(f.entriesAbout(near).getFirst().getDescription().contains("critical hit"));
            assertEquals(17, f.entriesAbout(far).getFirst().getRollValue());
            assertFalse(f.entriesAbout(far).getFirst().getDescription().contains("critical"));
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Attacks never hit automatically: a Dying PC can be missed")
        void downedTargetCanBeMissed(Path path) {
            EncounterParticipant fighter = f.player("Fighter", 30, 50);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            attackRoll(path, fighter, true, 2, 3);

            assertEquals(0, fighter.getDeathSaveFailures());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"WEAPON", "SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("The dead can't be attacked: a 409 that rolls nothing and changes nothing")
        void deadCannotBeAttacked(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);
            CombatFixture.dead(goblin);

            ConflictException ex = assertThrows(ConflictException.class, () -> attackRoll(path, goblin, true, 15));

            assertEquals("Goblin is dead", ex.getMessage());
            assertEquals(1, f.dice.remaining());
            assertTrue(f.entriesAbout(goblin).isEmpty());
        }
    }

    // ── Concentration ────────────────────────────────────────────

    @Nested
    @DisplayName("How applied conditions end (D162)")
    class ConditionEndRules {

        @Test
        @DisplayName("A monster's condition keeps its rounds and Repeat Save, counted on the monster's turns")
        void monsterConditionWithARepeatSave() {
            ogreAction("""
                    {"name": "Gaze", "deliveryMethod": "AUTO_HIT", "saveDC": 14, "saveAbility": "WIS",
                     "effects": [{"effectType": "CONDITION", "condition": "frightened", "durationRounds": 10,
                                  "saveToEndEachTurn": true}]}""");
            f.dice.script();

            f.monsterActions.monsterAction(f.encounterId(), monsterAction("Gaze", rogue), f.dmUserId);

            ConditionEntry frightened = rogue.getActiveConditions().getFirst();
            assertEquals(10, frightened.duration());
            assertEquals(EndRule.REPEAT_SAVE, frightened.endRule());
            assertEquals(new RepeatSave(Ability.WIS, 14, false), frightened.repeatSave());
            assertEquals(ogre.getId(), frightened.sourceParticipantId());
        }

        @Test
        @DisplayName("A spell's condition with rounds of its own ends after them at the start of its caster's turn")
        void spellConditionWithRounds() {
            f.spell("Test Spell", 1, spellTemplate("AUTO_HIT", null, null,
                    "[{\"condition\": \"poisoned\", \"durationRounds\": 1}]", ""));
            f.dice.script();

            cast(Path.SPELL, rogue);

            ConditionEntry poisoned = rogue.getActiveConditions().getFirst();
            assertEquals(1, poisoned.duration());
            assertEquals(EndRule.afterRounds(wizard.getId()), poisoned.endRule());
            assertNull(poisoned.repeatSave());
        }
    }

    @Nested
    @DisplayName("Condition immunities (D161)")
    class ConditionImmunities {

        @Test
        @DisplayName("A monster immune to a condition doesn't gain it from a monster's action, and its entry says so")
        void monsterImmuneToAnActionsCondition() {
            EncounterParticipant golem = f.monster("Golem", 50, 10);
            golem.getMonster().setConditionImmunities("[\"charmed\", \"frightened\", \"poisoned\"]");
            ogreAction("""
                    {"name": "Gaze", "deliveryMethod": "AUTO_HIT", "effects": [
                       {"effectType": "CONDITION", "condition": "frightened"},
                       {"effectType": "CONDITION", "condition": "prone"}]}""");
            f.dice.script();

            f.monsterActions.monsterAction(f.encounterId(), monsterAction("Gaze", golem), f.dmUserId);

            assertEquals(List.of("prone"), CombatFixture.conditionNames(golem));
            assertTrue(f.descriptions().contains("Ogre uses Gaze on Golem: gains prone, immune to frightened."));
        }

        @Test
        @DisplayName("A monster immune to a spell's condition doesn't gain it")
        void monsterImmuneToASpellsCondition() {
            EncounterParticipant golem = f.monster("Golem", 50, 10);
            golem.getMonster().setConditionImmunities("[\"Charmed\"]");
            f.spell("Test Spell", 1, spellTemplate("AUTO_HIT", null, null, "[\"charmed\"]", ""));
            f.dice.script();

            cast(Path.SPELL, golem);

            assertEquals(List.of(), CombatFixture.conditionNames(golem));
            assertTrue(f.descriptions().stream().anyMatch(d -> d.endsWith("Golem: immune to charmed.")));
        }

        @Test
        @DisplayName("A conditional immunity in a stat block, such as one while wearing a mask, is the DM's call")
        void conditionalMonsterImmunityDoesNotCount() {
            EncounterParticipant severin = f.monster("Severin", 50, 10);
            severin.getMonster().setConditionImmunities(
                    "[\"{\\\"conditionImmune\\\":[\\\"frightened\\\"],\\\"preNote\\\":\\\"While wearing the mask:\\\"}\"]");
            ogreAction("""
                    {"name": "Gaze", "deliveryMethod": "AUTO_HIT", "effects": [
                       {"effectType": "CONDITION", "condition": "frightened"}]}""");
            f.dice.script();

            f.monsterActions.monsterAction(f.encounterId(), monsterAction("Gaze", severin), f.dmUserId);

            assertEquals(List.of("frightened"), CombatFixture.conditionNames(severin));
        }

        @Test
        @DisplayName("A player character is immune to the conditions its sheet lists and those its race gives")
        void playerImmunitiesFromTheSheetAndTheRace() {
            rogue.getCharacter().setConditionImmunities(List.of("frightened"));
            rogue.getCharacter().setRaceRef(Race.builder().name("Yuan-ti Pureblood")
                    .resistances("[\"condition immunity: poisoned\"]").build());
            ogreAction("""
                    {"name": "Gaze", "deliveryMethod": "AUTO_HIT", "effects": [
                       {"effectType": "CONDITION", "condition": "frightened"},
                       {"effectType": "CONDITION", "condition": "poisoned"},
                       {"effectType": "CONDITION", "condition": "blinded"}]}""");
            f.dice.script();

            f.monsterActions.monsterAction(f.encounterId(), monsterAction("Gaze", rogue), f.dmUserId);

            assertEquals(List.of("blinded"), CombatFixture.conditionNames(rogue));
            assertTrue(f.descriptions().contains(
                    "Ogre uses Gaze on Rogue: gains blinded, immune to frightened, poisoned."));
        }

        @Test
        @DisplayName("The DM can still add a condition by hand to a creature immune to it")
        void manualConditionIgnoresImmunity() {
            EncounterParticipant golem = f.monster("Golem", 50, 10);
            golem.getMonster().setConditionImmunities("[\"frightened\"]");
            ConditionRequest request = new ConditionRequest();
            request.setTargetId(golem.getId());
            request.setCondition("frightened");

            f.conditionService.addCondition(f.encounterId(), request, f.dmUserId);

            assertEquals(List.of("frightened"), CombatFixture.conditionNames(golem));
        }
    }

    @Nested
    @DisplayName("Concentration (D162)")
    class Concentration {

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Each damage application makes one CON save, DC 10 or half the damage; a failure ends it (10.6, 10.24)")
        void oneSavePerDamageApplication(Path path) {
            EncounterParticipant cleric = f.player("Cleric", 40, 16);
            concentrate(cleric, "Bless");

            // 14 damage: DC 10, and a roll of 8 with CON +0 fails
            damage(path, cleric, 14, null, 8);

            assertNull(cleric.getConcentration());
            assertEquals(1, f.entries(CombatActionType.CONCENTRATION_CHECK).size());
            assertTrue(f.descriptions().contains("Cleric loses concentration on Bless (failed concentration check)"));
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A save at the DC keeps concentration; 20 damage still makes the DC 10 (10.22)")
        void saveAtTheDcKeepsConcentration(Path path) {
            EncounterParticipant cleric = f.player("Cleric", 40, 16);
            ConcentrationInstance instance = concentrate(cleric, "Bless");

            damage(path, cleric, 20, null, 10);

            assertEquals(instance, cleric.getConcentration());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("Dropping to 0 ends concentration with no save (10.25)")
        void zeroHpEndsConcentration(Path path) {
            EncounterParticipant cleric = f.player("Cleric", 20, 16);
            cleric.setHpCurrent(5);
            concentrate(cleric, "Bless");

            damage(path, cleric, 10, null);

            assertEquals(LifeState.DYING, cleric.getLifeState());
            assertNull(cleric.getConcentration());
            assertTrue(f.entries(CombatActionType.CONCENTRATION_CHECK).isEmpty());
        }

        @ParameterizedTest
        @EnumSource(Path.class)
        @DisplayName("A monster's concentration ends when it dies")
        void deathEndsConcentration(Path path) {
            EncounterParticipant shaman = f.monster("Shaman", 5, 12);
            concentrate(shaman, "Hold Person");

            damage(path, shaman, 10, null);

            assertNull(shaman.getConcentration());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL", "MANUAL"})
        @DisplayName("An incapacitating condition ends concentration")
        void incapacitationEndsConcentration(Path path) {
            EncounterParticipant cleric = f.player("Cleric", 40, 16);
            concentrate(cleric, "Bless");

            condition(path, cleric, "stunned");

            assertNull(cleric.getConcentration());
            assertTrue(f.descriptions().contains("Cleric loses concentration on Bless (stunned)"));
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_SPELL"})
        @DisplayName("A new casting ends the caster's instance first, removing exactly the conditions it created")
        void newCastingEndsTheOldInstance(Path path) {
            EncounterParticipant caster = path == Path.SPELL ? wizard : mage;
            EncounterParticipant first = f.monster("Goblin", 20, 15);
            EncounterParticipant second = f.monster("Orc", 20, 13);
            EncounterParticipant cleric = f.player("Cleric", 30, 16);
            ConcentrationInstance clerics = concentrate(cleric, "Hold Person");
            first.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.fromEffect("paralyzed", 10, 1,
                    "Hold Person", cleric.getId(), clerics))));
            f.spell("Test Spell", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "halfOnSave": false,
                     "concentration": true, "durationRounds": 10, "effects": [], "conditionsInflicted": ["paralyzed"]}""");

            f.dice.script(2);
            cast(path, first);
            ConcentrationInstance firstCasting = caster.getConcentration();
            f.dice.script(2);
            cast(path, second);

            assertNotEquals(firstCasting.id(), caster.getConcentration().id());
            // The goblin keeps the cleric's paralysis and loses the first casting's
            assertEquals(1, first.getActiveConditions().size());
            assertEquals(clerics.id(), first.getActiveConditions().getFirst().concentrationInstanceId());
            assertEquals(caster.getConcentration().id(), second.getActiveConditions().getFirst().concentrationInstanceId());
            assertEquals(EndRule.CONCENTRATION, second.getActiveConditions().getFirst().endRule());
        }
    }

    // ── Legendary Resistance and the log ─────────────────────────

    @Nested
    @DisplayName("Legendary Resistance (D163) and the log (D157)")
    class LegendaryResistanceAndLog {

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("A failed save by a creature with uses left offers Legendary Resistance, recording the success outcome")
        void offeredOnAFailedSave(Path path) {
            EncounterParticipant dragon = f.monster("Dragon", 200, 19);
            dragon.getMonster().setDamageResistances("[\"cold\"]");
            dragon.setResourcePoolsCurrent(CombatFixture.legendaryResistance(2));

            savingThrow(path, List.of(dragon), "CON", true, "20", "cold", List.of("restrained"), 3);

            CombatLog entry = f.entriesAbout(dragon).getFirst();
            assertTrue(entry.getLegendaryResistanceEligible());
            assertFalse(entry.getLrResolved());
            assertEquals(dragon.getId(), entry.getLrTargetId());
            // 20 cold resisted is 10; a success would have been 10 halved and resisted, 5
            assertEquals(10, entry.getLrDamageDealt());
            assertEquals(5, entry.getLrSuccessDamage());
            assertEquals(List.of(dragon.getActiveConditions().getFirst().id()), entry.getLrConditionIds());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Legendary Resistance isn't offered on a success, or without uses left")
        void notOfferedOtherwise(Path path) {
            EncounterParticipant dragon = f.monster("Dragon", 200, 19);
            dragon.setResourcePoolsCurrent(CombatFixture.legendaryResistance(2));
            EncounterParticipant spent = f.monster("Spent Dragon", 200, 19);
            spent.setResourcePoolsCurrent(CombatFixture.legendaryResistance(0));

            savingThrow(path, List.of(dragon, spent), "CON", true, "20", null, List.of(), 18, 3);

            assertNull(f.entriesAbout(dragon).getFirst().getLegendaryResistanceEligible());
            assertNull(f.entriesAbout(spent).getFirst().getLegendaryResistanceEligible());
        }

        @ParameterizedTest
        @EnumSource(value = Path.class, names = {"SPELL", "MONSTER_ACTION", "MONSTER_SPELL"})
        @DisplayName("Each target gets an entry of its own, and the entry for the whole Effect names no target")
        void entryPerTargetAndSummary(Path path) {
            EncounterParticipant goblin = f.monster("Goblin", 30, 15);
            EncounterParticipant bugbear = f.monster("Bugbear", 30, 16);
            bugbear.setIsVisibleToPlayers(false);

            savingThrow(path, List.of(goblin, bugbear), "DEX", true, "10", "fire", List.of(), 3, 3);

            assertEquals(1, f.entriesAbout(goblin).size());
            assertEquals(1, f.entriesAbout(bugbear).size());
            List<CombatLog> summaries = f.log.stream()
                    .filter(entry -> entry.getTargetId() == null
                            && entry.getActionType() != CombatActionType.SPELL_SLOT_USE)
                    .toList();
            assertEquals(1, summaries.size());
            assertFalse(summaries.getFirst().getDescription().contains("Bugbear"));
            assertFalse(summaries.getFirst().getDescription().contains("Goblin"));
            assertTrue(f.entriesAbout(bugbear).getFirst().getDescription().contains("Bugbear"));
        }

        @Test
        @DisplayName("An attack entered by hand and the DM's damage write one entry about their target, and the "
                + "attack's Manual Modifier entry names none (D164)")
        void singleTargetPathsWriteOneEntry() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 15);

            damage(Path.WEAPON, goblin, 4, "slashing");
            damage(Path.MANUAL, goblin, 3, null);

            assertEquals(List.of(
                    "Manual Modifier (DM): Rogue's attack uses attack bonus +30 and 4 slashing damage.",
                    "Rogue attacks Goblin: d20(15) + 30 = 45 vs AC 15 — hit, 4 slashing damage.",
                    "DM deals 3 damage to Goblin"), f.descriptions());
            assertEquals(2, f.entriesAbout(goblin).size());
        }
    }
}
