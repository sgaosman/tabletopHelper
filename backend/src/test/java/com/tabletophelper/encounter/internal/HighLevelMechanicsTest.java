package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.rules.combat.DamageRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine extensions the 8th- and 9th-level spells brought (D190): disadvantage by creature type, an ability floor,
 * damage that ignores the save, escape checks, a per-creature end, round reminders, a cast roll, healing to full and from
 * a pool, critical hit ranges and dice, and damage immunity as an Ongoing Modifier.
 */
class HighLevelMechanicsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        f.effectApplier.setSpellRepository(f.spellRepository);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, null);
    }

    private boolean logged(String text) {
        return f.log.stream().anyMatch(entry -> entry.getDescription() != null && entry.getDescription().contains(text));
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("The new keys read back, and a bad one makes the template unreadable")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"unaffectedAtOrBelow": {"ability": "int", "max": 2},
                     "fullDamageOnSave": true,
                     "escapeCheck": {"ability": "INT", "dc": 20, "autoSucceed": ["Minotaur"]},
                     "roundReminders": {"2": "Acid rain", "10": "Freezing rain"},
                     "castRoll": {"dice": "1d4+1", "note": "turns"}, "healsToFull": true, "healingPool": 700,
                     "castsSpell": {"maxLevel": 8}, "critRange": 18, "critDamageDice": "12d12",
                     "effectTriggers": [{"when": ["END_OF_TURN"], "endsForCreatureOnSave": true}]}""");
            assertNull(template.unreadable());
            assertEquals(new SpellTemplate.AbilityFloor("INT", 2), template.unaffectedAtOrBelow());
            assertEquals(new SpellTemplate.EscapeCheck("INT", 20, List.of("minotaur")), template.escapeCheck());
            assertEquals(Map.of(2, "Acid rain", 10, "Freezing rain"), template.roundReminders());
            assertEquals(new SpellTemplate.CastRoll("1d4+1", "turns"), template.castRoll());
            assertTrue(template.healsToFull());
            assertEquals(700, template.healingPool());
            assertEquals(8, template.castsSpellUpTo());
            assertEquals(18, template.critRange());
            assertEquals("12d12", template.critDamageDice());
            assertTrue(template.triggerEndsForCreature(SpellTrigger.END_OF_TURN));
            assertFalse(template.triggerEndsForCreature(SpellTrigger.START_OF_TURN));

            assertNotNull(SpellTemplate.parse("{\"escapeCheck\": {\"ability\": \"INT\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"critRange\": 1}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"critDamageDice\": \"lots\"}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"castRoll\": {\"dice\": \"x\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"healingPool\": 0}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"roundReminders\": {\"two\": \"x\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"castsSpell\": {}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"unaffectedAtOrBelow\": {\"ability\": \"INT\"}}").unreadable());
        }
    }

    @Nested
    @DisplayName("Casting")
    class Casting {

        @Test
        @DisplayName("Sunburst: an undead saves with disadvantage, keeping the lower d20 (PHB p.279)")
        void disadvantageByType() {
            f.spell("Sunburst", 8, """
                    {"spellLevel": 8, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "saveDisadvantageTypes": ["undead", "ooze"],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "radiant"}],
                     "conditionsInflicted": [{"condition": "blinded", "durationRounds": 10}]}""");
            EncounterParticipant zombie = f.monster("Zombie", 40, 8);
            zombie.getMonster().setType("undead");
            EncounterParticipant ogre = f.monster("Ogre", 40, 11);
            // 2d6 (3, 3) = 6; the zombie rolls 20 and 2, keeping 2; the ogre rolls 20
            f.dice.script(3, 3, 20, 2, 20);
            cast("Sunburst", 8, List.of(zombie, ogre));

            assertEquals(34, zombie.getHpCurrent());
            assertTrue(CombatFixture.conditionNames(zombie).contains("blinded"));
            assertEquals(37, ogre.getHpCurrent());
            assertFalse(CombatFixture.conditionNames(ogre).contains("blinded"));
        }

        @Test
        @DisplayName("Psychic Scream has no effect on a creature with an Intelligence of 2 or lower (XGE p.163)")
        void abilityFloor() {
            f.spell("Psychic Scream", 9, """
                    {"spellLevel": 9, "deliveryMethod": "SAVING_THROW", "saveAbility": "INT", "halfOnSave": true,
                     "unaffectedAtOrBelow": {"ability": "INT", "max": 2},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "psychic"}],
                     "conditionsInflicted": ["stunned"]}""");
            EncounterParticipant wolf = f.monster("Wolf", 20, 13);
            wolf.getMonster().setIntelligence(2);
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            f.dice.script(4, 4, 1);
            cast("Psychic Scream", 9, List.of(wolf, mage));

            assertEquals(20, wolf.getHpCurrent());
            assertEquals(32, mage.getHpCurrent());
            assertTrue(CombatFixture.conditionNames(mage).contains("stunned"));
            assertTrue(logged("Psychic Scream has no effect on Wolf (INT 2 or lower)"));
        }

        @Test
        @DisplayName("Feeblemind's damage is taken whatever the save, which decides only the condition (PHB p.239)")
        void damageIgnoresSave() {
            f.spell("Feeblemind", 8, """
                    {"spellLevel": 8, "deliveryMethod": "SAVING_THROW", "saveAbility": "INT", "halfOnSave": false,
                     "fullDamageOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "psychic"}],
                     "conditionsInflicted": ["feebleminded"]}""");
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            // 4d6 of 2s = 8 taken, and a save of 20 succeeds
            f.dice.script(2, 2, 2, 2, 20);
            cast("Feeblemind", 8, List.of(mage));
            assertEquals(32, mage.getHpCurrent());
            assertFalse(CombatFixture.conditionNames(mage).contains("feebleminded"));

            f.dice.script(2, 2, 2, 2, 1);
            cast("Feeblemind", 8, List.of(mage));
            assertEquals(24, mage.getHpCurrent());
            assertTrue(CombatFixture.conditionNames(mage).contains(EffectApplier.FEEBLEMINDED));
        }

        @Test
        @DisplayName("Power Word Heal restores all hit points and ends stunned and charmed (PHB p.266)")
        void healsToFull() {
            f.spell("Power Word Heal", 9, """
                    {"spellLevel": 9, "deliveryMethod": "AUTO_HIT", "healsToFull": true,
                     "removesConditions": ["charmed", "frightened", "paralyzed", "stunned"]}""");
            EncounterParticipant fighter = f.player("Fighter", 50, 16);
            fighter.setHpCurrent(7);
            f.effectApplier.addCondition(f.encounter, fighter, "stunned", null);
            f.effectApplier.addCondition(f.encounter, fighter, "poisoned", null);
            cast("Power Word Heal", 9, List.of(fighter));

            assertEquals(50, fighter.getHpCurrent());
            assertFalse(CombatFixture.conditionNames(fighter).contains("stunned"));
            assertTrue(CombatFixture.conditionNames(fighter).contains("poisoned"));
        }

        @Test
        @DisplayName("Mass Heal divides its pool so none is wasted: the least hurt healed in full, the rest shared "
                + "(PHB p.258)")
        void healingPool() {
            EncounterParticipant a = f.player("A", 100, 10);
            EncounterParticipant b = f.player("B", 400, 10);
            EncounterParticipant c = f.player("C", 400, 10);
            a.setHpCurrent(90);
            b.setHpCurrent(1);
            c.setHpCurrent(1);
            Map<EncounterParticipant, Integer> shares = SpellCaster.poolShares(700, List.of(b, a, c));
            assertEquals(10, shares.get(a));
            assertEquals(345, shares.get(b));
            assertEquals(345, shares.get(c));

            f.spell("Mass Heal", 9, """
                    {"spellLevel": 9, "deliveryMethod": "AUTO_HIT", "healingPool": 700,
                     "removesConditions": ["blinded", "deafened"]}""");
            f.effectApplier.addCondition(f.encounter, b, "blinded", null);
            SpellCaster.CastResult result = cast("Mass Heal", 9, List.of(a, b, c));
            assertEquals(100, a.getHpCurrent());
            assertEquals(346, b.getHpCurrent());
            assertFalse(CombatFixture.conditionNames(b).contains("blinded"));
            assertTrue(result.summary().contains("Mass Heal restores 700 of its 700 hit points"));
        }

        @Test
        @DisplayName("Time Stop rolls 1d4 + 1 for its turns and logs it (PHB p.283)")
        void castRoll() {
            f.spell("Time Stop", 9, """
                    {"spellLevel": 9, "deliveryMethod": "SELF", "selfOnly": true,
                     "castRoll": {"dice": "1d4+1", "note": "turns in a row"}}""");
            f.dice.script(3);
            SpellCaster.CastResult result = cast("Time Stop", 9, List.of());
            assertTrue(result.summary().contains("Time Stop: 1d4 + 1 (3) = 4 turns in a row"), result.summary());
        }
    }

    @Nested
    @DisplayName("Lasting")
    class Lasting {

        @Test
        @DisplayName("Weird: a successful save at the end of a turn ends the spell for that creature only (PHB p.288)")
        void endsForCreature() {
            f.spell("Weird", 9, """
                    {"spellLevel": 9, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "WIS", "conditionsInflicted": [{"condition": "frightened", "durationRounds": 10}],
                     "effectTriggers": [{"when": ["END_OF_TURN"], "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                                         "damageDice": "4d10", "damageType": "psychic",
                                         "endsForCreatureOnSave": true}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            EncounterParticipant troll = f.monster("Troll", 60, 11);
            f.dice.script(1, 1);
            cast("Weird", 9, List.of(ogre, troll));
            assertTrue(CombatFixture.conditionNames(ogre).contains("frightened"));
            assertTrue(CombatFixture.conditionNames(troll).contains("frightened"));

            // 4d10 of 5s = 20; the ogre saves with 20, the troll fails with 2
            f.dice.script(5, 5, 5, 5, 20, 2);
            f.spellCaster.trigger(f.encounter, wizard, EffectApplier.effects(wizard).getFirst(),
                    SpellTrigger.END_OF_TURN, List.of(ogre, troll), "INT", 7, 15);
            assertFalse(CombatFixture.conditionNames(ogre).contains("frightened"));
            assertTrue(CombatFixture.conditionNames(troll).contains("frightened"));
            assertEquals(40, troll.getHpCurrent());
            assertNotNull(wizard.getConcentration());
        }

        @Test
        @DisplayName("Maze: the banished creature's DC 20 INT check is rolled at the start of the caster's turn; a "
                + "success ends the spell, and a minotaur escapes without a roll (PHB p.258)")
        void escapeCheck() {
            f.spell("Maze", 8, """
                    {"spellLevel": 8, "concentration": true, "durationRounds": 100, "deliveryMethod": "AUTO_HIT",
                     "conditionsInflicted": [{"condition": "banished", "durationRounds": 100}],
                     "escapeCheck": {"ability": "INT", "dc": 20, "autoSucceed": ["minotaur"]}}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            cast("Maze", 8, List.of(ogre));
            assertTrue(CombatAccess.banished(ogre));

            f.dice.script(19);
            f.effectApplier.effectsAtTurnStart(f.encounter, wizard);
            assertTrue(CombatAccess.banished(ogre));
            assertTrue(logged("INT check d20(19) + 0 = 19 against DC 20, and fails"));

            f.dice.script(20);
            f.effectApplier.effectsAtTurnStart(f.encounter, wizard);
            assertFalse(CombatAccess.banished(ogre));
            assertNull(wizard.getConcentration());

            EncounterParticipant minotaur = f.monster("Minotaur", 76, 14);
            cast("Maze", 8, List.of(minotaur));
            f.effectApplier.effectsAtTurnStart(f.encounter, wizard);
            assertFalse(CombatAccess.banished(minotaur));
            assertTrue(logged("Minotaur escapes Wizard's Maze without a check"));
        }

        @Test
        @DisplayName("Storm of Vengeance reminds the table of each round's effect at the start of the caster's turn "
                + "(PHB p.279)")
        void roundReminders() {
            f.spell("Storm of Vengeance", 9, """
                    {"spellLevel": 9, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "CON", "halfOnSave": false,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "thunder"}],
                     "roundReminders": {"2": "Acid rain", "3": "Lightning"}}""");
            f.encounter.setRoundNumber(4);
            f.dice.script(1, 1);
            cast("Storm of Vengeance", 9, List.of());
            assertEquals(1, EffectApplier.effects(wizard).size());

            f.encounter.setRoundNumber(5);
            f.effectApplier.effectsAtTurnStart(f.encounter, wizard);
            assertTrue(logged("Storm of Vengeance, round 2: Acid rain"));
            assertFalse(logged("Lightning"));
        }
    }

    @Nested
    @DisplayName("Rolls")
    class Rolls {

        @Test
        @DisplayName("Blade of Disaster: a d20 of 18 is a critical hit, dealing its 12d12 rather than doubled dice "
                + "(TCE p.106)")
        void critRangeAndDice() {
            Resolver resolver = new Resolver(Rng.scripted(18, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
            Effect blade = new Effect("Blade of Disaster", new Effect.Attack(5, RollMode.NORMAL, true, false, null,
                    List.of(), 18).withWeapon(false), List.of(new Effect.DamagePart(new Dice(4, 12, 0), "force", 0,
                    new Dice(12, 12, 0))), null, List.of(), false, null);
            Resolver.TargetOutcome outcome = resolver.resolve(blade, List.of(new Resolver.Target(UUID.randomUUID(),
                    25, 0, List.of()))).getFirst();
            assertEquals(Outcome.CRITICAL, outcome.outcome());
            assertEquals(12, outcome.damage().getFirst().amount());
        }

        @Test
        @DisplayName("Invulnerability's DAMAGE_IMMUNITY to \"all\" makes its caster immune to every type (XGE p.160)")
        void immunity() {
            f.spell("Invulnerability", 9, """
                    {"spellLevel": 9, "concentration": true, "durationRounds": 100, "deliveryMethod": "SELF",
                     "selfOnly": true,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "DAMAGE_IMMUNITY",
                                                                      "damageTypes": ["all"]}]}]}""");
            cast("Invulnerability", 9, List.of());
            assertTrue(f.stats.defences(wizard, "fire").contains(DamageRules.Defence.IMMUNITY));
            assertTrue(f.stats.defences(wizard, "psychic").contains(DamageRules.Defence.IMMUNITY));
            OngoingModifier all = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.DAMAGE_IMMUNITY, null,
                    null, 0, null, null, List.of("all"), false, false, false, null, false, null, null, null, null);
            assertEquals("immune to all damage", all.describe());
        }
    }
}
