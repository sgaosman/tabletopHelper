package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.rules.combat.TurnOrder;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine extensions the 4th-level spells brought (D185): several damage types in one roll, durations by slot,
 * conditions and exhaustion from triggers, damage that comes later, a damage budget, retaliation, creature-type rules,
 * saves and ends on damage, Banishment and Otiluke's Resilient Sphere, the new Ongoing Modifier kinds and Confusion's
 * d10.
 */
class FourthLevelMechanicsTest {

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

    private static OngoingModifier modifier(OngoingModifier.Kind kind, String requires, String condition,
                                            List<String> types, int flat) {
        return new OngoingModifier(UUID.randomUUID(), kind, null, null, flat, RollMode.ADVANTAGE, null, types, false,
                false, false, null, false, requires, List.of(), condition, null);
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("Ice Storm deals 2d8 bludgeoning and 4d6 cold in one roll, its upcast adding to the bludgeoning "
                + "(PHB p.252); a later damage effect not marked combined stays out, as Melf's Acid Arrow's 2d4")
        void combinedDamage() {
            f.spell("Ice Storm", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "bludgeoning",
                                  "upcastScaling": {"additionalDicePerLevel": "1d8"}},
                                 {"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "cold", "combined": true}]}""");
            Effect effect = f.spellEffects.forCast("Ice Storm", 5, wizard, "INT", 7, 15, null).effect();

            assertEquals(List.of(new Effect.DamagePart(new Dice(3, 8, 0), "bludgeoning"),
                    new Effect.DamagePart(new Dice(4, 6, 0), "cold")), effect.damage());
            SpellTemplate arrow = SpellTemplate.parse("""
                    {"effects": [{"effectType": "DAMAGE", "damageDice": "4d4", "damageType": "acid"},
                                 {"effectType": "DAMAGE", "damageDice": "2d4", "damageType": "acid"}]}""");
            assertEquals(1, arrow.damageEffects().size());
        }

        @Test
        @DisplayName("Dominate Beast lasts 1 minute at 4th, 10 minutes at 5th, an hour at 6th and 8 hours from 7th (PHB "
                + "p.234), and its charm lasts as long")
        void durationBySlot() {
            f.spell("Dominate Beast", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "concentration": true,
                     "durationRounds": 10, "durationBySlot": {"5": 100, "6": 600, "7": 4800},
                     "conditionsInflicted": [{"condition": "charmed", "durationRounds": 10, "saveToEndOnDamage": true}]}""");
            int[][] expected = {{4, 10}, {5, 100}, {6, 600}, {7, 4800}, {9, 4800}};
            for (int[] pair : expected) {
                SpellEffects.SpellPlan plan = f.spellEffects.forCast("Dominate Beast", pair[0], wizard, "INT", 7, 15,
                        null);
                assertEquals(pair[1], plan.durationRounds(), "slot " + pair[0]);
                assertEquals(pair[1], plan.effect().conditionEnds().get("charmed").durationRounds());
                assertEquals(new Effect.DamageSave(com.tabletophelper.rules.Ability.WIS, 15, false),
                        plan.effect().conditionEnds().get("charmed").saveOnDamage());
            }
        }

        @Test
        @DisplayName("The new keys are read, and one that doesn't fit makes the template unreadable")
        void newKeys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"spellLevel": 4, "damageBudget": 60, "turnReminder": "bite",
                     "delayedDamage": {"damageDice": "5d4", "damageType": "acid"},
                     "retaliation": {"damageDice": "2d8", "damageTypeByOption": {"warm": "fire", "chill": "cold"}},
                     "noEffectOn": ["undead"], "maximumDamageTo": ["plant"], "targetTypes": ["beast"],
                     "tempHitPoints": {"flat": 10, "option": "great tree"},
                     "effectTriggers": [{"when": ["START_OF_TURN"], "exhaustion": 1, "addsAffected": true,
                                         "whenConditioned": "restrained",
                                         "conditionsInflicted": [{"condition": "restrained"}]}]}""");
            assertNull(template.unreadable());
            assertEquals(60, template.damageBudget());
            assertEquals("FAILED_SAVE", template.delayedDamage().on());
            assertEquals("fire", template.retaliation().typeFor("WARM"));
            assertEquals("cold", template.retaliation().typeFor("chill"));
            assertEquals(10, template.tempHitPoints("great tree"));
            assertNull(template.tempHitPoints("primal beast"));
            SpellTemplate.EffectTrigger trigger = template.trigger(SpellTrigger.START_OF_TURN).orElseThrow();
            assertEquals(1, trigger.exhaustion());
            assertTrue(trigger.addsAffected());
            assertEquals("restrained", trigger.whenConditioned());
            assertEquals("restrained", trigger.conditions().getFirst().name());

            assertNotNull(SpellTemplate.parse("""
                    {"durationBySlot": {"fifth": 100}}""").unreadable());
            assertNotNull(SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["ENTER"], "exhaustion": "one"}]}""").unreadable());
        }

        @Test
        @DisplayName("A cast's delayed damage adds its upcast dice: Melf's Acid Arrow's 2d4 is 3d4 at 3rd (PHB p.259)")
        void delayedUpcast() {
            f.spell("Melf's Acid Arrow", 2, """
                    {"spellLevel": 2, "delayedDamage": {"damageDice": "2d4", "damageType": "acid", "on": "HIT",
                     "upcastScaling": {"additionalDicePerLevel": "1d4"}}}""");
            SpellEffects.Later later = f.spellEffects.later("Melf's Acid Arrow", 3, null);
            assertEquals("3d4", later.delayedDice());
            assertEquals("HIT", later.delayedOn());
        }
    }

    @Nested
    @DisplayName("Triggers")
    class Triggers {

        @BeforeEach
        void evards() {
            f.spell("Evard's Black Tentacles", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "concentration": true,
                     "durationRounds": 10,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "3d6", "damageType": "bludgeoning"}],
                     "effectTriggers": [{"when": ["ENTER", "START_OF_TURN"], "whenConditioned": "restrained",
                                         "conditionsInflicted": [{"condition": "restrained"}]}]}""");
        }

        @Test
        @DisplayName("A trigger gives its own conditions on a failed save; for a creature that already has them it "
                + "deals its damage with no save and gives nothing (PHB p.238)")
        void conditionsAndAutomatic() {
            Effect saving = f.spellEffects.forTrigger("Evard's Black Tentacles", SpellTrigger.ENTER, 4, wizard,
                    "INT", 7, 15).effect();
            assertEquals(List.of("restrained"), saving.conditions());
            assertTrue(saving.delivery() instanceof Effect.Save);

            Effect automatic = f.spellEffects.forConditionedTrigger("Evard's Black Tentacles",
                    SpellTrigger.START_OF_TURN, 4, wizard, "INT", 7, 15).orElseThrow().effect();
            assertTrue(automatic.delivery() instanceof Effect.Automatic);
            assertTrue(automatic.conditions().isEmpty());
            assertEquals("bludgeoning", automatic.damage().getFirst().type());
        }

        @Test
        @DisplayName("Through the caster: the restraint belongs to the concentration, and the next start of turn rolls "
                + "only the damage")
        void throughTheCaster() {
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            // A cast with no targets still rolls its 3d6, as every cast does
            f.dice.script(1, 1, 1);
            cast("Evard's Black Tentacles", 4, List.of(), null);
            SpellEffectEntry tentacles = EffectApplier.effects(wizard).getFirst();

            f.dice.script(2, 2, 2, 3);
            f.spellCaster.trigger(f.encounter, wizard, tentacles, SpellTrigger.ENTER, List.of(ogre), "INT", 7, 15);
            assertEquals(54, ogre.getHpCurrent());
            ConditionEntry restrained = ogre.getActiveConditions().getFirst();
            assertEquals("restrained", restrained.name());
            assertEquals(wizard.getConcentration().id(), restrained.concentrationInstanceId());

            f.dice.script(1, 1, 1);
            f.spellCaster.trigger(f.encounter, wizard, tentacles, SpellTrigger.START_OF_TURN, List.of(ogre), "INT",
                    7, 15);
            assertEquals(51, ogre.getHpCurrent());
        }

        @Test
        @DisplayName("A trigger's exhaustion is recorded on the effect and taken back when it ends (XGE p.164)")
        void exhaustion() {
            f.spell("Sickening Radiance", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "concentration": true,
                     "durationRounds": 100,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "4d10", "damageType": "radiant"},
                                 {"effectType": "DEBUFF", "modifiers": [{"kind": "REVEALED"}]}],
                     "effectTriggers": [{"when": ["START_OF_TURN"], "exhaustion": 1, "addsAffected": true}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            ogre.setExhaustionLevel(1);
            f.dice.script(1, 1, 1, 1);
            cast("Sickening Radiance", 4, List.of(), null);
            SpellEffectEntry radiance = EffectApplier.effects(wizard).getFirst();

            f.dice.script(1, 1, 1, 1, 2);
            f.spellCaster.trigger(f.encounter, wizard, radiance, SpellTrigger.START_OF_TURN, List.of(ogre), "INT", 7,
                    15);
            assertEquals(2, ogre.getExhaustionLevel());
            assertTrue(OngoingModifiers.revealed(ActiveModifiers.modifiersOn(ogre)));

            f.effectApplier.endConcentration(f.encounter, wizard, "dropped");
            assertEquals(1, ogre.getExhaustionLevel());
            assertFalse(OngoingModifiers.revealed(ActiveModifiers.modifiersOn(ogre)));
        }

        @Test
        @DisplayName("A damage budget ends the effect once spent (PHB p.246)")
        void budget() {
            f.spell("Guardian of Faith", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "durationRounds": 4800, "damageBudget": 30,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "20", "damageType": "radiant"}],
                     "effectTriggers": [{"when": ["ENTER"]}]}""");
            EncounterParticipant giant = f.monster("Giant", 100, 11);
            cast("Guardian of Faith", 4, List.of(), null);
            SpellEffectEntry guardian = EffectApplier.effects(wizard).getFirst();
            assertEquals(30, guardian.damageLeft());

            f.dice.script(1);
            f.spellCaster.trigger(f.encounter, wizard, guardian, SpellTrigger.ENTER, List.of(giant), "INT", 7, 15);
            assertEquals(10, EffectApplier.effects(wizard).getFirst().damageLeft());
            f.dice.script(1);
            f.spellCaster.trigger(f.encounter, wizard, guardian, SpellTrigger.ENTER, List.of(giant), "INT", 7, 15);
            assertTrue(EffectApplier.effects(wizard).isEmpty());
            assertEquals(60, giant.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Casting")
    class Casting {

        @Test
        @DisplayName("Blight: no effect on an undead, and a plant saves with disadvantage and takes maximum damage "
                + "(PHB p.219)")
        void creatureTypes() {
            f.spell("Blight", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "noEffectOn": ["undead", "construct"], "maximumDamageTo": ["plant"],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "8d8", "damageType": "necrotic",
                                  "upcastScaling": {"additionalDicePerLevel": "1d8"}}]}""");
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("undead");
            EncounterParticipant mound = f.monster("Mound", 136, 15);
            mound.getMonster().setType("plant");

            f.dice.script(18, 2);
            cast("Blight", 5, List.of(zombie, mound), null);

            assertEquals(22, zombie.getHpCurrent());
            assertEquals(136 - 72, mound.getHpCurrent());
            assertTrue(f.descriptions().stream().anyMatch(line -> line.contains("Blight has no effect on Zombie")));
        }

        @Test
        @DisplayName("A spell for one creature type refuses others before anything is spent, and Wild Shape makes a "
                + "beast (PHB p.234)")
        void targetTypes() {
            f.spell("Dominate Beast", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "targetTypes": ["beast"]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            ogre.getMonster().setType("giant");
            assertThrows(ValidationException.class,
                    () -> f.spellCaster.requireTargetTypes("Dominate Beast", List.of(ogre)));
            EncounterParticipant wolf = f.monster("Wolf", 11, 13);
            wolf.getMonster().setType("beast");
            f.spellCaster.requireTargetTypes("Dominate Beast", List.of(wolf));
            assertEquals("humanoid", CreatureStats.typeForSpells(wizard));
        }

        @Test
        @DisplayName("An instantaneous spell's delayed damage comes at the end of its creature's next turn, and its "
                + "Spell Effect goes with it (XGE p.170)")
        void delayedDamage() {
            f.spell("Vitriolic Sphere", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "delayedDamage": {"damageDice": "5d4", "damageType": "acid"},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "10d4", "damageType": "acid"}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2);
            cast("Vitriolic Sphere", 4, List.of(ogre), null);
            assertEquals(50, ogre.getHpCurrent());

            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            assertEquals(50, ogre.getHpCurrent());
            f.effectApplier.effectsAtTurnStart(f.encounter, ogre);
            f.dice.script(4, 4, 4, 4, 4);
            f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            assertEquals(30, ogre.getHpCurrent());
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("Fire Shield's chill shield resists fire and answers a melee hit with 2d8 cold (PHB p.242)")
        void retaliation() {
            f.spell("Fire Shield", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SELF", "selfOnly": true, "durationRounds": 100,
                     "castOptions": ["warm", "chill"],
                     "retaliation": {"damageDice": "2d8", "damageTypeByOption": {"warm": "fire", "chill": "cold"}},
                     "effects": [{"effectType": "BUFF", "modifiers": [
                        {"kind": "RESISTANCE", "damageTypes": ["cold"], "option": "warm"},
                        {"kind": "RESISTANCE", "damageTypes": ["fire"], "option": "chill"}]}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            cast("Fire Shield", 4, List.of(), "chill");
            assertEquals(new SpellEffectEntry.Retaliation("2d8", "cold"),
                    EffectApplier.effects(wizard).getFirst().retaliation());

            Effect club = new Effect("Club", new Effect.Attack(5, RollMode.NORMAL, true, false),
                    List.of(new Effect.DamagePart(new Dice(1, 6, 0), "bludgeoning")), null, List.of(), false, null);
            f.dice.script(15, 3, 4, 4);
            f.effectApplier.apply(f.encounter, new EffectApplier.Use(ogre, "Ogre", CombatActionType.MONSTER_ACTION,
                            "uses Club", "on", "Club", null), club,
                    f.resolver.resolve(club, List.of(f.stats.target(wizard, club))));
            assertEquals(27, wizard.getHpCurrent());
            assertEquals(52, ogre.getHpCurrent());
            f.effectApplier.damage(f.encounter, null, "DM", wizard, 10, "fire");
            assertEquals(22, wizard.getHpCurrent());
        }

        @Test
        @DisplayName("Death Ward turns the first drop to 0 into 1 hit point and ends (PHB p.230)")
        void deathWard() {
            f.spell("Death Ward", 4, """
                    {"spellLevel": 4, "deliveryMethod": "AUTO_HIT", "durationRounds": 4800,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "DEATH_WARD"}]}]}""");
            cast("Death Ward", 4, List.of(wizard), null);
            f.effectApplier.damage(f.encounter, null, "DM", wizard, 100, null);
            assertEquals(1, wizard.getHpCurrent());
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("Great Tree gives 10 temporary hit points, which don't add to more already held (XGE p.157)")
        void tempHitPoints() {
            f.spell("Guardian of Nature", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SELF", "selfOnly": true, "concentration": true,
                     "durationRounds": 10, "castOptions": ["primal beast", "great tree"],
                     "tempHitPoints": {"flat": 10, "option": "great tree"},
                     "effects": [{"effectType": "BUFF", "modifiers": [
                        {"kind": "SAVE_MODE", "mode": "ADVANTAGE", "ability": "CON", "option": "great tree"}]}]}""");
            cast("Guardian of Nature", 4, List.of(), "great tree");
            assertEquals(10, wizard.getHpTemp());
        }
    }

    @Nested
    @DisplayName("Ends on damage")
    class EndsOnDamage {

        @Test
        @DisplayName("Hypnotic Pattern's charm ends on damage but still belongs to the concentration (PHB p.252)")
        void endsOnDamage() {
            f.spell("Hypnotic Pattern", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "concentration": true,
                     "durationRounds": 10,
                     "conditionsInflicted": [{"condition": "charmed", "durationRounds": 10, "endsOnDamage": true}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(2);
            cast("Hypnotic Pattern", 3, List.of(ogre), null);
            ConditionEntry charmed = ogre.getActiveConditions().getFirst();
            assertEquals(wizard.getConcentration().id(), charmed.concentrationInstanceId());

            f.effectApplier.damage(f.encounter, null, "DM", ogre, 3, null);
            assertTrue(ogre.getActiveConditions() == null || ogre.getActiveConditions().isEmpty());
            assertNotNull(wizard.getConcentration());
        }

        @Test
        @DisplayName("Tasha's Hideous Laughter's damage save has advantage, and a success ends the spell (PHB p.280)")
        void damageSaveWithAdvantage() {
            f.spell("Tasha's Hideous Laughter", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "concentration": true,
                     "durationRounds": 10,
                     "conditionsInflicted": [{"condition": "incapacitated", "durationRounds": 10,
                                              "saveToEndOnDamage": true, "saveToEndOnDamageAdvantage": true}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(2);
            cast("Tasha's Hideous Laughter", 1, List.of(ogre), null);

            // Advantage: 3 and 18, keeping 18 against DC 15
            f.dice.script(3, 18);
            f.effectApplier.damage(f.encounter, null, "DM", ogre, 3, null);
            assertNull(wizard.getConcentration());
            assertTrue(ogre.getActiveConditions() == null || ogre.getActiveConditions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Out of reach")
    class OutOfReach {

        @Test
        @DisplayName("A banished creature can't be targeted and its turns are skipped, a PC's too (PHB p.217)")
        void banished() {
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            ogre.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("banished", null, 1))));
            assertThrows(ConflictException.class, () -> CombatAccess.requireNotDead(List.of(ogre)));
            TurnOrder.Combatant combatant = EncounterTurns.combatant(ogre);
            assertTrue(combatant.monster() && combatant.dead());

            wizard.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("banished", null, 1))));
            assertTrue(new TurnOrder.Turn(EncounterTurns.combatant(wizard)).isSkipped());
        }

        @Test
        @DisplayName("Nothing crosses Otiluke's Resilient Sphere either way, but its creature can act on itself (PHB "
                + "p.264)")
        void enclosed() {
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            ogre.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("enclosed", null, 1))));
            assertThrows(ConflictException.class, () -> CombatAccess.requireReachable(wizard, List.of(ogre)));
            assertThrows(ConflictException.class, () -> CombatAccess.requireReachable(ogre, List.of(wizard)));
            CombatAccess.requireReachable(ogre, List.of(ogre));
            CombatAccess.requireReachable(wizard, List.of(wizard));
        }

        @Test
        @DisplayName("A confused creature's d10 is rolled and logged at the start of its turn (PHB p.224)")
        void confusion() {
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            ogre.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("confused", null, 1))));
            f.dice.script(1, 3);
            f.effectApplier.effectsAtTurnStart(f.encounter, ogre);
            assertTrue(f.descriptions().stream().anyMatch(line -> line.equals("Ogre is confused: d10 (1), so it moves "
                    + "its full speed in a random direction (d8: 3) and takes no action; it can't take reactions.")));
        }
    }

    @Nested
    @DisplayName("Ongoing Modifier kinds")
    class Kinds {

        @Test
        @DisplayName("Stoneskin resists only nonmagical damage, and spells, enchanted weapons and magical monsters' "
                + "weapons are magical (PHB p.278)")
        void nonmagicalResistance() {
            List<OngoingModifier> stoneskin = List.of(modifier(OngoingModifier.Kind.RESISTANCE, "NONMAGICAL", null,
                    List.of("slashing"), 0));
            assertTrue(OngoingModifiers.resists("slashing", stoneskin, false));
            assertFalse(OngoingModifiers.resists("slashing", stoneskin, true));

            EncounterParticipant knight = f.monster("Knight", 60, 18);
            assertFalse(EffectApplier.magical(new EffectApplier.Use(knight, "Knight", CombatActionType.MONSTER_ACTION,
                    "uses Longsword", "on", "Longsword", null)));
            knight.getMonster().setTraits("[{\"name\": \"Magic Weapons\", \"entries\": [\"Its weapon attacks are "
                    + "magical.\"]}]");
            assertTrue(EffectApplier.magical(new EffectApplier.Use(knight, "Knight", CombatActionType.MONSTER_ACTION,
                    "uses Longsword", "on", "Longsword", null)));
            assertTrue(EffectApplier.magical(new EffectApplier.Use(null, "DM", CombatActionType.SPELL_CAST,
                    "casts Fireball", "at", "Fireball", null)));
        }

        @Test
        @DisplayName("Freedom of Movement guards against magical paralysis and speed loss only (PHB p.244)")
        void freedomOfMovement() {
            List<OngoingModifier> freedom = List.of(
                    modifier(OngoingModifier.Kind.CONDITION_IMMUNITY, "MAGICAL", "paralyzed", List.of(), 0),
                    modifier(OngoingModifier.Kind.SPEED_PROTECTED, null, null, List.of(), 0),
                    modifier(OngoingModifier.Kind.SPEED, null, null, List.of(), -10),
                    modifier(OngoingModifier.Kind.SPEED_HALVED, null, null, List.of(), 0));
            assertTrue(OngoingModifiers.immuneTo("paralyzed", freedom, true));
            assertFalse(OngoingModifiers.immuneTo("paralyzed", freedom, false));
            assertEquals(Map.of("walk", 30), OngoingModifiers.speeds(Map.of("walk", 30), freedom));
        }

        @Test
        @DisplayName("Aura of Life keeps the maximum from shrinking; Aura of Purity's advantage counts only against "
                + "the conditions it names; Elemental Bane takes a resistance away")
        void auras() {
            List<OngoingModifier> life = List.of(modifier(OngoingModifier.Kind.HP_MAX_PROTECTED, null, null,
                    List.of(), 0), modifier(OngoingModifier.Kind.HP_MAX, null, null, List.of(), -10));
            assertEquals(40, OngoingModifiers.hitPointMaximum(40, life));

            List<OngoingModifier> purity = List.of(modifier(OngoingModifier.Kind.SAVE_MODE, null,
                    "blinded,charmed,poisoned", List.of(), 0));
            assertEquals(List.of(RollMode.ADVANTAGE), OngoingModifiers.saveModes("WIS", purity, List.of("charmed")));
            assertTrue(OngoingModifiers.saveModes("WIS", purity, List.of("restrained")).isEmpty());
            assertTrue(OngoingModifiers.saveModes("WIS", purity).isEmpty());

            List<OngoingModifier> bane = List.of(modifier(OngoingModifier.Kind.LOSE_RESISTANCE, null, null,
                    List.of("fire"), 0));
            assertTrue(OngoingModifiers.losesResistance("FIRE", bane));
            assertFalse(OngoingModifiers.losesResistance("cold", bane));
        }

        @Test
        @DisplayName("Aura of Life gives a living creature at 0 hit points 1 at the start of its turn (PHB p.216)")
        void healAtZero() {
            f.spell("Aura of Life", 4, """
                    {"spellLevel": 4, "deliveryMethod": "AUTO_HIT", "concentration": true, "durationRounds": 100,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "HEAL_AT_ZERO", "flat": 1},
                                                                       {"kind": "HP_MAX_PROTECTED"}]}]}""");
            EncounterParticipant fighter = f.player("Fighter", 20, 16);
            cast("Aura of Life", 4, List.of(fighter), null);
            CombatFixture.downed(fighter, com.tabletophelper.rules.combat.LifeState.DYING, 0);
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(1, fighter.getHpCurrent());
            fighter.setExhaustionLevel(4);
            assertEquals(20, CreatureStats.hpMax(fighter));
        }
    }
}
