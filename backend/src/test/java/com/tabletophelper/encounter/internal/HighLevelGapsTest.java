package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.ShapeSpells;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The high-level spell gaps closed in D195: Time Stop's turns in a row, Feeblemind's Intelligence and Charisma of 1 and
 * its ending by Greater Restoration, Earthquake's concentration saves, Prismatic Wall's layers and Holy Aura's blinding.
 */
class HighLevelGapsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        f.effectApplier.setSpellRepository(f.spellRepository);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        return cast(spell, slotLevel, targets, null);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets,
                                        String option) {
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    private boolean logged(String text) {
        return f.log.stream().anyMatch(entry -> entry.getDescription() != null && entry.getDescription().contains(text));
    }

    @Test
    @DisplayName("The new keys read back, and bad layers make the template unreadable")
    void keys() {
        SpellTemplate template = SpellTemplate.parse("""
                {"castRoll": {"dice": "1d4+1", "note": "turns", "extraTurns": true},
                 "concentrationSave": "CON",
                 "retaliation": {"saveAbility": "CON", "conditionsInflicted": ["blinded"],
                                 "attackerTypes": ["fiend", "undead"]},
                 "layers": {"saveAbility": "DEX", "rows": [
                    {"roll": 2, "color": "orange", "damageDice": "10d6", "damageType": "acid"},
                    {"roll": 1, "color": "red", "damageDice": "10d6", "damageType": "fire"}]}}""");
        assertNull(template.unreadable());
        assertTrue(template.castRoll().extraTurns());
        assertEquals("CON", template.concentrationSave());
        assertEquals("CON", template.retaliation().saveAbility());
        assertEquals(List.of("blinded"), template.retaliation().conditions());
        assertEquals(List.of("fiend", "undead"), template.retaliation().attackerTypes());
        assertNull(template.retaliation().damageDice());
        assertEquals("DEX", template.layers().saveAbility());
        assertEquals("red", template.layers().rows().getFirst().color());

        assertNotNull(SpellTemplate.parse("{\"layers\": {\"rows\": []}}").unreadable());
        assertNotNull(SpellTemplate.parse("{\"layers\": {\"saveAbility\": \"DEX\", \"rows\": [{}]}}").unreadable());
    }

    @Nested
    @DisplayName("Time Stop")
    class TimeStop {

        private EncounterParticipant ogre;

        @BeforeEach
        void setUp() {
            f.spell("Time Stop", 9, """
                    {"spellLevel": 9, "deliveryMethod": "SELF", "selfOnly": true,
                     "castRoll": {"dice": "1d4+1", "note": "turns in a row", "extraTurns": true}}""");
            ogre = f.monster("Ogre", 60, 11);
            wizard.setInitiative(20);
            ogre.setInitiative(10);
            f.turnOf(wizard);
        }

        @Test
        @DisplayName("The caster takes the rolled turns in a row in the same round, then the turn passes on (PHB p.283)")
        void turnsInARow() {
            f.effectApplier.addCondition(f.encounter, ogre, "poisoned", 1);
            f.dice.script(2);
            SpellCaster.CastResult result = cast("Time Stop", 9, List.of());
            assertTrue(result.summary().contains("takes 3 turns in a row after this one"), result.summary());
            assertEquals(3, EffectApplier.extraTurns(wizard).orElseThrow().usesLeft());

            for (int left = 2; left >= 0; left--) {
                f.turnProcessor.advance(f.encounter);
                assertEquals(wizard.getId(), f.encounter.getCurrentTurnParticipantId());
                assertEquals(1, f.encounter.getRoundNumber());
                assertEquals(left, EffectApplier.extraTurns(wizard).orElseThrow().usesLeft());
            }
            assertTrue(logged("Wizard takes another turn (Time Stop, 0 more after this; Round 1)"));
            // Nothing of the ogre's moved on while time stood still
            assertTrue(CombatFixture.conditionNames(ogre).contains("poisoned"));

            f.turnProcessor.advance(f.encounter);
            assertEquals(ogre.getId(), f.encounter.getCurrentTurnParticipantId());
            assertTrue(EffectApplier.extraTurns(wizard).isEmpty());
        }

        @Test
        @DisplayName("Affecting another creature ends the turns still to come (PHB p.283)")
        void endsOnAffectingAnother() {
            f.spell("Fire Bolt", 0, """
                    {"spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "ranged",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}]}""");
            f.dice.script(4);
            cast("Time Stop", 9, List.of());
            f.turnProcessor.advance(f.encounter);
            assertEquals(wizard.getId(), f.encounter.getCurrentTurnParticipantId());

            f.dice.script(15, 5);
            cast("Fire Bolt", 0, List.of(ogre));
            assertTrue(EffectApplier.extraTurns(wizard).isEmpty());
            assertTrue(logged("Time Stop ends (it affects another creature)"));
            f.turnProcessor.advance(f.encounter);
            assertEquals(ogre.getId(), f.encounter.getCurrentTurnParticipantId());
        }
    }

    @Nested
    @DisplayName("Feeblemind")
    class Feeblemind {

        @Test
        @DisplayName("A feebleminded creature's INT and CHA are 1, its listed save bonus keeping its proficiency "
                + "(PHB p.239)")
        void scores() {
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            mage.getMonster().setIntelligence(18);
            mage.getMonster().setCharisma(16);
            mage.getMonster().setWisdom(14);
            mage.getMonster().setSavingThrows("{\"int\": \"+7\"}");
            assertEquals(7, f.stats.saveModifier(mage, Ability.INT));

            f.effectApplier.addCondition(f.encounter, mage, EffectApplier.FEEBLEMINDED, null);
            assertEquals(1, f.stats.abilityScore(mage, Ability.INT));
            assertEquals(1, f.stats.abilityScore(mage, Ability.CHA));
            assertEquals(14, f.stats.abilityScore(mage, Ability.WIS));
            // +7 is +4 for INT 18 and +3 proficiency: -5 for INT 1 and the +3
            assertEquals(-2, f.stats.saveModifier(mage, Ability.INT));
            assertEquals(-5, f.stats.saveModifier(mage, Ability.CHA));

            wizard.getCharacter().setIntelligence(16);
            f.effectApplier.addCondition(f.encounter, wizard, EffectApplier.FEEBLEMINDED, null);
            assertEquals(-5, f.stats.saveModifier(wizard, Ability.INT));
            assertEquals(-5, f.stats.skillModifier(wizard, "Arcana", Ability.INT));
        }

        @Test
        @DisplayName("Greater Restoration's feebleminded option ends it (PHB p.239, p.246)")
        void greaterRestoration() {
            f.spell("Greater Restoration", 5, """
                    {"spellLevel": 5, "deliveryMethod": "AUTO_HIT", "autoHit": true,
                     "castOptions": ["exhaustion", "charmed", "petrified", "feebleminded", "curse"],
                     "removesConditions": ["exhaustion", "charmed", "petrified", "feebleminded"]}""");
            EncounterParticipant fighter = f.player("Fighter", 40, 16);
            f.effectApplier.addCondition(f.encounter, fighter, EffectApplier.FEEBLEMINDED, null);
            cast("Greater Restoration", 5, List.of(fighter), "feebleminded");
            assertFalse(CreatureStats.feebleminded(fighter));
            assertEquals(10, f.stats.abilityScore(fighter, Ability.INT));
        }
    }

    @Nested
    @DisplayName("Earthquake")
    class Earthquake {

        @Test
        @DisplayName("A concentrating creature it reaches makes a CON save or loses its concentration, on the cast and "
                + "each use (PHB p.236)")
        void concentrationSaves() {
            f.spell("Earthquake", 8, """
                    {"spellLevel": 8, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "DEX", "repeatEffect": true, "repeatActionType": "NONE",
                     "concentrationSave": "CON", "conditionsInflicted": [{"condition": "prone", "saveToAvoid": "DEX"}]}""");
            EncounterParticipant cleric = f.monster("Cleric", 40, 12);
            EncounterParticipant druid = f.monster("Druid", 40, 12);
            f.effectApplier.startConcentration(f.encounter, cleric, "Spirit Guardians", 3, 100);
            f.effectApplier.startConcentration(f.encounter, druid, "Moonbeam", 2, 10);
            // DEX saves: 20 and 20; CON saves: the cleric 2, the druid 19
            f.dice.script(20, 20, 2, 19);
            cast("Earthquake", 8, List.of(cleric, druid));
            assertNull(cleric.getConcentration());
            assertNotNull(druid.getConcentration());
            assertTrue(logged("Cleric makes a DC 15 CON save to keep concentrating on Spirit Guardians: 2, a failure"));

            // The use at the end of the caster's turn: DEX 20, then CON 1
            f.dice.script(20, 1);
            f.spellCaster.repeat(f.encounter, wizard, EffectApplier.effects(wizard).getFirst(), List.of(druid), "INT",
                    7, 15, null, "", 1);
            assertNull(druid.getConcentration());
            assertNotNull(wizard.getConcentration());
        }
    }

    @Nested
    @DisplayName("Prismatic Wall")
    class PrismaticWall {

        @Test
        @DisplayName("Passing through: a DEX save against each layer in order, damage halved on a success and "
                + "conditions on a failure (PHB p.267)")
        void layers() {
            f.spell("Prismatic Wall", 9, """
                    {"spellLevel": 9, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "durationRounds": 100,
                     "persistsWithoutConcentration": true, "repeatEffect": true, "repeatActionType": "NONE",
                     "effects": [{"effectType": "CONDITION", "condition": "blinded", "saveToAvoid": true}],
                     "layers": {"saveAbility": "DEX", "rows": [
                        {"roll": 1, "color": "red", "damageDice": "10d6", "damageType": "fire"},
                        {"roll": 6, "color": "indigo",
                         "conditionsInflicted": [{"condition": "restrained"}],
                         "countedSaves": {"ability": "CON", "successes": 3, "failures": 3,
                                          "endsConditions": ["restrained"], "failureCondition": "petrified"}},
                        {"roll": 7, "color": "violet", "conditionsInflicted": [{"condition": "blinded"}],
                         "reminder": "a WIS save at the start of {caster}'s next turn, DC {dc}"}]}}""");
            cast("Prismatic Wall", 9, List.of());
            SpellEffectEntry wall = EffectApplier.effects(wizard).getFirst();
            EncounterParticipant giant = f.monster("Giant", 100, 15);

            // Red: 10d6 of 1s = 10, a save of 20 halves it; indigo: a save of 1; violet: two d20s of 1, the restrained
            // giant saving with disadvantage
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 20, 1, 1, 1);
            f.spellCaster.repeat(f.encounter, wizard, wall, List.of(giant), "INT", 7, 15, null, "", 1);
            assertEquals(95, giant.getHpCurrent());
            assertTrue(CombatFixture.conditionNames(giant).contains("restrained"));
            assertTrue(CombatFixture.conditionNames(giant).contains("blinded"));
            assertTrue(logged("a WIS save at the start of Wizard's next turn, DC 15"));
            assertTrue(EffectApplier.effects(wizard).stream().anyMatch(effect -> effect.countedSave() != null));
            assertEquals(0, f.dice.remaining());
        }
    }

    @Nested
    @DisplayName("Rounds")
    class Rounds {

        @Test
        @DisplayName("Storm of Vengeance: a use deals its round's damage, the lightning of round 3 at up to six "
                + "creatures with a DEX save for half; the cast's round has nothing to use (PHB p.279)")
        void stormOfVengeance() {
            f.spell("Storm of Vengeance", 9, """
                    {"spellLevel": 9, "concentration": true, "durationRounds": 10, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "CON", "repeatEffect": true, "repeatActionType": "NONE",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "thunder"}],
                     "roundEffects": {"2": {"damageDice": "1d6", "damageType": "acid"},
                                      "3": {"damageDice": "10d6", "damageType": "lightning", "saveAbility": "DEX",
                                            "halfOnSave": true, "maxTargets": 6}}}""");
            EncounterParticipant ogre = f.monster("Ogre", 100, 11);
            f.dice.script(1, 1);
            cast("Storm of Vengeance", 9, List.of());
            SpellEffectEntry storm = EffectApplier.effects(wizard).getFirst();
            assertThrows(com.tabletophelper.shared.ConflictException.class, () -> f.spellCaster.repeat(f.encounter,
                    wizard, storm, List.of(ogre), "INT", 7, 15, null, "", 1));

            f.encounter.setRoundNumber(2);
            f.dice.script(4);
            f.spellCaster.repeat(f.encounter, wizard, storm, List.of(ogre), "INT", 7, 15, null, "", 1);
            assertEquals(96, ogre.getHpCurrent());

            f.encounter.setRoundNumber(3);
            List<EncounterParticipant> seven = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                seven.add(f.monster("Goblin " + i, 7, 13));
            }
            assertThrows(com.tabletophelper.shared.ValidationException.class, () -> f.spellCaster.repeat(f.encounter,
                    wizard, storm, seven, "INT", 7, 15, null, "", 1));
            // 10d6 of 2s = 20, the ogre's DEX save of 20 halves it
            f.dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 20);
            f.spellCaster.repeat(f.encounter, wizard, storm, List.of(ogre), "INT", 7, 15, null, "", 1);
            assertEquals(86, ogre.getHpCurrent());
        }

        @Test
        @DisplayName("Tsunami's wall deals a d10 less each round: 5d10 in round 2, STR save or all of it (PHB p.284)")
        void tsunami() {
            f.spell("Tsunami", 8, """
                    {"spellLevel": 8, "concentration": true, "durationRounds": 6, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "STR", "halfOnSave": true, "repeatEffect": true, "repeatActionType": "NONE",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "6d10", "damageType": "bludgeoning"}],
                     "roundEffects": {"2": {"damageDice": "5d10", "damageType": "bludgeoning", "saveAbility": "STR"},
                                      "6": {"damageDice": "1d10", "damageType": "bludgeoning", "saveAbility": "STR"}}}""");
            EncounterParticipant ogre = f.monster("Ogre", 100, 11);
            // 6d10 of 2s = 12, a STR save of 20 halves it
            f.dice.script(2, 2, 2, 2, 2, 2, 20);
            cast("Tsunami", 8, List.of(ogre));
            assertEquals(94, ogre.getHpCurrent());
            SpellEffectEntry wall = EffectApplier.effects(wizard).getFirst();

            f.encounter.setRoundNumber(2);
            // 5d10 of 2s = 10: a save of 20 takes nothing, then a save of 1 takes it all
            f.dice.script(2, 2, 2, 2, 2, 20);
            f.spellCaster.repeat(f.encounter, wizard, wall, List.of(ogre), "INT", 7, 15, null, "", 1);
            assertEquals(94, ogre.getHpCurrent());
            f.dice.script(2, 2, 2, 2, 2, 1);
            f.spellCaster.repeat(f.encounter, wizard, wall, List.of(ogre), "INT", 7, 15, null, "", 1);
            assertEquals(84, ogre.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Forms")
    class Forms {

        @Test
        @DisplayName("A polymorphed vampire spawn loses Turn Resistance and has its form's Evasion (PHB p.266, D192)")
        void traitsFollowTheForm() {
            EncounterParticipant spawn = f.monster("Vampire Spawn", 82, 15);
            spawn.getMonster().setType("undead");
            spawn.getMonster().setTraits("[{\"name\": \"Turn Resistance\", \"description\": \"The vampire has "
                    + "advantage on saving throws against any effect that turns undead.\"}]");
            assertTrue(ChannelDivinity.resistsTurning(CreatureStats.traitText(spawn)));
            assertFalse(CreatureStats.evasion(spawn));

            Monster nimble = Monster.builder().id(UUID.randomUUID()).name("Nimble Beast").type("beast")
                    .challengeRating("1").source("MM").size("Medium").hitPoints(20).armourClass(13).strength(10)
                    .dexterity(16).constitution(10).intelligence(3).wisdom(12).charisma(6)
                    .traits("[{\"name\": \"Evasion\", \"description\": \"If subjected to an effect that allows a "
                            + "Dexterity saving throw to take only half damage, it takes none on a success.\"}]")
                    .build();
            ShapeSpells.Rules rules = ShapeSpells.POLYMORPH;
            ConditionEntry entry = ConditionEntry.fromEffect(rules.entry(), null, 1, rules.spell(), wizard.getId(), null);
            List<ConditionEntry> conditions = new ArrayList<>(spawn.getActiveConditions() != null
                    ? spawn.getActiveConditions() : List.of());
            conditions.add(entry);
            spawn.setActiveConditions(conditions);
            f.effectApplier.startForm(spawn, entry.id(), f.wildShapeEffects.spellForm(nimble, spawn, rules), false);

            assertFalse(ChannelDivinity.resistsTurning(CreatureStats.traitText(spawn)));
            assertTrue(CreatureStats.evasion(spawn));
        }

        @Test
        @DisplayName("The hollyphant's Aura of Invulnerability keeps spells of 5th level or lower from it (BGDIA p.237)")
        void hollyphant() {
            EncounterParticipant hollyphant = f.monster("Hollyphant", 36, 18);
            hollyphant.getMonster().setTraits("[{\"name\": \"Aura of Invulnerability\", \"description\": \"An "
                    + "invisible aura forms a 10-foot-radius sphere around the hollyphant for as long as it lives. Any "
                    + "spell of 5th level or lower cast from outside the barrier can't affect creatures or objects "
                    + "within it, even if the spell is cast using a higher level spell slot.\"}]");
            assertEquals("Aura of Invulnerability", f.stats.spellImmunityTrait(wizard, hollyphant, 5));
            assertNull(f.stats.spellImmunityTrait(wizard, hollyphant, 6));
            assertNull(f.stats.spellImmunityTrait(hollyphant, hollyphant, 1));
        }
    }

    @Nested
    @DisplayName("Holy Aura")
    class HolyAura {

        @Test
        @DisplayName("A fiend or undead that hits an affected creature in melee makes a CON save or is blinded until the "
                + "spell ends; others don't (PHB p.250)")
        void blinding() {
            f.spell("Holy Aura", 8, """
                    {"spellLevel": 8, "concentration": true, "durationRounds": 10, "deliveryMethod": "AUTO_HIT",
                     "targetType": "MULTI_TARGET",
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "SAVE_MODE", "mode": "ADVANTAGE"}]}],
                     "retaliation": {"saveAbility": "CON", "conditionsInflicted": ["blinded"],
                                     "attackerTypes": ["fiend", "undead"]}}""");
            EncounterParticipant paladin = f.player("Paladin", 60, 15);
            cast("Holy Aura", 8, List.of(paladin));
            SpellEffectEntry aura = EffectApplier.effects(wizard).getFirst();
            assertEquals(15, aura.retaliation().dc());

            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            EncounterParticipant vampire = f.monster("Vampire", 144, 16);
            vampire.getMonster().setType("undead");
            Effect claw = new Effect("Claw", new Effect.Attack(5, RollMode.NORMAL, true, false),
                    List.of(new Effect.DamagePart(new Dice(1, 6, 0), "slashing")), null, List.of(), false, null);

            // The ogre hits (15, 3): no save
            f.dice.script(15, 3);
            f.effectApplier.apply(f.encounter, new EffectApplier.Use(ogre, "Ogre", CombatActionType.MONSTER_ACTION,
                    "uses Claw", "on", "Claw", null), claw, f.resolver.resolve(claw, List.of(f.stats.target(paladin, claw))));
            assertFalse(CombatFixture.conditionNames(ogre).contains("blinded"));
            assertEquals(0, f.dice.remaining());

            // The vampire hits (15, 3) and fails its CON save (2)
            f.dice.script(15, 3, 2);
            f.effectApplier.apply(f.encounter, new EffectApplier.Use(vampire, "Vampire", CombatActionType.MONSTER_ACTION,
                    "uses Claw", "on", "Claw", null), claw,
                    f.resolver.resolve(claw, List.of(f.stats.target(paladin, claw))));
            assertTrue(CombatFixture.conditionNames(vampire).contains("blinded"));

            // Blinded until the spell ends
            f.effectApplier.endConcentration(f.encounter, wizard, "ended by hand");
            assertFalse(CombatFixture.conditionNames(vampire).contains("blinded"));
        }
    }
}
