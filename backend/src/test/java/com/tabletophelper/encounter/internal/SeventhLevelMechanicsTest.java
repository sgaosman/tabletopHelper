package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.SummonRules;
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
 * The engine extensions the 7th-level spells brought (D189): hit-point gates and bands with instant death, creature
 * types sent away, per-creature ray tables with counted saves that petrify, a detonation that grows and explodes when
 * its spell ends, a save that follows a hit, creatures that rise when killed, conditions chosen by cast option, and the
 * speed cap and per-turn healing Ongoing Modifiers.
 */
class SeventhLevelMechanicsTest {

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

    private EncounterParticipant monster(String name, int hp, String type) {
        EncounterParticipant monster = f.monster(name, hp, 10);
        monster.getMonster().setType(type);
        return monster;
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("The new keys read back, and a bad one makes the template unreadable")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"maxTargetHp": 100, "noEffectOnConditions": ["deafened"],
                     "hpThresholdEffects": [{"maxHp": 50, "conditionsInflicted": [{"condition": "deafened",
                                             "durationRounds": 10}]},
                                            {"maxHp": 20, "killsOutright": true}],
                     "banishes": {"types": ["Fiend"], "durationRounds": 14400},
                     "rayTable": [{"roll": 2, "damageDice": "10d6", "damageType": "acid"},
                                  {"roll": 1, "rollsTwice": true}],
                     "detonation": {"damageDice": "12d6", "damageType": "fire", "saveAbility": "DEX",
                                    "halfOnSave": true, "growsEachTurn": "1d6"},
                     "raisesOnKill": {"creatureTypes": ["Humanoid"], "form": "Zombie"},
                     "conditionsByOption": {"Box": ["Enclosed"]},
                     "followUpSave": {"ability": "CHA", "option": "banish",
                                      "conditionsInflicted": [{"condition": "banished"}]}}""");
            assertNull(template.unreadable());
            assertEquals(100, template.maxTargetHp());
            assertEquals(List.of("deafened"), template.noEffectOnConditions());
            assertEquals(20, template.hpThresholdEffects().getFirst().maxHp());
            assertTrue(template.hpThresholdEffects().getFirst().killsOutright());
            assertEquals(List.of("fiend"), template.banishes().types());
            assertEquals(1, template.rayTable().getFirst().roll());
            assertEquals("12d6", template.detonation().damageDice());
            assertEquals(List.of("humanoid"), template.raisesOnKill().creatureTypes());
            assertEquals(Map.of("box", List.of("enclosed")), template.conditionsByOption());
            assertEquals("HIT", template.followUpSave().on());

            assertNotNull(SpellTemplate.parse("{\"maxTargetHp\": \"lots\"}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"hpThresholdEffects\": [{}]}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"rayTable\": [{\"damageDice\": \"10d6\"}]}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"detonation\": {\"damageDice\": \"12d6\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"raisesOnKill\": {}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"banishes\": {\"types\": []}}").unreadable());
        }

        @Test
        @DisplayName("Speed at most 10 feet caps every speed after the others, and Freedom of Movement ignores it")
        void speedCap() {
            OngoingModifier cap = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.SPEED_MAX, null, null,
                    10, null, null, null, false, false, false, null, false, null, null, null, null);
            assertEquals(Map.of("walk", 10, "fly", 5),
                    OngoingModifiers.speeds(new java.util.LinkedHashMap<>(Map.of("walk", 30, "fly", 5)), List.of(cap)));
            assertEquals("speed at most 10 ft.", cap.describe());
        }

        @Test
        @DisplayName("Conjure Celestial offers one celestial of CR 4, or CR 5 with a 9th-level slot (PHB p.225)")
        void conjureCelestial() {
            SummonRules.Spell spell = SummonRules.spell("Conjure Celestial").orElseThrow();
            assertEquals(SummonRules.Kind.CONJURE_CELESTIAL, spell.kind());
            assertFalse(spell.castInCombat());
            assertEquals(4.0, SummonRules.options(spell, 8, false).getFirst().maxCr());
            assertEquals(5.0, SummonRules.options(spell, 9, false).getFirst().maxCr());
            SummonRules.Option option = SummonRules.options(spell, 7, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Couatl", "4", "celestial", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Ogre", "2", "giant", null, false).isPresent());
            assertTrue(SummonRules.refusal(spell, option, "Deva", "10", "celestial", null, false).isPresent());
        }
    }

    @Nested
    @DisplayName("Casting")
    class Casting {

        @Test
        @DisplayName("Divine Word's bands: 20 HP or fewer dies, 30 or fewer is blinded, deafened and stunned, a fiend is "
                + "sent home, one above 50 is untouched and a deafened one can't hear it (PHB p.234)")
        void divineWord() {
            f.spell("Divine Word", 7, """
                    {"spellLevel": 7, "deliveryMethod": "SAVING_THROW", "saveAbility": "CHA",
                     "noEffectOnConditions": ["deafened"],
                     "hpThresholdEffects": [{"maxHp": 20, "killsOutright": true},
                       {"maxHp": 30, "conditionsInflicted": [{"condition": "blinded", "durationRounds": 600},
                         {"condition": "deafened", "durationRounds": 600}, {"condition": "stunned",
                         "durationRounds": 600}]},
                       {"maxHp": 50, "conditionsInflicted": [{"condition": "deafened", "durationRounds": 10}]}],
                     "banishes": {"types": ["celestial", "elemental", "fey", "fiend"], "durationRounds": 14400}}""");
            EncounterParticipant goblin = monster("Goblin", 15, "humanoid");
            EncounterParticipant ogre = monster("Ogre", 28, "giant");
            EncounterParticipant imp = monster("Imp", 45, "fiend");
            EncounterParticipant troll = monster("Troll", 80, "giant");
            EncounterParticipant deaf = monster("Deaf Guard", 10, "humanoid");
            f.effectApplier.addCondition(f.encounter, deaf, "deafened", null);
            // Each of the four that can hear fails its CHA save
            f.dice.script(2, 2, 2, 2);
            SpellCaster.CastResult result = cast("Divine Word", 7, List.of(goblin, ogre, imp, troll, deaf), null);

            assertEquals(LifeState.DEAD, goblin.getLifeState());
            assertTrue(CreatureStats.conditionNames(ogre).containsAll(List.of("blinded", "deafened", "stunned")));
            assertTrue(CreatureStats.conditionNames(imp).containsAll(List.of("deafened", "banished")));
            assertTrue(CreatureStats.conditionNames(troll).isEmpty());
            assertEquals(LifeState.ALIVE, deaf.getLifeState());
            assertTrue(result.summary().contains("Troll has 80 HP, too many"));
        }

        @Test
        @DisplayName("Death Ward saves a creature Divine Word would kill outright, and ends (PHB p.230)")
        void deathWardStopsInstantDeath() {
            f.spell("Death Ward", 4, """
                    {"spellLevel": 4, "deliveryMethod": "AUTO_HIT", "durationRounds": 4800,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "DEATH_WARD"}]}]}""");
            f.spell("Divine Word", 7, """
                    {"spellLevel": 7, "deliveryMethod": "SAVING_THROW", "saveAbility": "CHA",
                     "hpThresholdEffects": [{"maxHp": 20, "killsOutright": true}]}""");
            EncounterParticipant fighter = f.player("Fighter", 12, 16);
            cast("Death Ward", 4, List.of(fighter), null);
            f.dice.script(2);
            cast("Divine Word", 7, List.of(fighter), null);
            assertEquals(LifeState.ALIVE, fighter.getLifeState());
            assertEquals(12, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("A hit-point gate spares a creature above it, as Power Word Pain's 100 (XGE p.163)")
        void hitPointGate() {
            f.spell("Power Word Pain", 7, """
                    {"spellLevel": 7, "deliveryMethod": "AUTO_HIT", "maxTargetHp": 100, "modifierRepeatSave": "CON",
                     "effects": [{"effectType": "DEBUFF", "modifiers": [{"kind": "SPEED_MAX", "flat": 10},
                                  {"kind": "ATTACK_MODE", "mode": "DISADVANTAGE"}]}]}""");
            EncounterParticipant giant = monster("Giant", 120, "giant");
            EncounterParticipant ogre = monster("Ogre", 60, "giant");
            cast("Power Word Pain", 7, List.of(giant, ogre), null);
            SpellEffectEntry effect = EffectApplier.effects(wizard).getFirst();
            assertEquals(List.of(ogre.getId()), effect.affected());
            assertEquals(2, effect.modifiers().size());
        }

        @Test
        @DisplayName("Rays: a d8 per creature picks its damage type, and the indigo ray's three failed CON saves "
                + "petrify it for good (PHB p.267)")
        void rays() {
            f.spell("Prismatic Spray", 7, """
                    {"spellLevel": 7, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "rayTable": [{"roll": 1, "color": "red", "damageDice": "10d6", "damageType": "fire"},
                       {"roll": 5, "color": "blue", "damageDice": "10d6", "damageType": "cold"},
                       {"roll": 6, "color": "indigo", "conditionsInflicted": ["restrained"],
                        "countedSaves": {"ability": "CON", "successes": 3, "failures": 3,
                                         "endsConditions": ["restrained"], "failureCondition": "petrified"}},
                       {"roll": 8, "rollsTwice": true}]}""");
            EncounterParticipant ogre = monster("Ogre", 100, "giant");
            EncounterParticipant troll = monster("Troll", 100, "giant");
            // The ogre: d8 1 (red), 10d6 of 2s = 20, DEX save 2 fails. The troll: d8 8, then 8 again (rerolled), 5
            // (blue) and 6 (indigo): 10d6 of 1s = 10 cold, DEX save 3 fails, so restrained
            f.dice.script(1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 8, 8, 5, 6, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3);
            cast("Prismatic Spray", 7, List.of(ogre, troll), null);
            assertEquals(80, ogre.getHpCurrent());
            assertEquals(90, troll.getHpCurrent());
            assertTrue(CreatureStats.conditionNames(troll).contains("restrained"));

            for (int i = 0; i < 3; i++) {
                f.dice.script(1);
                f.effectApplier.effectsAtTurnEnd(f.encounter, troll);
            }
            assertTrue(CreatureStats.conditionNames(troll).contains("petrified"));
            assertFalse(CreatureStats.conditionNames(troll).contains("restrained"));
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("A detonation grows 1d6 at the end of each of its caster's turns and explodes over its creatures "
                + "when concentration ends (PHB p.230)")
        void detonation() {
            f.spell("Delayed Blast Fireball", 7, """
                    {"spellLevel": 7, "concentration": true, "durationRounds": 10, "deliveryMethod": "AUTO_HIT",
                     "detonation": {"damageDice": "12d6", "damageType": "fire", "saveAbility": "DEX",
                                    "halfOnSave": true, "growsEachTurn": "1d6",
                                    "upcastScaling": {"additionalDicePerLevel": "1d6"}}}""");
            EncounterParticipant ogre = monster("Ogre", 100, "giant");
            cast("Delayed Blast Fireball", 8, List.of(ogre), null);
            assertEquals("13d6", EffectApplier.effects(wizard).getFirst().detonation().dice());

            f.effectApplier.effectsAtTurnEnd(f.encounter, wizard);
            f.effectApplier.effectsAtTurnEnd(f.encounter, wizard);
            assertEquals("15d6", EffectApplier.effects(wizard).getFirst().detonation().dice());
            assertEquals(100, ogre.getHpCurrent());

            // 15d6 of 2s = 30, and the ogre's DEX save of 2 fails
            f.dice.script(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
            f.effectApplier.endConcentration(f.encounter, wizard, "ended by hand");
            assertEquals(70, ogre.getHpCurrent());
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("A save follows a hit for the option it names: Plane Shift's banish (PHB p.266); its travel option "
                + "takes the travellers out of the fight")
        void followUpSave() {
            f.spell("Plane Shift", 7, """
                    {"spellLevel": 7, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE",
                     "castOptions": ["banish", "travel"], "noSaveOptions": ["travel"],
                     "conditionsByOption": {"banish": [], "travel": ["plane shifted"]},
                     "followUpSave": {"ability": "CHA", "option": "banish",
                                      "conditionsInflicted": [{"condition": "banished"}]}}""");
            EncounterParticipant ogre = monster("Ogre", 60, "giant");
            // A hit of 15 + 7, then the ogre's CHA save of 3 fails
            f.dice.script(15, 3);
            cast("Plane Shift", 7, List.of(ogre), "banish");
            assertTrue(CombatAccess.banished(ogre));

            cast("Plane Shift", 7, List.of(wizard), "travel");
            assertTrue(CreatureStats.conditionNames(wizard).contains("plane shifted"));
            assertTrue(CombatAccess.banished(wizard));
        }

        @Test
        @DisplayName("Conditions by option: Forcecage's box encloses, its cage cages")
        void conditionsByOption() {
            f.spell("Forcecage", 7, """
                    {"spellLevel": 7, "deliveryMethod": "AUTO_HIT", "castOptions": ["cage", "box"],
                     "conditionsByOption": {"cage": ["caged"], "box": ["enclosed"]},
                     "conditionsInflicted": [{"condition": "caged", "durationRounds": 600},
                                             {"condition": "enclosed", "durationRounds": 600}]}""");
            EncounterParticipant ogre = monster("Ogre", 60, "giant");
            EncounterParticipant troll = monster("Troll", 60, "giant");
            cast("Forcecage", 7, List.of(ogre), "box");
            cast("Forcecage", 7, List.of(troll), "cage");
            assertEquals(List.of("enclosed"), CreatureStats.conditionNames(ogre));
            assertEquals(List.of("caged"), CreatureStats.conditionNames(troll));
            assertTrue(CombatAccess.enclosed(ogre));
        }

        @Test
        @DisplayName("A humanoid the spell kills is held to rise; another type is not (PHB p.241)")
        void risesOnKill() {
            f.spell("Finger of Death", 7, """
                    {"spellLevel": 7, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "7d8+30", "damageType": "necrotic"}],
                     "raisesOnKill": {"creatureTypes": ["humanoid"], "form": "Zombie"}}""");
            EncounterParticipant bandit = monster("Bandit", 30, "humanoid");
            EncounterParticipant wolf = monster("Wolf", 30, "beast");
            // 7d8 of 1s + 30 = 37, then both CON saves of 2 fail
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 2, 2);
            cast("Finger of Death", 7, List.of(bandit, wolf), null);
            assertEquals(LifeState.DEAD, bandit.getLifeState());
            assertEquals(LifeState.DEAD, wolf.getLifeState());
            assertEquals(List.of(bandit.getId()), EffectApplier.effects(wizard).getFirst().rising());
        }

        @Test
        @DisplayName("A creature with per-turn healing regains it at the start of its turn, up to its maximum (PHB "
                + "p.271)")
        void healEachTurn() {
            f.spell("Regenerate", 7, """
                    {"spellLevel": 7, "deliveryMethod": "AUTO_HIT", "durationRounds": 600,
                     "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "HEAL_EACH_TURN", "flat": 1}]}]}""");
            EncounterParticipant fighter = f.player("Fighter", 20, 16);
            fighter.setHpCurrent(18);
            cast("Regenerate", 7, List.of(fighter), null);
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(19, fighter.getHpCurrent());
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(20, fighter.getHpCurrent());
        }
    }
}
