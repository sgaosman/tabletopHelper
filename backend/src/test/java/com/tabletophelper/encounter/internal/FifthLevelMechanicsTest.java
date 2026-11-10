package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine extensions the 5th-level spells brought (D187): ending conditions and exhaustion, control of a creature,
 * life drain, a spell ended by a successful first save, counted saves, a use that ends the spell, ability check contests
 * and damage types chosen at the cast.
 */
class FifthLevelMechanicsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets,
                                        String option) {
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("The new keys read back, and a bad one makes the template unreadable")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"lifeDrain": 0.5, "endsOnInitialSave": true, "controlTransfer": true, "repeatEndsSpell": true,
                     "removesConditions": ["charmed", "exhaustion"],
                     "countedSaves": {"ability": "CON", "successes": 3, "failures": 3, "endsConditions": ["Poisoned"]},
                     "contest": {"targetAbility": "STR", "condition": "restrained"},
                     "hitThreshold": {"hp": 50, "condition": "Banished"},
                     "repeatEffect": {"deliveryMethod": "SAVING_THROW", "damageDice": "4d8",
                                      "conditionsInflicted": [{"condition": "blinded", "durationRounds": 10}]}}""");
            assertNull(template.unreadable());
            assertEquals(0.5, template.lifeDrain());
            assertTrue(template.endsOnInitialSave());
            assertTrue(template.controlTransfer());
            assertTrue(template.repeatEndsSpell());
            assertEquals(List.of("charmed", "exhaustion"), template.removesConditions());
            assertEquals(List.of("poisoned"), template.countedSaves().endsConditions());
            assertEquals("STR", template.contest().targetAbility());
            assertEquals(new SpellTemplate.HitThreshold(50, "banished"), template.hitThreshold());
            assertEquals("blinded", template.repeatConditions().getFirst().name());

            assertNotNull(SpellTemplate.parse("{\"lifeDrain\": 2}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"countedSaves\": {\"ability\": \"CON\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"contest\": {}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"hitThreshold\": {\"hp\": 50}}").unreadable());
        }

        @Test
        @DisplayName("A damage part of type OPTION deals the type chosen, as Destructive Wave's radiant or necrotic "
                + "(PHB p.231)")
        void optionDamageType() {
            f.spell("Destructive Wave", 5, """
                    {"spellLevel": 5, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "castOptions": ["radiant", "necrotic"],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "5d6", "damageType": "thunder"},
                                 {"effectType": "DAMAGE", "damageDice": "5d6", "damageType": "OPTION",
                                  "combined": true}]}""");
            Effect effect = f.spellEffects.forCast("Destructive Wave", 5, wizard, "INT", 7, 15, null, "necrotic")
                    .effect();
            assertEquals(List.of(new Effect.DamagePart(new Dice(5, 6, 0), "thunder"),
                    new Effect.DamagePart(new Dice(5, 6, 0), "necrotic")), effect.damage());
        }
    }

    @Nested
    @DisplayName("Casting")
    class Casting {

        @Test
        @DisplayName("Lesser Restoration ends the chosen condition whatever gave it (PHB p.255); Greater Restoration's "
                + "exhaustion option lowers it by one level, and a curse is the DM's (PHB p.246)")
        void restoration() {
            f.spell("Lesser Restoration", 2, """
                    {"spellLevel": 2, "deliveryMethod": "AUTO_HIT", "castOptions": ["blinded", "poisoned", "disease"],
                     "removesConditions": ["blinded", "poisoned"]}""");
            f.spell("Greater Restoration", 5, """
                    {"spellLevel": 5, "deliveryMethod": "AUTO_HIT", "castOptions": ["exhaustion", "charmed", "curse"],
                     "removesConditions": ["exhaustion", "charmed"]}""");
            EncounterParticipant fighter = f.player("Fighter", 20, 16);
            f.effectApplier.addCondition(f.encounter, fighter, "poisoned", null);
            f.effectApplier.addCondition(f.encounter, fighter, "blinded", null);
            fighter.setExhaustionLevel(2);

            cast("Lesser Restoration", 2, List.of(fighter), "poisoned");
            assertFalse(CreatureStats.conditionNames(fighter).contains("poisoned"));
            assertTrue(CreatureStats.conditionNames(fighter).contains("blinded"));

            cast("Greater Restoration", 5, List.of(fighter), "exhaustion");
            assertEquals(1, fighter.getExhaustionLevel());

            SpellCaster.CastResult curse = cast("Greater Restoration", 5, List.of(fighter), "curse");
            assertTrue(curse.summary().contains("The DM ends the curse on Fighter"));
        }

        @Test
        @DisplayName("Dominate Person's failed save puts the creature under the caster's controller; the spell ending "
                + "gives it back (PHB p.235)")
        void control() {
            f.spell("Dominate Person", 5, """
                    {"spellLevel": 5, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "WIS", "controlTransfer": true,
                     "conditionsInflicted": [{"condition": "charmed", "durationRounds": 10}]}""");
            UUID owner = UUID.randomUUID();
            wizard.setControlledByUserId(owner);
            EncounterParticipant bandit = f.monster("Bandit", 30, 12);
            f.dice.script(2);
            cast("Dominate Person", 5, List.of(bandit), null);
            assertEquals(owner, bandit.getControlledByUserId());
            assertEquals(1, EffectApplier.effects(wizard).getFirst().controlled().size());

            f.effectApplier.endConcentration(f.encounter, wizard, "ended by hand");
            assertNull(bandit.getControlledByUserId());
        }

        @Test
        @DisplayName("Removing the condition that holds a controlled creature gives it back at once")
        void controlEndsWithCondition() {
            f.spell("Dominate Person", 5, """
                    {"spellLevel": 5, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "WIS", "controlTransfer": true, "conditionsInflicted": ["charmed"]}""");
            wizard.setControlledByUserId(UUID.randomUUID());
            EncounterParticipant bandit = f.monster("Bandit", 30, 12);
            f.dice.script(2);
            cast("Dominate Person", 5, List.of(bandit), null);
            f.effectApplier.removeCondition(f.encounter, bandit, "charmed");
            assertNull(bandit.getControlledByUserId());
            assertTrue(EffectApplier.effects(wizard).getFirst().controlled().isEmpty());
        }

        @Test
        @DisplayName("Life drain heals the caster half the damage taken, rounded down; a successful first save ends the "
                + "spell (XGE p.155)")
        void drainAndInitialSave() {
            f.spell("Enervation", 5, """
                    {"spellLevel": 5, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "DEX", "halfOnSave": true, "lifeDrain": 0.5, "endsOnInitialSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "4d8", "damageType": "necrotic"}],
                     "repeatActionType": "ACTION",
                     "repeatEffect": {"deliveryMethod": "AUTO_HIT", "damageDice": "4d8", "damageType": "necrotic"}}""");
            wizard.setHpCurrent(10);
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(3, 3, 3, 2, 1);
            cast("Enervation", 5, List.of(ogre), null);
            assertEquals(49, ogre.getHpCurrent());
            assertEquals(15, wizard.getHpCurrent());
            assertNotNull(wizard.getConcentration());

            f.dice.script(4, 4, 4, 4, 20);
            cast("Enervation", 5, List.of(ogre), null);
            assertNull(wizard.getConcentration());
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("Counted saves: each save at the end of the creature's turn is tallied; three successes end the "
                + "spell's condition and the spell (PHB p.227)")
        void countedSaves() {
            f.spell("Contagion", 5, """
                    {"spellLevel": 5, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE",
                     "conditionsInflicted": ["poisoned"],
                     "countedSaves": {"ability": "CON", "successes": 3, "failures": 3, "endsConditions": ["poisoned"],
                                      "onFailures": "The disease takes hold."}}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(15);
            cast("Contagion", 5, List.of(ogre), null);
            assertTrue(CreatureStats.conditionNames(ogre).contains("poisoned"));
            SpellEffectEntry.CountedSave saves = EffectApplier.effects(wizard).getFirst().countedSave();
            assertEquals(15, saves.dc());

            f.dice.script(20);
            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            f.dice.script(1);
            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            SpellEffectEntry.Tally tally = EffectApplier.effects(wizard).getFirst().countedSave().tallies().getFirst();
            assertEquals(1, tally.successes());
            assertEquals(1, tally.failures());
            assertFalse(tally.done());

            f.dice.script(20, 20);
            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            assertFalse(CreatureStats.conditionNames(ogre).contains("poisoned"));
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("A contest the caster wins gives its condition until the end of the caster's next turn; a tie "
                + "changes nothing (PHB p.280, p.174)")
        void contest() {
            f.spell("Telekinesis", 5, """
                    {"spellLevel": 5, "concentration": true, "durationRounds": 100, "deliveryMethod": "AUTO_HIT",
                     "contest": {"targetAbility": "STR", "condition": "restrained"},
                     "repeatActionType": "ACTION", "repeatEffect": true}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            int strength = com.tabletophelper.rules.Ability.modifier(f.stats.abilityScore(ogre,
                    com.tabletophelper.rules.Ability.STR));
            int caster = f.stats.spellcastingModifier(wizard, "INT", 7);
            // A tie: the ogre rolls what makes its total equal the wizard's
            f.dice.script(10, 10 + caster - strength);
            cast("Telekinesis", 5, List.of(ogre), null);
            assertFalse(CreatureStats.conditionNames(ogre).contains("restrained"));

            f.dice.script(20, 1);
            f.spellCaster.repeat(f.encounter, wizard, EffectApplier.effects(wizard).getFirst(), List.of(ogre), "INT",
                    7, 15, null, "", 1);
            assertTrue(CreatureStats.conditionNames(ogre).contains("restrained"));
        }

        @Test
        @DisplayName("A use that ends the spell leaves its conditions on their own ends, as Holy Weapon's burst (XGE "
                + "p.157)")
        void repeatEndsSpell() {
            f.spell("Holy Weapon", 5, """
                    {"spellLevel": 5, "concentration": true, "durationRounds": 600, "deliveryMethod": "AUTO_HIT",
                     "repeatActionType": "BONUS_ACTION", "repeatEndsSpell": true,
                     "repeatEffect": {"deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                                      "damageDice": "4d8", "damageType": "radiant",
                                      "conditionsInflicted": [{"condition": "blinded", "durationRounds": 10,
                                                               "saveToEndEachTurn": true, "saveToEndAbility": "CON"}]}}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            cast("Holy Weapon", 5, List.of(wizard), null);
            assertNotNull(wizard.getConcentration());

            f.dice.script(2, 2, 2, 2, 1);
            f.spellCaster.repeat(f.encounter, wizard, EffectApplier.effects(wizard).getFirst(), List.of(ogre), "INT",
                    7, 15, null, "", 1);
            assertEquals(52, ogre.getHpCurrent());
            assertNull(wizard.getConcentration());
            assertTrue(CreatureStats.conditionNames(ogre).contains("blinded"));
        }
    }
}
