package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lasting area spells automated in D199, with the templates the game data ships: Stinking Cloud, Sleet Storm,
 * Hunger of Hadar, Tasha's Caustic Brew, Gust of Wind, Spike Growth, Healing Spirit, Cordon of Arrows, Nathair's
 * Mischief, Wind Wall, Phantasmal Force and Aura of Vitality, and Moonbeam's and Spirit Guardians' additions. There is no
 * map, so the DM picks the creatures in an area at the cast and applies the area through the Spell Effect's triggers.
 * The caster's save DC is 15, and the monsters' abilities are 10, so their saves add nothing.
 */
class AreaSpellsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;
    private EncounterParticipant ogre;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        ogre = f.monster("Ogre", 50, 11);
        goblin = f.monster("Goblin", 20, 13);
        f.effectApplier.setSpellRepository(f.spellRepository);
    }

    // The spell as the game data ships its template, given to the fixture's spell repository
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            assertNotNull(in);
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    f.spell(name, definition.path("spellLevel").asInt(), definition.toString());
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        return cast(spell, slotLevel, targets, null);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets,
                                        String option) {
        shipped(spell);
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    private Optional<SpellEffectEntry> effect(String spell) {
        return EffectApplier.effects(wizard).stream().filter(e -> e.spell().equals(spell)).findFirst();
    }

    private SpellCaster.CastResult trigger(String spell, SpellTrigger when, List<EncounterParticipant> targets) {
        return trigger(spell, when, targets, 1);
    }

    private SpellCaster.CastResult trigger(String spell, SpellTrigger when, List<EncounterParticipant> targets,
                                           int times) {
        return f.spellCaster.trigger(f.encounter, wizard, effect(spell).orElseThrow(), when, targets, "INT", 7, 15,
                times);
    }

    private SpellCaster.CastResult use(String spell, List<EncounterParticipant> targets) {
        return f.spellCaster.repeat(f.encounter, wizard, effect(spell).orElseThrow(), targets, "INT", 7, 15, null,
                "", 1);
    }

    private boolean logged(String text) {
        return f.log.stream().anyMatch(entry -> entry.getDescription() != null && entry.getDescription().contains(text));
    }

    private static Optional<ConditionEntry> condition(EncounterParticipant creature, String name) {
        return creature.getActiveConditions() == null ? Optional.empty() : creature.getActiveConditions().stream()
                .filter(c -> c.name().equals(name)).findFirst();
    }

    private static int actionsUsed(EncounterParticipant creature) {
        return creature.getTurnEconomy() != null ? creature.getTurnEconomy().actionsUsed() : 0;
    }

    @Test
    @DisplayName("The new keys read back, and a bad castEffect or conditionDamage makes the template unreadable")
    void keys() {
        SpellTemplate template = SpellTemplate.parse("""
                {"castResolves": false, "failNote": "is pushed", "conditionsOutlastSpell": true,
                 "modifiersOnSuccess": true, "succeedsWithoutBreath": true, "succeedsIfImmuneToDamage": "poison",
                 "sharedRoll": true,
                 "castEffect": {"deliveryMethod": "AUTO_HIT", "conditionsInflicted": ["blinded"]},
                 "conditionDamage": {"condition": "Covered in Acid", "damageDice": "2d4", "damageType": "acid"},
                 "actionSave": {"condition": "phantasm", "ability": "INT", "skill": "Investigation"},
                 "effectUses": {"count": 1, "plusSpellcastingModifier": true, "minimum": 2},
                 "rayTable": [{"roll": 1, "saveAbility": "DEX"}],
                 "effectTriggers": [{"when": ["START_OF_TURN"], "concentrationSave": "CON", "costsActionOnFail": true,
                                     "spendsUse": true, "repeatable": true}]}""");
        assertNull(template.unreadable());
        assertFalse(template.castResolves());
        assertEquals("is pushed", template.failNote());
        assertTrue(template.conditionsOutlastSpell());
        assertTrue(template.modifiersOnSuccess());
        assertTrue(template.succeedsWithoutBreath());
        assertEquals("poison", template.succeedsIfImmuneToDamage());
        assertTrue(template.sharedRoll());
        assertEquals("AUTO_HIT", template.castEffect().effect().deliveryMethod());
        assertEquals("blinded", template.castEffect().conditions().getFirst().name());
        assertEquals("covered in acid", template.conditionDamage().condition());
        assertEquals("Investigation", template.actionSave().skill());
        assertEquals(4, template.effectUses().at(2, 2, 3));
        assertEquals(2, template.effectUses().at(2, 2, -1));
        assertEquals("DEX", template.rayTable().getFirst().saveAbility());
        assertEquals("CON", template.triggerConcentrationSave(SpellTrigger.START_OF_TURN));
        assertTrue(template.triggerCostsAction(SpellTrigger.START_OF_TURN));
        assertTrue(template.triggerSpendsUse(SpellTrigger.START_OF_TURN));
        assertTrue(template.triggerRepeatable(SpellTrigger.START_OF_TURN));
        assertFalse(template.triggerRepeatable(SpellTrigger.ENTER));
        assertTrue(SpellTemplate.parse("{}").castResolves());

        assertNotNull(SpellTemplate.parse("{\"castEffect\": true}").unreadable());
        assertNotNull(SpellTemplate.parse("{\"castEffect\": {\"damageDice\": \"lots\"}}").unreadable());
        assertNotNull(SpellTemplate.parse("{\"conditionDamage\": {\"condition\": \"acid\"}}").unreadable());
    }

    @Test
    @DisplayName("Traits that say a creature needn't breathe, as an undead's or a warforged's, are read as such")
    void breathless() {
        assertTrue(SpellCaster.breathless(List.of(new CreatureStats.Trait("Undead Nature",
                "A zombie doesn't require air, food, drink, or sleep."))));
        assertTrue(SpellCaster.breathless(List.of(new CreatureStats.Trait("Constructed Resilience",
                "You don't need to eat, drink, or breathe."))));
        assertFalse(SpellCaster.breathless(List.of(new CreatureStats.Trait("Keen Smell", "It smells well."))));
    }

    @Nested
    @DisplayName("Stinking Cloud (PHB p.278)")
    class StinkingCloud {

        @Test
        @DisplayName("The cast rolls nothing; at the start of its turn a creature that fails its CON save spends its "
                + "action, while one immune to poison or that needn't breathe succeeds")
        void retching() {
            EncounterParticipant golem = f.monster("Golem", 60, 17);
            golem.getMonster().setDamageImmunities("[\"poison\"]");
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setTraits("[{\"name\": \"Undead Nature\", \"description\": \"A zombie doesn't "
                    + "require air, food, drink, or sleep.\"}]");

            SpellCaster.CastResult cast = cast("Stinking Cloud", 3, List.of(ogre));
            assertTrue(cast.autoResolved());
            assertTrue(cast.summary().contains("Nothing is rolled yet"), cast.summary());
            assertTrue(cast.targets().isEmpty());
            assertEquals("Stinking Cloud", wizard.getConcentration().spell());
            assertTrue(effect("Stinking Cloud").isPresent());

            f.turnOf(ogre);
            CombatFixture.newTurn(ogre);
            // Only the ogre rolls: 3 against DC 15
            f.dice.script(3);
            trigger("Stinking Cloud", SpellTrigger.START_OF_TURN, List.of(ogre, golem, zombie));

            assertEquals(1, actionsUsed(ogre));
            assertEquals(0, actionsUsed(golem));
            assertEquals(0, actionsUsed(zombie));
            assertTrue(logged("Ogre spends its action this turn retching and reeling."));
            assertTrue(logged("Ogre's action this turn is spent."));
            assertFalse(logged("Golem spends its action"));
            assertEquals(0, f.dice.remaining());
        }

        @Test
        @DisplayName("A creature that fails off its own turn is told to keep to it, its economy left alone")
        void offTurn() {
            cast("Stinking Cloud", 3, List.of());
            f.dice.script(1);
            trigger("Stinking Cloud", SpellTrigger.START_OF_TURN, List.of(goblin));
            assertEquals(0, actionsUsed(goblin));
            assertTrue(logged("Goblin can't use its action on its turn"));
        }
    }

    @Test
    @DisplayName("Sleet Storm: a failed DEX save knocks prone, which outlasts the spell; a creature concentrating "
            + "at the start of its turn there makes a CON save or loses concentration (PHB p.276)")
    void sleetStorm() {
        EncounterParticipant mage = f.monster("Mage", 40, 12);
        f.effectApplier.startConcentration(f.encounter, mage, "Fly", 3, 100);

        // The ogre's DEX save: 2, prone; the cast makes no concentration save
        f.dice.script(2);
        cast("Sleet Storm", 3, List.of(ogre));
        ConditionEntry prone = condition(ogre, "prone").orElseThrow();
        assertNull(prone.concentrationInstanceId());
        assertNull(prone.duration());

        // The mage starts its turn there: its DEX save of 20 keeps it up, its CON save of 1 breaks Fly
        f.dice.script(20, 1);
        trigger("Sleet Storm", SpellTrigger.START_OF_TURN, List.of(mage));
        assertTrue(condition(mage, "prone").isEmpty());
        assertNull(mage.getConcentration());
        assertTrue(logged("Mage makes a DC 15 CON save to keep concentrating on Fly: 1, a failure"));

        // A creature entering it saves against prone only
        f.dice.script(4);
        trigger("Sleet Storm", SpellTrigger.ENTER, List.of(goblin));
        assertTrue(condition(goblin, "prone").isPresent());

        f.effectApplier.endConcentration(f.encounter, wizard, "test");
        assertTrue(condition(ogre, "prone").isPresent());
        assertTrue(condition(goblin, "prone").isPresent());
    }

    @Test
    @DisplayName("Hunger of Hadar: the cast blinds the creatures in the sphere with no roll or damage; 2d6 cold at "
            + "the start of a turn, a DEX save against 2d6 acid at its end, and blindness on entering (PHB p.251)")
    void hungerOfHadar() {
        SpellCaster.CastResult cast = cast("Hunger of Hadar", 3, List.of(ogre));
        assertEquals(50, ogre.getHpCurrent());
        ConditionEntry blinded = condition(ogre, "blinded").orElseThrow();
        assertEquals(wizard.getConcentration().id(), blinded.concentrationInstanceId());
        assertEquals(List.of("blinded"), cast.targets().getFirst().getConditionsApplied());

        f.dice.script(3, 4);
        trigger("Hunger of Hadar", SpellTrigger.START_OF_TURN, List.of(ogre));
        assertEquals(43, ogre.getHpCurrent());
        assertEquals(1, ogre.getActiveConditions().stream().filter(c -> c.name().equals("blinded")).count());

        f.dice.script(2, 2, 5);
        trigger("Hunger of Hadar", SpellTrigger.END_OF_TURN, List.of(ogre));
        assertEquals(39, ogre.getHpCurrent());

        trigger("Hunger of Hadar", SpellTrigger.ENTER, List.of(goblin));
        assertTrue(condition(goblin, "blinded").isPresent());
        assertEquals(20, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("Tasha's Caustic Brew: a failed DEX save covers a creature in acid, which deals 2d4 per slot level at "
            + "the start of each of its turns until scraped off (TCE p.115)")
    void causticBrew() {
        // No damage at the cast: the ogre fails on 2, the goblin saves on 19
        f.dice.script(2, 19);
        cast("Tasha's Caustic Brew", 2, List.of(ogre, goblin));
        assertEquals(50, ogre.getHpCurrent());
        assertTrue(condition(ogre, "covered in acid").isPresent());
        assertTrue(condition(goblin, "covered in acid").isEmpty());

        // 4d4 at a 2nd-level slot
        f.dice.script(1, 2, 3, 4);
        f.effectApplier.effectsAtTurnStart(f.encounter, ogre);
        assertEquals(40, ogre.getHpCurrent());
        assertTrue(logged("Wizard's Tasha's Caustic Brew (covered in acid) deals its damage to Ogre"));
        f.effectApplier.effectsAtTurnStart(f.encounter, goblin);
        assertEquals(20, goblin.getHpCurrent());

        f.effectApplier.removeCondition(f.encounter, ogre, "covered in acid");
        f.effectApplier.effectsAtTurnStart(f.encounter, ogre);
        assertEquals(40, ogre.getHpCurrent());
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("Gust of Wind: a creature starting its turn in the line makes a STR save, a failure logging the "
            + "15-foot push; the bonus action only turns the line (PHB p.248)")
    void gustOfWind() {
        SpellCaster.CastResult cast = cast("Gust of Wind", 2, List.of(ogre));
        assertTrue(cast.targets().isEmpty());

        f.dice.script(4, 17);
        SpellCaster.CastResult pushed = trigger("Gust of Wind", SpellTrigger.START_OF_TURN, List.of(ogre, goblin));
        assertTrue(logged("Ogre is pushed 15 feet away from Wizard along the line."));
        assertFalse(logged("Goblin is pushed"));
        assertTrue(pushed.summary().contains("Ogre is pushed 15 feet"), pushed.summary());

        assertEquals("Wizard moves Gust of Wind.", use("Gust of Wind", List.of()).summary());
    }

    @Test
    @DisplayName("Spike Growth: the DM applies the thorns once for each 5 feet moved, 2d4 piercing each, in one roll "
            + "(PHB p.277)")
    void spikeGrowth() {
        cast("Spike Growth", 2, List.of());
        f.dice.script(1, 1, 1, 1, 2, 2);
        SpellCaster.CastResult result = trigger("Spike Growth", SpellTrigger.ENTER, List.of(ogre), 3);
        assertEquals(42, ogre.getHpCurrent());
        assertTrue(result.summary().contains("3 times"), result.summary());
    }

    @Nested
    @DisplayName("Healing Spirit (XGE p.157)")
    class HealingSpirit {

        private EncounterParticipant fighter;

        @BeforeEach
        void setUp() {
            fighter = f.player("Fighter", 30, 16);
            fighter.setHpCurrent(10);
        }

        @Test
        @DisplayName("It heals 1 + the caster's modifier times, 1d6 more per slot above 2nd, and not undead")
        void heals() {
            wizard.getCharacter().setIntelligence(16);
            SpellCaster.CastResult cast = cast("Healing Spirit", 3, List.of());
            assertEquals(4, effect("Healing Spirit").orElseThrow().usesLeft());
            assertTrue(cast.summary().contains("It has 4 uses."), cast.summary());

            f.dice.script(3, 4);
            SpellCaster.CastResult healed = trigger("Healing Spirit", SpellTrigger.ENTER, List.of(fighter));
            assertEquals(17, fighter.getHpCurrent());
            assertEquals(3, effect("Healing Spirit").orElseThrow().usesLeft());
            assertTrue(healed.summary().contains("Healing Spirit has 3 left."), healed.summary());

            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("Undead");
            zombie.setHpCurrent(5);
            trigger("Healing Spirit", SpellTrigger.START_OF_TURN, List.of(zombie));
            assertEquals(5, zombie.getHpCurrent());
            assertEquals(3, effect("Healing Spirit").orElseThrow().usesLeft());
            assertTrue(logged("Healing Spirit has no effect on Zombie (undead)"));
        }

        @Test
        @DisplayName("It heals at least twice, then disappears, ending the concentration; more creatures than heals "
                + "left is a 409")
        void runsOut() {
            wizard.getCharacter().setIntelligence(8);
            cast("Healing Spirit", 2, List.of());
            assertEquals(2, effect("Healing Spirit").orElseThrow().usesLeft());
            EncounterParticipant rogue = f.player("Rogue", 20, 14);
            rogue.setHpCurrent(5);
            EncounterParticipant cleric = f.player("Cleric", 20, 14);
            assertThrows(ConflictException.class, () -> trigger("Healing Spirit", SpellTrigger.ENTER,
                    List.of(fighter, rogue, cleric)));

            f.dice.script(2);
            trigger("Healing Spirit", SpellTrigger.ENTER, List.of(fighter, rogue));
            assertEquals(12, fighter.getHpCurrent());
            assertEquals(7, rogue.getHpCurrent());
            assertTrue(effect("Healing Spirit").isEmpty());
            assertNull(wizard.getConcentration());
        }
    }

    @Test
    @DisplayName("Cordon of Arrows: 4 pieces and 2 more per slot above 2nd; each creature it strikes makes a DEX "
            + "save or takes 1d6 piercing and spends a piece, the spell ending with the last (PHB p.228)")
    void cordonOfArrows() {
        SpellCaster.CastResult cast = cast("Cordon of Arrows", 3, List.of());
        assertFalse(cast.concentration());
        assertEquals(6, effect("Cordon of Arrows").orElseThrow().usesLeft());

        f.dice.script(4, 2, 18);
        trigger("Cordon of Arrows", SpellTrigger.ENTER, List.of(ogre, goblin));
        assertEquals(46, ogre.getHpCurrent());
        assertEquals(20, goblin.getHpCurrent());
        assertEquals(4, effect("Cordon of Arrows").orElseThrow().usesLeft());

        f.effectApplier.spendEffectUses(wizard, effect("Cordon of Arrows").orElseThrow().id(), 3);
        f.dice.script(1, 1);
        trigger("Cordon of Arrows", SpellTrigger.END_OF_TURN, List.of(goblin));
        assertTrue(effect("Cordon of Arrows").isEmpty());
        assertTrue(logged("none of its uses are left"));
    }

    @Test
    @DisplayName("Nathair's Mischief: one d4 for the whole cube, each row with its own save, lasting until the start "
            + "of the caster's next turn; the caster rolls again with its use, and molasses asks for no save (FTD "
            + "p.20)")
    void nathairsMischief() {
        // The d4 lands on 2, the flowers: the ogre's DEX save of 3 fails, the goblin's 17 succeeds
        f.dice.script(2, 3, 17);
        cast("Nathair's Mischief", 2, List.of(ogre, goblin));
        ConditionEntry blinded = condition(ogre, "blinded").orElseThrow();
        assertEquals(1, blinded.duration());
        assertTrue(condition(goblin, "blinded").isEmpty());
        assertTrue(logged("Nathair's Mischief rolls d4: 2 (bouquets of flowers): a DC 15 DEX save"));

        f.dice.script(4);
        use("Nathair's Mischief", List.of(ogre, goblin));
        assertTrue(logged("Nathair's Mischief: the cube is difficult terrain until the start of Wizard's next turn"));

        // The apple pie: a WIS save against being charmed
        f.dice.script(1, 1, 20);
        use("Nathair's Mischief", List.of(ogre, goblin));
        assertTrue(condition(ogre, "charmed").isPresent());
        assertTrue(condition(goblin, "charmed").isEmpty());
        assertTrue(logged("WIS save d20(1)"));
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("Wind Wall: the creatures in it when it appears make a STR save against 3d8 bludgeoning, half on a "
            + "success (PHB p.288)")
    void windWall() {
        f.dice.script(4, 4, 4, 2, 20);
        SpellCaster.CastResult cast = cast("Wind Wall", 3, List.of(ogre, goblin));
        assertTrue(cast.autoResolved());
        assertEquals(38, ogre.getHpCurrent());
        assertEquals(14, goblin.getHpCurrent());
        assertEquals("Wind Wall", wizard.getConcentration().spell());
    }

    @Test
    @DisplayName("Phantasmal Force: a failed INT save gives the phantasm, no damage at the cast; the caster's use "
            + "deals 1d6 psychic, and the target's INT (Investigation) check with its action ends the spell (PHB "
            + "p.264)")
    void phantasmalForce() {
        f.dice.script(3);
        cast("Phantasmal Force", 2, List.of(ogre));
        assertEquals(50, ogre.getHpCurrent());
        ConditionEntry phantasm = condition(ogre, "phantasm").orElseThrow();
        assertTrue(phantasm.repeatSave().madeByAction());
        assertEquals(15, phantasm.repeatSave().dc());

        f.dice.script(5);
        use("Phantasmal Force", List.of(ogre));
        assertEquals(45, ogre.getHpCurrent());

        // A check, not a save: d20 14 + 0 fails, then 19 succeeds and the spell ends
        f.turnOf(ogre);
        CombatFixture.newTurn(ogre);
        f.dice.script(14);
        f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(ogre.getId(), phantasm.id()),
                f.dmUserId);
        assertTrue(logged("Ogre uses its action on a INT (Investigation) check against phantasm: d20(14) + 0 = 14 "
                + "vs DC 15 — fails"));
        assertEquals("Phantasmal Force", wizard.getConcentration().spell());
        CombatFixture.newTurn(ogre);
        f.dice.script(19);
        f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(ogre.getId(), phantasm.id()),
                f.dmUserId);
        assertTrue(condition(ogre, "phantasm").isEmpty());
        assertNull(wizard.getConcentration());

        EncounterParticipant skeleton = f.monster("Skeleton", 13, 13);
        skeleton.getMonster().setType("Undead");
        cast("Phantasmal Force", 2, List.of(skeleton));
        assertTrue(condition(skeleton, "phantasm").isEmpty());
    }

    @Test
    @DisplayName("Aura of Vitality: the cast heals nobody; each bonus-action use heals one creature 2d6 (PHB p.216)")
    void auraOfVitality() {
        EncounterParticipant fighter = f.player("Fighter", 30, 16);
        fighter.setHpCurrent(10);
        SpellCaster.CastResult cast = cast("Aura of Vitality", 3, List.of());
        assertTrue(cast.targets().isEmpty());
        assertEquals(30, wizard.getHpCurrent());

        f.dice.script(3, 3);
        use("Aura of Vitality", List.of(fighter));
        assertEquals(16, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("Moonbeam: a shapechanger saves with disadvantage at the cast and on later turns (PHB p.261)")
    void moonbeamShapechanger() {
        EncounterParticipant werewolf = f.monster("Werewolf", 58, 12);
        werewolf.getMonster().setType("Humanoid");
        werewolf.getMonster().setSubtype("human, shapechanger");

        // 2d10 rolls 10; the d20s 18 and 2 take the 2
        f.dice.script(5, 5, 18, 2);
        cast("Moonbeam", 2, List.of(werewolf));
        assertEquals(48, werewolf.getHpCurrent());

        f.dice.script(5, 5, 18, 2);
        trigger("Moonbeam", SpellTrigger.START_OF_TURN, List.of(werewolf));
        assertEquals(38, werewolf.getHpCurrent());
    }

    @Test
    @DisplayName("Spirit Guardians: the cast's option picks radiant or necrotic, its damage lands at once, and every "
            + "creature it reaches, saved or not, has its speed halved; a creature it reaches later is too (PHB p.278)")
    void spiritGuardians() {
        // 3d8 rolls 6: the ogre fails on 1 and takes 6, the goblin saves on 20 and takes 3
        f.dice.script(2, 2, 2, 1, 20);
        cast("Spirit Guardians", 3, List.of(ogre, goblin), "necrotic");
        assertEquals(44, ogre.getHpCurrent());
        assertEquals(17, goblin.getHpCurrent());
        assertTrue(logged("necrotic"));
        for (EncounterParticipant creature : List.of(ogre, goblin)) {
            assertTrue(ActiveModifiers.modifiersOn(creature).stream()
                    .anyMatch(m -> m.kind() == OngoingModifier.Kind.SPEED_HALVED), creature.getDisplayName());
        }
        assertEquals("necrotic", effect("Spirit Guardians").orElseThrow().option());

        EncounterParticipant orc = f.monster("Orc", 15, 13);
        f.dice.script(1, 1, 1, 20);
        SpellCaster.CastResult entered = trigger("Spirit Guardians", SpellTrigger.ENTER, List.of(orc));
        assertEquals(14, orc.getHpCurrent());
        assertTrue(entered.summary().contains("necrotic"), entered.summary());
        assertTrue(ActiveModifiers.modifiersOn(orc).stream()
                .anyMatch(m -> m.kind() == OngoingModifier.Kind.SPEED_HALVED));
    }
}
