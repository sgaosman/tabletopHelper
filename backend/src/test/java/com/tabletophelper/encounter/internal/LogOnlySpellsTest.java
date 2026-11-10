package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.StandardActionCost;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.StandardActions;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * The low-level spells whose buffs and debuffs were only logged, as they now resolve (D199), with their templates as
 * the game data ships them, and the rules behind them: the new Ongoing Modifier kinds, speeds a spell gives, the
 * advantage and disadvantage on the ability checks the engine rolls, and seeing invisible creatures.
 */
class LogOnlySpellsTest {

    private static final Map<String, JsonNode> SHIPPED = new HashMap<>();

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeAll
    static void readTemplates() throws IOException {
        try (InputStream in = LogOnlySpellsTest.class.getResourceAsStream(
                "/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                SHIPPED.put(definition.path("spellName").asString(), definition);
            }
        }
    }

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        f.effectApplier.setSpellRepository(f.spellRepository);
    }

    private void shipped(String name) {
        JsonNode definition = SHIPPED.get(name);
        assertNotNull(definition, name);
        assertNull(SpellTemplate.of(definition).unreadable(), name);
        f.spell(name, definition.path("spellLevel").asInt(), definition.toString());
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets, String option) {
        shipped(spell);
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    private boolean logged(String text) {
        return f.log.stream().anyMatch(entry -> entry.getDescription() != null && entry.getDescription().contains(text));
    }

    private static List<OngoingModifier.Kind> kindsOn(EncounterParticipant creature) {
        return ActiveModifiers.modifiersOn(creature).stream().map(OngoingModifier::kind).toList();
    }

    private static OngoingModifier modifier(OngoingModifier.Kind kind, int flat, String requires) {
        return new OngoingModifier(UUID.randomUUID(), kind, null, null, flat, null, null, List.of(), false, false,
                false, null, false, requires, List.of(), null, null);
    }

    private static OngoingModifier checkMode(RollMode mode, String ability, String skill) {
        return new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.CHECK_MODE, null, null, 0, mode, ability,
                List.of(), false, false, false, null, false, null, List.of(), null, skill);
    }

    private EncounterParticipant ogre() {
        EncounterParticipant ogre = f.monster("Ogre", 60, 11);
        ogre.getMonster().setSpeed("{\"walk\": 40}");
        return ogre;
    }

    @Nested
    @DisplayName("The rules")
    class Rules {

        @Test
        @DisplayName("Fly's 60 feet of flying and Spider Climb's climbing at the walking speed come before Haste doubles "
                + "them; Kinetic Jaunt's +10 changes the walking speed alone")
        void grantedSpeeds() {
            Map<String, Integer> base = new LinkedHashMap<>(Map.of("walk", 30));
            Map<String, Integer> speeds = OngoingModifiers.speeds(base, List.of(
                    modifier(OngoingModifier.Kind.GRANTED_SPEED, 60, "fly"),
                    modifier(OngoingModifier.Kind.GRANTED_SPEED, 0, "climb"),
                    modifier(OngoingModifier.Kind.SPEED, 10, "walk"),
                    modifier(OngoingModifier.Kind.SPEED_DOUBLED, 0, null)));
            assertEquals(80, speeds.get("walk"));
            assertEquals(120, speeds.get("fly"));
            assertEquals(60, speeds.get("climb"));
            // A higher speed of its own stays (PHB p.243)
            assertEquals(90, OngoingModifiers.speeds(new LinkedHashMap<>(Map.of("walk", 30, "fly", 90)),
                    List.of(modifier(OngoingModifier.Kind.GRANTED_SPEED, 60, "fly"))).get("fly"));
        }

        @Test
        @DisplayName("A check mode counts for its ability or every ability, and for its skill or every skill; "
                + "initiative counts a Dexterity mode and one for initiative alone")
        void checkSources() {
            List<OngoingModifiers.Labelled> modifiers = List.of(
                    new OngoingModifiers.Labelled("Owl's Wisdom", checkMode(RollMode.ADVANTAGE, "WIS", null)),
                    new OngoingModifiers.Labelled("Coin", checkMode(RollMode.DISADVANTAGE, "WIS", "Perception")),
                    new OngoingModifiers.Labelled("Coin", checkMode(RollMode.DISADVANTAGE, "DEX",
                            OngoingModifier.INITIATIVE_CHECK)),
                    new OngoingModifiers.Labelled("Heat Metal", checkMode(RollMode.DISADVANTAGE, null, null)));
            assertEquals(List.of("Owl's Wisdom", "Heat Metal"), OngoingModifiers.checkSources("WIS", null, modifiers)
                    .stream().map(AttackRules.Source::reason).toList());
            assertEquals(3, OngoingModifiers.checkSources("WIS", "Perception", modifiers).size());
            assertEquals(2, OngoingModifiers.checkSources("DEX", OngoingModifier.INITIATIVE_CHECK, modifiers).size());
            assertEquals(1, OngoingModifiers.checkSources("DEX", "Stealth", modifiers).size());
            // An unknown ability counts the modes for every ability alone
            assertEquals(1, OngoingModifiers.checkSources(null, null, modifiers).size());
        }

        @Test
        @DisplayName("A check rolls two d20s with advantage or disadvantage and one when they cancel, naming them")
        void checkRolls() {
            f.dice.script(4, 17);
            AbilityChecks.Roll advantage = AbilityChecks.roll(f.resolver::d20,
                    List.of(new AttackRules.Source(RollMode.ADVANTAGE, "Enhance Ability")));
            assertEquals(17, advantage.d20());
            assertEquals("2d20(4, 17) with advantage from Enhance Ability → 17", advantage.dice());
            f.dice.script(9);
            AbilityChecks.Roll cancelled = AbilityChecks.roll(f.resolver::d20, List.of(
                    new AttackRules.Source(RollMode.ADVANTAGE, "Enhance Ability"),
                    new AttackRules.Source(RollMode.DISADVANTAGE, "Heat Metal")));
            assertEquals(9, cancelled.d20());
            assertTrue(cancelled.dice().startsWith("d20(9) (advantage: Enhance Ability; disadvantage: Heat Metal"));
        }

        @Test
        @DisplayName("Seeing invisible creatures ends invisibility's advantage and disadvantage, but not Blur's (PHB "
                + "p.274, p.219)")
        void seesInvisible() {
            AttackRules.Side seer = new AttackRules.Side(List.of(), new AttackRules.Senses(false, false, true));
            AttackRules.Side invisible = AttackRules.Side.of(List.of("invisible"));
            assertEquals(RollMode.NORMAL, AttackRules.setup(List.of(), seer, invisible, true).rollMode());
            assertEquals(RollMode.NORMAL, AttackRules.setup(List.of(), invisible, seer, true).rollMode());
            OngoingModifier blur = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.ATTACKED_MODE, null,
                    null, 0, RollMode.DISADVANTAGE, null, List.of(), false, false, false, null, false, "SIGHTED",
                    List.of(), null, null);
            assertEquals(1, OngoingModifiers.targetSources(List.of(new OngoingModifiers.Labelled("Blur", blur)),
                    seer, AttackRules.Side.NONE, null).size());
        }

        @Test
        @DisplayName("The new kinds read in the log and on the chips")
        void describes() {
            assertEquals("can't take reactions (until the start of its next turn)",
                    new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.NO_REACTIONS, null, null, 0, null,
                            null, List.of(), false, false, false, OngoingModifier.Until.TARGET_TURN_START, false, null,
                            List.of(), null, null).describe());
            assertEquals("disadvantage on Wisdom (Perception) checks",
                    checkMode(RollMode.DISADVANTAGE, "WIS", "Perception").describe());
            assertEquals("disadvantage on initiative",
                    checkMode(RollMode.DISADVANTAGE, "DEX", OngoingModifier.INITIATIVE_CHECK).describe());
            assertEquals("disadvantage on ability checks", checkMode(RollMode.DISADVANTAGE, null, null).describe());
            assertEquals("fly speed 60 ft.", modifier(OngoingModifier.Kind.GRANTED_SPEED, 60, "fly").describe());
            assertEquals("climb speed equal to its walking speed",
                    modifier(OngoingModifier.Kind.GRANTED_SPEED, 0, "climb").describe());
            assertEquals("walking speed +10 ft.", modifier(OngoingModifier.Kind.SPEED, 10, "walk").describe());
            assertEquals("darkvision out to 60 feet", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.NOTE, null, null, 0, null, null, List.of(), false, false, false, null, false,
                    null, List.of(), null, null, "darkvision out to 60 feet").describe());
        }

        @Test
        @DisplayName("Every shipped template these spells use reads, and a bad new key makes one unreadable")
        void templatesRead() {
            for (String spell : List.of("Heat Metal", "Shocking Grasp", "Enhance Ability", "Enthrall", "Fly")) {
                assertNull(SpellTemplate.of(SHIPPED.get(spell)).unreadable(), spell);
            }
            SpellTemplate heat = SpellTemplate.of(SHIPPED.get("Heat Metal"));
            assertEquals("worn", heat.heldObjectSave().wornOption());
            assertEquals(2, heat.heldObjectSave().modifiers().size());
            assertEquals("2d6", SpellTemplate.of(SHIPPED.get("Enhance Ability")).tempHitPointsDice("Bear's Endurance"));
            assertNull(SpellTemplate.of(SHIPPED.get("Enhance Ability")).tempHitPointsDice("Cat's Grace"));
            assertNotNull(SpellTemplate.parse("{\"attackAdvantageVs\": \"LEATHER\"}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"heldObjectSave\": {\"wornOption\": \"worn\"}}").unreadable());
            assertNotNull(SpellTemplate.parse("{\"tempHitPoints\": {\"dice\": \"lots\"}}").unreadable());
        }
    }

    @Nested
    @DisplayName("Reactions and speed")
    class Debuffs {

        @Test
        @DisplayName("Shocking Grasp has advantage against an ogre in chain mail, and the hit takes its reactions "
                + "until the start of its next turn (PHB p.275)")
        void shockingGrasp() {
            EncounterParticipant ogre = ogre();
            ogre.getMonster().setAcType("chain mail");
            // advantage: 3 and 15 keep 15, + 7 hits AC 11; 1d8 lightning rolls 5
            f.dice.script(3, 15, 5);
            cast("Shocking Grasp", 0, List.of(ogre), null);

            assertEquals(55, ogre.getHpCurrent());
            assertTrue(logged("target wears metal armour"));
            assertTrue(kindsOn(ogre).contains(OngoingModifier.Kind.NO_REACTIONS));
            UUID player = UUID.randomUUID();
            ConflictException refused = assertThrows(ConflictException.class, () -> f.economy.charge(f.encounter,
                    ogre, Charge.of(ActionCost.REACTION, false), player,
                    new ActionEconomyKeeper.Command(null, "Ogre's reaction")));
            assertEquals("It can't take reactions (Shocking Grasp)", refused.getMessage());

            f.effectApplier.modifiersAtTurnStart(f.encounter, ogre);
            assertFalse(kindsOn(ogre).contains(OngoingModifier.Kind.NO_REACTIONS));
            assertTrue(EffectApplier.effects(wizard).isEmpty());
        }

        @Test
        @DisplayName("Shocking Grasp rolls one d20 against a target in leather")
        void shockingGraspLeather() {
            EncounterParticipant ogre = ogre();
            ogre.getMonster().setAcType("hide armor");
            f.dice.script(15, 5);
            cast("Shocking Grasp", 0, List.of(ogre), null);
            assertEquals(55, ogre.getHpCurrent());
            assertEquals(0, f.dice.remaining());
        }

        @Test
        @DisplayName("A character's equipped chain mail is metal armour; leather isn't")
        void characterArmour() {
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            assertFalse(f.stats.wearsMetalArmour(fighter));
            f.wears(fighter, CombatFixture.armour("Chain Mail", Item.ArmourCategory.HEAVY, 16, true));
            assertTrue(f.stats.wearsMetalArmour(fighter));
            EncounterParticipant rogue = f.player("Rogue", 30, 12);
            f.wears(rogue, CombatFixture.armour("Studded Leather Armor", Item.ArmourCategory.LIGHT, 12, false));
            assertFalse(f.stats.wearsMetalArmour(rogue));
        }

        @Test
        @DisplayName("Arms of Hadar leaves each goblin that fails its own loss of reactions, ending on its own turn "
                + "(PHB p.215)")
        void armsOfHadar() {
            EncounterParticipant first = f.monster("Goblin 1", 30, 13);
            EncounterParticipant second = f.monster("Goblin 2", 30, 13);
            f.dice.script(2, 3, 4, 4, 4, 4);
            cast("Arms of Hadar", 1, List.of(first, second), null);

            List<SpellEffectEntry> effects = EffectApplier.effects(wizard);
            assertEquals(2, effects.size());
            assertTrue(effects.stream().allMatch(effect -> effect.affected().size() == 1));
            f.effectApplier.modifiersAtTurnStart(f.encounter, first);
            assertFalse(kindsOn(first).contains(OngoingModifier.Kind.NO_REACTIONS));
            assertTrue(kindsOn(second).contains(OngoingModifier.Kind.NO_REACTIONS));
        }

        @Test
        @DisplayName("Magnify Gravity halves a failed creature's speed until the end of its next turn (EGW p.188)")
        void magnifyGravity() {
            EncounterParticipant ogre = ogre();
            f.dice.script(2, 4, 4);
            cast("Magnify Gravity", 1, List.of(ogre), null);
            assertEquals(20, TurnEconomies.speed(ogre, f.objectMapper).get("walk"));
            f.effectApplier.modifiersAtTurnStart(f.encounter, ogre);
            assertEquals(20, TurnEconomies.speed(ogre, f.objectMapper).get("walk"));
            f.effectApplier.modifiersAtTurnEnd(f.encounter, ogre);
            assertEquals(40, TurnEconomies.speed(ogre, f.objectMapper).get("walk"));
        }

        @Test
        @DisplayName("Tasha's Mind Whip takes reactions until the end of the creature's next turn and notes its single "
                + "move, action or bonus action (TCE p.115)")
        void mindWhip() {
            EncounterParticipant ogre = ogre();
            f.dice.script(2, 3, 3, 3);
            cast("Tasha's Mind Whip", 2, List.of(ogre), null);
            assertTrue(kindsOn(ogre).containsAll(List.of(OngoingModifier.Kind.NO_REACTIONS,
                    OngoingModifier.Kind.NOTE)));
            f.effectApplier.modifiersAtTurnStart(f.encounter, ogre);
            assertTrue(kindsOn(ogre).contains(OngoingModifier.Kind.NO_REACTIONS));
            f.effectApplier.modifiersAtTurnEnd(f.encounter, ogre);
            assertTrue(kindsOn(ogre).isEmpty());
        }
    }

    @Nested
    @DisplayName("Heat Metal (PHB p.250)")
    class HeatMetal {

        @Test
        @DisplayName("A creature that keeps hold of the heated weapon has disadvantage until the start of the caster's "
                + "next turn; one that fails drops it")
        void held() {
            EncounterParticipant ogre = ogre();
            // 2d8 fire: 4 + 4; the CON save: 18 succeeds against DC 15
            f.dice.script(4, 4, 18);
            cast("Heat Metal", 2, List.of(ogre), "held");
            assertEquals(52, ogre.getHpCurrent());
            assertTrue(kindsOn(ogre).containsAll(List.of(OngoingModifier.Kind.ATTACK_MODE,
                    OngoingModifier.Kind.CHECK_MODE)));
            assertTrue(logged("Ogre keeps hold of the object"));

            f.effectApplier.modifiersAtTurnStart(f.encounter, wizard);
            assertTrue(kindsOn(ogre).isEmpty());
            SpellEffectEntry effect = EffectApplier.effects(wizard).getFirst();
            assertEquals("held", effect.option());

            // the bonus action again at 2nd level: 2d8 of 1s, and the save fails on 2
            f.dice.script(1, 1, 2);
            f.spellCaster.repeat(f.encounter, wizard, effect, List.of(ogre), "INT", 7, 15, null, "", 1);
            assertEquals(50, ogre.getHpCurrent());
            assertTrue(kindsOn(ogre).isEmpty());
            assertTrue(logged("Ogre drops the object"));
        }

        @Test
        @DisplayName("Worn armour can't be dropped, so there is no save and the disadvantage comes each time; the use "
                + "again at 3rd level deals 3d8")
        void worn() {
            EncounterParticipant ogre = ogre();
            f.dice.script(4, 4, 4);
            cast("Heat Metal", 3, List.of(ogre), "worn");
            assertEquals(48, ogre.getHpCurrent());
            assertTrue(kindsOn(ogre).contains(OngoingModifier.Kind.ATTACK_MODE));
            assertTrue(logged("can't drop the armour it wears"));
            assertEquals(1, AbilityChecks.sources(ogre, Ability.STR, null).size());

            f.effectApplier.modifiersAtTurnStart(f.encounter, wizard);
            f.dice.script(2, 2, 2);
            f.spellCaster.repeat(f.encounter, wizard, EffectApplier.effects(wizard).getFirst(), List.of(ogre), "INT",
                    7, 15, null, "", 1);
            assertEquals(42, ogre.getHpCurrent());
            assertTrue(kindsOn(ogre).contains(OngoingModifier.Kind.CHECK_MODE));
        }
    }

    @Nested
    @DisplayName("Buffs")
    class Buffs {

        @Test
        @DisplayName("Enhance Ability's Bear's Endurance rolls 2d6 temporary hit points and gives advantage on "
                + "Constitution checks (PHB p.237)")
        void bearsEndurance() {
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            f.dice.script(3, 4);
            cast("Enhance Ability", 2, List.of(fighter), "Bear's Endurance");
            assertEquals(7, fighter.getHpTemp());
            assertEquals(List.of(RollMode.ADVANTAGE), AbilityChecks.sources(fighter, Ability.CON, null).stream()
                    .map(AttackRules.Source::mode).toList());
            assertTrue(AbilityChecks.sources(fighter, Ability.STR, null).isEmpty());
        }

        @Test
        @DisplayName("Enhance Ability's Cat's Grace gives advantage on initiative and Hide's Stealth, as Dexterity "
                + "checks (PHB p.237, p.189)")
        void catsGrace() {
            EncounterParticipant rogue = f.player("Rogue", 30, 14);
            cast("Enhance Ability", 2, List.of(rogue), "Cat's Grace");
            assertEquals(1, AbilityChecks.sources(rogue, Ability.DEX, OngoingModifier.INITIATIVE_CHECK).size());

            f.turnOf(rogue);
            f.dice.script(5, 16);
            f.standardActions.standardAction(f.encounterId(), new StandardActionRequest(rogue.getId(),
                    StandardActions.Action.HIDE, StandardActionCost.ACTION, null, null), f.dmUserId);
            assertTrue(logged("2d20(5, 16) with advantage from Enhance Ability → 16"));
        }

        @Test
        @DisplayName("Expeditious Retreat Dashes as it is cast, then lets its caster Dash as a bonus action (PHB p.238)")
        void expeditiousRetreat() {
            cast("Expeditious Retreat", 1, List.of(), null);
            assertTrue(wizard.getTurnEconomy().dashed());
            assertTrue(logged("Wizard takes the Dash action as it casts Expeditious Retreat"));

            CombatFixture.newTurn(wizard);
            UUID player = f.playerControlling(wizard);
            f.standardActions.standardAction(f.encounterId(), new StandardActionRequest(wizard.getId(),
                    StandardActions.Action.DASH, StandardActionCost.BONUS_ACTION, null, null), player);
            assertTrue(wizard.getTurnEconomy().bonusActionUsed());
            assertTrue(logged("(bonus action, Expeditious Retreat)"));
        }

        @Test
        @DisplayName("Fly gives 60 feet of flying speed, Spider Climb climbing at the walking speed (PHB p.243, p.277)")
        void movement() {
            EncounterParticipant ogre = ogre();
            cast("Fly", 3, List.of(ogre), null);
            assertEquals(60, TurnEconomies.speed(ogre, f.objectMapper).get("fly"));
            wizard.getCharacter().setSpeed(30);
            cast("Kinetic Jaunt", 2, List.of(), null);
            assertEquals(40, TurnEconomies.speed(wizard, f.objectMapper).get("walk"));
        }

        @Test
        @DisplayName("See Invisibility lets its caster see invisible creatures (PHB p.274)")
        void seeInvisibility() {
            cast("See Invisibility", 2, List.of(), null);
            assertTrue(CreatureStats.senses(wizard).seesInvisible());
            assertFalse(CreatureStats.senses(wizard).truesight());
        }

        @Test
        @DisplayName("Produce Flame's hurled flame is a ranged spell attack for 1d8 fire, its light a reminder (PHB "
                + "p.269)")
        void produceFlame() {
            EncounterParticipant ogre = ogre();
            f.dice.script(14, 6);
            cast("Produce Flame", 0, List.of(ogre), null);
            assertEquals(54, ogre.getHpCurrent());
            assertTrue(logged("bright light in a 10-foot radius"));
        }

        @Test
        @DisplayName("Fortune's Favor gives advantage on the creature's next attack roll, once (EGW p.186)")
        void fortunesFavor() {
            cast("Fortune's Favor", 2, List.of(wizard), null);
            OngoingModifier favour = ActiveModifiers.modifiersOn(wizard).getFirst();
            assertEquals(OngoingModifier.Kind.ATTACK_MODE, favour.kind());
            assertTrue(favour.oneShot());
        }
    }

    @Nested
    @DisplayName("Checks and saves")
    class Checks {

        @Test
        @DisplayName("Enthrall: a creature that can't be charmed succeeds, one the caster's side is fighting saves "
                + "with advantage, and a failure gives disadvantage on Perception (PHB p.238)")
        void enthrall() {
            EncounterParticipant ogre = ogre();
            EncounterParticipant golem = f.monster("Golem", 60, 15);
            golem.getMonster().setConditionImmunities("[\"charmed\"]");
            // the ogre's advantage: 3 and 5 keep 5, failing DC 15
            f.dice.script(3, 5);
            cast("Enthrall", 2, List.of(ogre, golem), null);
            assertEquals(List.of(RollMode.DISADVANTAGE), AbilityChecks.sources(ogre, Ability.WIS, "Perception")
                    .stream().map(AttackRules.Source::mode).toList());
            assertTrue(kindsOn(golem).isEmpty());
            assertTrue(logged("fought by the caster's side"));
        }

        @Test
        @DisplayName("Jim's Glowing Coin gives disadvantage on Perception and initiative (Acquisitions Incorporated p.76)")
        void glowingCoin() {
            EncounterParticipant ogre = ogre();
            f.dice.script(2);
            cast("Jim's Glowing Coin", 2, List.of(ogre), null);
            assertEquals(List.of(RollMode.DISADVANTAGE), AbilityChecks.sources(ogre, Ability.DEX,
                    OngoingModifier.INITIATIVE_CHECK).stream().map(AttackRules.Source::mode).toList());
            assertTrue(AbilityChecks.sources(ogre, Ability.DEX, "Stealth").isEmpty());
        }
    }

    @Nested
    @DisplayName("Exploration and social spells")
    class Exploration {

        @Test
        @DisplayName("Each leaves a Spell Effect on its creature for its duration, with its concentration, showing "
                + "what it gives")
        void lastingEffects() {
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            Map<String, Integer> durations = new LinkedHashMap<>();
            durations.put("Darkvision", 4800);
            durations.put("Water Breathing", 14400);
            durations.put("Tongues", 600);
            durations.put("Nondetection", 4800);
            durations.put("Air Bubble", 14400);
            durations.put("Water Walk", 600);
            durations.put("Jump", 10);
            durations.put("Feather Fall", 10);
            durations.put("Friends", 10);
            for (Map.Entry<String, Integer> spell : durations.entrySet()) {
                cast(spell.getKey(), SHIPPED.get(spell.getKey()).path("spellLevel").asInt(), List.of(fighter), null);
                SpellEffectEntry effect = EffectApplier.effects(wizard).stream()
                        .filter(each -> each.spell().equals(spell.getKey())).findFirst().orElseThrow();
                assertEquals(spell.getValue(), effect.durationRounds(), spell.getKey());
                assertEquals(List.of(fighter.getId()), effect.affected(), spell.getKey());
                assertEquals(OngoingModifier.Kind.NOTE, effect.modifiers().getFirst().kind(), spell.getKey());
            }
            for (String spell : List.of("Comprehend Languages", "Disguise Self", "Detect Magic", "Speak with Animals",
                    "Borrowed Knowledge", "Detect Evil and Good", "Detect Poison and Disease")) {
                cast(spell, SHIPPED.get(spell).path("spellLevel").asInt(), List.of(), null);
                SpellEffectEntry effect = EffectApplier.effects(wizard).stream()
                        .filter(each -> each.spell().equals(spell)).findFirst().orElseThrow();
                assertEquals(List.of(wizard.getId()), effect.affected(), spell);
                assertNotNull(effect.durationRounds(), spell);
                assertEquals(SHIPPED.get(spell).path("concentration").asBoolean(),
                        effect.concentrationInstanceId() != null, spell);
            }
        }

        @Test
        @DisplayName("Beast Bond reaches only a beast (XGE p.150)")
        void beastBond() {
            EncounterParticipant ogre = ogre();
            ogre.getMonster().setType("giant");
            shipped("Beast Bond");
            assertThrows(RuntimeException.class, () -> f.spellCaster.requireTargetTypes("Beast Bond", List.of(ogre)));
            EncounterParticipant wolf = f.monster("Wolf", 11, 13);
            wolf.getMonster().setType("beast");
            cast("Beast Bond", 1, List.of(wolf), null);
            assertTrue(kindsOn(wolf).contains(OngoingModifier.Kind.NOTE));
        }
    }
}
