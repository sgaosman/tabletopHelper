package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.shared.ConflictException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine extensions the 6th-level spells brought (D188): Evasion, a save whose success still takes the damage,
 * spell-granted truesight and damage immunity, the Globe of Invulnerability, Harm's floor and lowered maximum,
 * Disintegrate's dust, Heal's cures, immunity through a condition immunity, saves by type, a spell that forbids casting,
 * options by condition and the save made with an action.
 */
class SixthLevelMechanicsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
    }

    private SpellCaster.CastResult cast(EncounterParticipant caster, String spell, int slotLevel,
                                        List<EncounterParticipant> targets, String option) {
        return f.spellCaster.cast(f.encounter, caster, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    private static OngoingModifier modifier(OngoingModifier.Kind kind, int flat, List<String> types) {
        return new OngoingModifier(UUID.randomUUID(), kind, null, null, flat, null, null, types, false, false, false,
                null, false, null, List.of(), null, null);
    }

    // Puts the modifiers on the creatures through a Spell Effect of the caster's, as a cast leaves them
    private SpellEffectEntry under(EncounterParticipant caster, String spell, List<EncounterParticipant> creatures,
                                   OngoingModifier... modifiers) {
        SpellEffectEntry effect = f.effectApplier.startEffect(f.encounter, caster, spell, null, null, 10, null, false);
        return f.effectApplier.startModifiers(f.encounter, caster, effect.id(), creatures, List.of(modifiers), null,
                null, null, true);
    }

    private static Resolver.Target target(UUID id, int saveModifier) {
        return new Resolver.Target(id, 10, saveModifier, List.of());
    }

    private static final String FIREBOLT = """
            {"spellLevel": 5, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
             "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "fire"}]}""";

    @Nested
    @DisplayName("Saves")
    class Saves {

        @Test
        @DisplayName("Evasion: a DEX save for half takes none on a success and half on a failure (PHB p.96); a CON save "
                + "for half is unchanged")
        void evasion() {
            Effect dex = new Effect("Fireball", new Effect.Save(Ability.DEX, 15, true),
                    List.of(new Effect.DamagePart(new Dice(4, 6, 0), "fire")), null, List.of(), false, null);
            UUID rogue = UUID.randomUUID();
            f.dice.script(5, 5, 5, 5, 20);
            Resolver.TargetOutcome saved = f.resolver.resolve(dex, List.of(target(rogue, 0).withEvasion(true)))
                    .getFirst();
            assertEquals(Outcome.SAVE_SUCCESS, saved.outcome());
            assertEquals(0, saved.damage().stream().mapToInt(Resolver.Damage::amount).sum());

            f.dice.script(5, 5, 5, 5, 2);
            Resolver.TargetOutcome failed = f.resolver.resolve(dex, List.of(target(rogue, 0).withEvasion(true)))
                    .getFirst();
            assertEquals(Outcome.SAVE_FAIL, failed.outcome());
            assertEquals(10, failed.damage().stream().mapToInt(Resolver.Damage::amount).sum());
            assertTrue(failed.successDamage().isEmpty());

            Effect con = new Effect("Circle", new Effect.Save(Ability.CON, 15, true),
                    List.of(new Effect.DamagePart(new Dice(4, 6, 0), "necrotic")), null, List.of(), false, null);
            f.dice.script(5, 5, 5, 5, 2);
            assertEquals(20, f.resolver.resolve(con, List.of(target(rogue, 0).withEvasion(true))).getFirst()
                    .damage().stream().mapToInt(Resolver.Damage::amount).sum());
        }

        @Test
        @DisplayName("A save whose success still takes all the damage, as Mental Prison's 5d10 (XGE p.161)")
        void fullDamageOnSuccess() {
            Effect prison = new Effect("Mental Prison", new Effect.Save(Ability.INT, 15, false, true),
                    List.of(new Effect.DamagePart(new Dice(5, 10, 0), "psychic")), null, List.of("restrained"), true,
                    10);
            f.dice.script(2, 2, 2, 2, 2, 20);
            Resolver.TargetOutcome saved = f.resolver.resolve(prison, List.of(target(UUID.randomUUID(), 0)))
                    .getFirst();
            assertEquals(Outcome.SAVE_SUCCESS, saved.outcome());
            assertEquals(10, saved.damage().stream().mapToInt(Resolver.Damage::amount).sum());
            assertTrue(saved.conditions().isEmpty());
        }

        @Test
        @DisplayName("Who has Evasion: a rogue or monk of 7th level, not 6th; a monster with the trait; a creature under "
                + "Fizban's Platinum Shield; nobody while incapacitated")
        void whoEvades() {
            EncounterParticipant rogue = f.player("Rogue", 40, 14);
            rogue.getCharacter().setCharacterClass("Rogue");
            rogue.getCharacter().setLevel(7);
            EncounterParticipant monk = f.player("Monk", 40, 14);
            monk.getCharacter().setCharacterClass("Monk");
            monk.getCharacter().setLevel(6);
            EncounterParticipant thief = f.monster("Master Thief", 40, 14);
            thief.getMonster().setTraits("[{\"name\": \"Evasion\", \"description\": \"...\"}]");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);

            assertTrue(CreatureStats.evasion(rogue));
            assertFalse(CreatureStats.evasion(monk));
            monk.getCharacter().setLevel(7);
            assertTrue(CreatureStats.evasion(monk));
            assertTrue(CreatureStats.evasion(thief));
            assertFalse(CreatureStats.evasion(ogre));
            under(wizard, "Fizban's Platinum Shield", List.of(ogre), modifier(OngoingModifier.Kind.EVASION, 0,
                    List.of()));
            assertTrue(CreatureStats.evasion(ogre));

            f.effectApplier.addCondition(f.encounter, rogue, "stunned", null);
            assertFalse(CreatureStats.evasion(rogue));
        }

        @Test
        @DisplayName("Through a cast: the rogue takes nothing on a success, an ogre half")
        void evasionThroughACast() {
            f.spell("Firebolt Storm", 5, FIREBOLT);
            EncounterParticipant rogue = f.player("Rogue", 40, 14);
            rogue.getCharacter().setCharacterClass("Rogue");
            rogue.getCharacter().setLevel(9);
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(5, 5, 5, 5, 20, 20);
            cast(wizard, "Firebolt Storm", 5, List.of(rogue, ogre), null);
            assertEquals(40, rogue.getHpCurrent());
            assertEquals(50, ogre.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Modifiers")
    class Modifiers {

        @Test
        @DisplayName("True Seeing's truesight joins the creature's senses (PHB p.284), and an immunity modifier makes "
                + "the damage type an immunity (XGE p.159)")
        void truesightAndImmunity() {
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            assertFalse(CreatureStats.senses(ogre).truesight());
            under(wizard, "True Seeing", List.of(ogre), modifier(OngoingModifier.Kind.TRUESIGHT, 0, List.of()),
                    modifier(OngoingModifier.Kind.DAMAGE_IMMUNITY, 0, List.of("fire")));
            assertTrue(CreatureStats.senses(ogre).truesight());
            assertTrue(f.stats.defences(ogre, "fire").contains(DamageRules.Defence.IMMUNITY));
            assertFalse(f.stats.defences(ogre, "cold").contains(DamageRules.Defence.IMMUNITY));
        }

        @Test
        @DisplayName("Globe of Invulnerability: a 5th-level spell from outside has no effect inside, even upcast; a "
                + "6th-level one does; a caster inside the globe reaches the others inside (PHB p.245)")
        void globe() {
            f.spell("Firebolt Storm", 5, FIREBOLT);
            f.spell("Big Storm", 6, FIREBOLT.replace("\"spellLevel\": 5", "\"spellLevel\": 6"));
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            under(mage, "Globe of Invulnerability", List.of(mage, ogre),
                    modifier(OngoingModifier.Kind.SPELL_IMMUNITY, 5, List.of()));

            f.dice.script(5, 5, 5, 5, 2);
            SpellCaster.CastResult blocked = cast(wizard, "Firebolt Storm", 7, List.of(ogre), null);
            assertEquals(60, ogre.getHpCurrent());
            assertTrue(blocked.summary().contains("has no effect on Ogre, inside Globe of Invulnerability"),
                    blocked.summary());

            f.dice.script(5, 5, 5, 5, 2);
            cast(wizard, "Big Storm", 6, List.of(ogre), null);
            assertEquals(40, ogre.getHpCurrent());

            f.dice.script(5, 5, 5, 5, 2);
            cast(mage, "Firebolt Storm", 5, List.of(ogre), null);
            assertEquals(20, ogre.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Damage rules")
    class DamageRulesOfSpells {

        @Test
        @DisplayName("Harm can't take a creature below 1 hit point, and a failed save lowers its maximum by the damage "
                + "taken (PHB p.249)")
        void harm() {
            f.spell("Harm", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "damageFloor": 1, "hpMaxReducedByDamage": {"durationRounds": 600},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "14d6", "damageType": "necrotic"}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            int[] sixes = new int[15];
            java.util.Arrays.fill(sixes, 6);
            sixes[14] = 2;
            f.dice.script(sixes);
            cast(wizard, "Harm", 6, List.of(ogre), null);
            assertEquals(1, ogre.getHpCurrent());
            assertEquals(LifeState.ALIVE, ogre.getLifeState());
            assertEquals(1, CreatureStats.hpMax(ogre));
            SpellEffectEntry lowered = EffectApplier.effects(wizard).getFirst();
            assertEquals("Harm", lowered.spell());
            assertNull(lowered.concentrationInstanceId());

            f.effectApplier.endEffect(f.encounter, wizard, lowered.id(), "ended by hand");
            assertEquals(60, CreatureStats.hpMax(ogre));
        }

        @Test
        @DisplayName("Disintegrate leaves a player character at 0 hit points dead, with no death saves (PHB p.233)")
        void disintegrate() {
            f.spell("Disintegrate", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": false,
                     "disintegrates": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "10d6+40", "damageType": "force"}]}""");
            EncounterParticipant fighter = f.player("Fighter", 40, 16);
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2);
            SpellCaster.CastResult result = cast(wizard, "Disintegrate", 6, List.of(fighter), null);
            assertEquals(0, fighter.getHpCurrent());
            assertEquals(LifeState.DEAD, fighter.getLifeState());
            assertFalse(Boolean.TRUE.equals(fighter.getDeathSaveDue()));
            assertTrue(result.summary().contains("is disintegrated"), result.summary());

            EncounterParticipant ogre = f.monster("Ogre", 100, 11);
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2);
            cast(wizard, "Disintegrate", 6, List.of(ogre), null);
            assertEquals(50, ogre.getHpCurrent());
            assertEquals(LifeState.ALIVE, ogre.getLifeState());
        }

        @Test
        @DisplayName("Heal restores 70, 10 more for each slot above 6th, ends blinded and deafened, and does nothing to "
                + "an undead (PHB p.250)")
        void heal() {
            f.spell("Heal", 6, """
                    {"spellLevel": 6, "deliveryMethod": "AUTO_HIT", "noEffectOn": ["undead", "construct"],
                     "removesConditions": ["blinded", "deafened"],
                     "effects": [{"effectType": "HEAL", "healingDice": "70"}],
                     "healing": {"healingDice": "70",
                                 "upcastScaling": {"additionalDicePerLevel": null, "additionalFlatPerLevel": 10}}}""");
            EncounterParticipant fighter = f.player("Fighter", 120, 16);
            fighter.setHpCurrent(10);
            f.effectApplier.addCondition(f.encounter, fighter, "blinded", null);
            f.effectApplier.addCondition(f.encounter, fighter, "deafened", null);
            f.effectApplier.addCondition(f.encounter, fighter, "poisoned", null);
            cast(wizard, "Heal", 7, List.of(fighter), null);
            assertEquals(90, fighter.getHpCurrent());
            assertEquals(List.of("poisoned"), CreatureStats.conditionNames(fighter));

            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("undead");
            zombie.setHpCurrent(5);
            cast(wizard, "Heal", 6, List.of(zombie), null);
            assertEquals(5, zombie.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Who it reaches")
    class Reach {

        @Test
        @DisplayName("A creature that can't be charmed is immune to Mass Suggestion (PHB p.258), and succeeds on Mental "
                + "Prison's save but still takes its damage (XGE p.161)")
        void charmImmunity() {
            f.spell("Mass Suggestion", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                     "immuneIfImmuneTo": "charmed", "conditionsInflicted": ["suggested"]}""");
            f.spell("Mental Prison", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "INT", "fullDamageOnSave": true,
                     "succeedsIfImmuneTo": "charmed", "conditionsInflicted": ["restrained"],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "5d10", "damageType": "psychic"}]}""");
            EncounterParticipant golem = f.monster("Golem", 60, 11);
            golem.getMonster().setConditionImmunities("[\"charmed\"]");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);

            f.dice.script(2);
            SpellCaster.CastResult suggestion = cast(wizard, "Mass Suggestion", 6, List.of(golem, ogre), null);
            assertTrue(suggestion.summary().contains("Golem is immune to Mass Suggestion (can't be charmed)"),
                    suggestion.summary());
            assertEquals(List.of("suggested"), CreatureStats.conditionNames(ogre));
            assertTrue(CreatureStats.conditionNames(golem).isEmpty());

            f.dice.script(2, 2, 2, 2, 2);
            cast(wizard, "Mental Prison", 6, List.of(golem), null);
            assertEquals(50, golem.getHpCurrent());
            assertTrue(CreatureStats.conditionNames(golem).isEmpty());
        }

        @Test
        @DisplayName("Flesh to Stone: restrained on a failed save, then counted CON saves; three failures end restrained "
                + "and leave the creature petrified until removed (PHB p.243)")
        void fleshToStone() {
            f.spell("Flesh to Stone", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "concentration": true,
                     "durationRounds": 10,
                     "conditionsInflicted": [{"condition": "restrained", "durationRounds": 10}],
                     "countedSaves": {"ability": "CON", "successes": 3, "failures": 3,
                                      "endsConditions": ["restrained"], "failureCondition": "petrified"}}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(2);
            cast(wizard, "Flesh to Stone", 6, List.of(ogre), null);
            assertEquals(List.of("restrained"), CreatureStats.conditionNames(ogre));

            // Fail, succeed, fail, fail: three failures, not in a row
            for (int roll : new int[]{2, 19, 3, 4}) {
                f.dice.script(roll);
                f.effectApplier.effectsAtTurnEnd(f.encounter, ogre);
            }
            assertEquals(List.of("petrified"), CreatureStats.conditionNames(ogre));
            assertNull(ogre.getActiveConditions().getFirst().concentrationInstanceId());
        }

        @Test
        @DisplayName("Mental Prison ends when its creature succeeds on the first save, which still deals the 5d10 (XGE "
                + "p.161)")
        void mentalPrisonEndsOnSave() {
            f.spell("Mental Prison", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "INT", "fullDamageOnSave": true,
                     "concentration": true, "durationRounds": 10, "endsOnInitialSave": true,
                     "conditionsInflicted": [{"condition": "restrained", "durationRounds": 10}],
                     "repeatActionType": "NONE",
                     "repeatEffect": {"deliveryMethod": "AUTO_HIT", "damageDice": "10d10", "damageType": "psychic"},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "5d10", "damageType": "psychic"}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            f.dice.script(2, 2, 2, 2, 2, 20);
            cast(wizard, "Mental Prison", 6, List.of(ogre), null);
            assertEquals(50, ogre.getHpCurrent());
            assertNull(wizard.getConcentration());
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("Sunbeam's undead save with disadvantage (PHB p.279)")
        void saveDisadvantageByType() {
            f.spell("Sunbeam", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "saveDisadvantageTypes": ["undead", "ooze"],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "6d8", "damageType": "radiant"}]}""");
            EncounterParticipant zombie = f.monster("Zombie", 60, 8);
            zombie.getMonster().setType("undead");
            // 6d8 of 1s; d20s 20 and 3 keep 3, which fails
            f.dice.script(1, 1, 1, 1, 1, 1, 20, 3);
            cast(wizard, "Sunbeam", 6, List.of(zombie), null);
            assertEquals(54, zombie.getHpCurrent());
        }

        @Test
        @DisplayName("Tenser's Transformation's caster can't cast spells while it lasts (XGE p.168)")
        void tensersBlocksCasting() {
            f.spell("Tenser's Transformation", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SELF", "selfOnly": true, "blocksCasting": true,
                     "concentration": true, "durationRounds": 100,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "ATTACK_MODE", "mode": "ADVANTAGE",
                                  "weaponOnly": true}]}]}""");
            f.spell("Firebolt Storm", 5, FIREBOLT);
            cast(wizard, "Tenser's Transformation", 6, List.of(), null);
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> cast(wizard, "Firebolt Storm", 5, List.of(), null));
            assertTrue(refused.getMessage().contains("can't cast spells while Tenser's Transformation lasts"));
        }
    }

    @Nested
    @DisplayName("Options and action saves")
    class OptionsAndActionSaves {

        private static final String EYEBITE = """
                {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "concentration": true,
                 "durationRounds": 10, "castOptions": ["asleep", "panicked", "sickened"],
                 "conditionsByOption": {
                   "asleep": [{"condition": "unconscious", "durationRounds": 10, "endsOnDamage": true}],
                   "panicked": [{"condition": "frightened", "durationRounds": 10}],
                   "sickened": [{"condition": "sickened", "durationRounds": 10, "saveToEndEachTurn": true,
                                 "saveToEndAbility": "WIS"}]},
                 "repeatActionType": "ACTION", "repeatEffect": true}""";

        @Test
        @DisplayName("Eyebite gives the option's conditions, at the cast and at each use (PHB p.238)")
        void eyebite() {
            f.spell("Eyebite", 6, EYEBITE);
            SpellTemplate template = SpellTemplate.parse(EYEBITE);
            assertNull(template.unreadable());
            assertEquals("frightened", template.conditionsFor("PANICKED").getFirst().name());

            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            EncounterParticipant troll = f.monster("Troll", 60, 11);
            f.dice.script(2);
            cast(wizard, "Eyebite", 6, List.of(ogre), "asleep");
            assertEquals(List.of("unconscious"), CreatureStats.conditionNames(ogre));

            SpellEffectEntry effect = EffectApplier.effects(wizard).getFirst();
            f.dice.script(2);
            f.spellCaster.repeat(f.encounter, wizard, effect, List.of(troll), "INT", 7, 15, null, "", 1, "sickened");
            ConditionEntry sickened = troll.getActiveConditions().getFirst();
            assertEquals("sickened", sickened.name());
            assertNotNull(sickened.repeatSave());
            assertFalse(sickened.repeatSave().madeByAction());
        }

        @Test
        @DisplayName("Otto's Irresistible Dance: no save, a dancing condition whose WIS save is made with an action, "
                + "which the end of the creature's turn doesn't roll (PHB p.264)")
        void ottosActionSave() {
            f.spell("Otto's Irresistible Dance", 6, """
                    {"spellLevel": 6, "deliveryMethod": "AUTO_HIT", "concentration": true, "durationRounds": 10,
                     "actionSave": {"condition": "dancing", "ability": "WIS"}, "immuneIfImmuneTo": "charmed",
                     "conditionsInflicted": [{"condition": "dancing", "durationRounds": 10}],
                     "effects": [{"effectType": "DEBUFF", "modifiers": [{"kind": "ATTACKED_MODE", "mode": "ADVANTAGE"}]}]}""");
            EncounterParticipant ogre = f.monster("Ogre", 60, 11);
            cast(wizard, "Otto's Irresistible Dance", 6, List.of(ogre), null);
            ConditionEntry dancing = ogre.getActiveConditions().getFirst();
            assertEquals("dancing", dancing.name());
            assertTrue(dancing.repeatSave().madeByAction());
            assertEquals(15, dancing.repeatSave().dc());
            assertEquals(Ability.WIS, dancing.repeatSave().ability());
            assertTrue(ActiveModifiers.modifiersOn(ogre).stream()
                    .anyMatch(m -> m.kind() == OngoingModifier.Kind.ATTACKED_MODE && m.mode() == RollMode.ADVANTAGE));
        }

        @Test
        @DisplayName("The new keys read")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"spellLevel": 6, "damageFloor": 1, "disintegrates": true, "hpMaxReducedByDamage": {},
                     "removesConditions": ["blinded"], "immuneIfImmuneTo": "charmed", "succeedsIfImmuneTo": "charmed",
                     "saveDisadvantageTypes": ["ooze"], "fullDamageOnSave": true, "blocksCasting": true,
                     "movable": true, "actionSave": {"condition": "dancing", "ability": "WIS"}}""");
            assertNull(template.unreadable());
            assertEquals(1, template.damageFloor());
            assertTrue(template.disintegrates());
            assertTrue(template.reducesHpMax());
            assertEquals(600, template.hpMaxReductionRounds());
            assertEquals(List.of("blinded"), template.removesConditions());
            assertEquals("charmed", template.immuneIfImmuneTo());
            assertEquals("charmed", template.succeedsIfImmuneTo());
            assertEquals(List.of("ooze"), template.saveDisadvantageTypes());
            assertTrue(template.fullDamageOnSave());
            assertTrue(template.blocksCasting());
            assertTrue(template.movable());
            assertEquals(new SpellTemplate.ActionSave("dancing", "WIS"), template.actionSave());
            assertTrue(template.conditionsFor("anything").isEmpty());
        }
    }
}
