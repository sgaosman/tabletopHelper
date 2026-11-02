package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.ConcentrationRequest;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.SpellSlotRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.SpellSlot;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Casting in combat, end to end through SpellCaster and the EffectApplier. From the old CombatServiceTest:
 * the two spell slot cases. From the old SpellResolverEngineTest: the silence check, the deliveries applied to
 * their targets, multi-target resolution and the result description; its template reading went to
 * SpellEffectsTest.
 */
class SpellcastingServiceTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}],
             "conditionsInflicted": null}""";

    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "concentration": false, "components": {"verbal": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
               "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""";

    private static final String HOLD_PERSON = """
            {"spellName": "Hold Person", "spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
             "halfOnSave": false, "concentration": true, "durationRounds": 10, "targetCount": 1,
             "targetCountUpcastScaling": {"additionalTargetsPerLevel": 1},
             "components": {"verbal": true}, "effects": [], "conditionsInflicted": ["paralyzed"]}""";

    private CombatFixture f;
    private EncounterParticipant wizard;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        wizard.setSpellAttackBonus(5);
        wizard.setSpellSaveDc(15);
        wizard.setSpellSlotsCurrent(Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0), "4", new SpellSlot(2, 0)));
        goblin = f.monster("Goblin", 20, 15);
        goblin.getMonster().setDexterity(14);
    }

    private CastSpellResponse cast(String spell, int slotLevel, List<EncounterParticipant> targets, int... rolls) {
        f.dice.script(rolls);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
        return f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), f.dmUserId);
    }

    @Nested
    @DisplayName("Silence check")
    class SilenceCheck {

        @Test
        @DisplayName("A silenced caster can't cast a spell with a verbal component, and spends no slot")
        void silencedCasterCannotCastVerbalSpell() {
            wizard.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("silenced", null, 1))));
            f.spell("Fireball", 3, FIREBALL);

            ConflictException ex = assertThrows(ConflictException.class, () -> cast("Fireball", 3, List.of(goblin)));

            assertEquals("Cannot cast spells with verbal components while silenced", ex.getMessage());
        }

        @Test
        @DisplayName("A silenced caster can cast a spell without a verbal component")
        void silencedCasterCanCastNonVerbalSpell() {
            wizard.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("silenced", null, 1))));
            f.spell("Counterspell", 3, """
                    {"spellLevel": 3, "deliveryMethod": "AUTO_HIT", "components": {"verbal": false, "somatic": true},
                     "effects": [], "conditionsInflicted": null}""");

            assertTrue(cast("Counterspell", 3, List.of(goblin)).isAutoResolved());
        }

        @Test
        @DisplayName("A caster who isn't silenced can cast a verbal spell")
        void nonSilencedCasterCanCastVerbalSpell() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            assertTrue(cast("Fire Bolt", 0, List.of(goblin), 15, 7).isAutoResolved());
        }
    }

    @Nested
    @DisplayName("Deliveries applied to their targets")
    class Deliveries {

        @Test
        @DisplayName("A spell attack that hits deals its damage, and the response reports hit, the roll and the damage")
        void spellAttackHit() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            CastSpellResponse response = cast("Fire Bolt", 0, List.of(goblin), 12, 7);

            CastSpellResponse.TargetOutcome outcome = response.getTargets().getFirst();
            assertEquals("hit", outcome.getOutcome());
            assertEquals(17, outcome.getAttackRoll());
            assertEquals(7, outcome.getDamage());
            assertEquals(13, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A spell attack that misses deals nothing; a natural 20 is a critical hit with doubled dice")
        void spellAttackMissAndCritical() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            CastSpellResponse miss = cast("Fire Bolt", 0, List.of(goblin), 5);
            CastSpellResponse critical = cast("Fire Bolt", 0, List.of(goblin), 20, 7, 7);

            assertEquals("miss", miss.getTargets().getFirst().getOutcome());
            assertNull(miss.getTargets().getFirst().getDamage());
            assertEquals("critical", critical.getTargets().getFirst().getOutcome());
            assertEquals(14, critical.getTargets().getFirst().getDamage());
        }

        @Test
        @DisplayName("A failed save takes the full damage and a success half, reported as failed_save and saved")
        void savingThrow() {
            f.spell("Fireball", 3, FIREBALL);

            CastSpellResponse failed = cast("Fireball", 3, List.of(goblin), 4, 4, 4, 4, 3, 3, 3, 3, 3);

            assertEquals("failed_save", failed.getTargets().getFirst().getOutcome());
            assertEquals(5, failed.getTargets().getFirst().getSaveRoll());
            assertEquals(28, failed.getTargets().getFirst().getDamage());
            assertEquals(0, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A failed save gives the spell's conditions, tied to its new Concentration Instance (D162)")
        void conditionsOnAFailedSave() {
            f.spell("Hold Person", 2, HOLD_PERSON);

            CastSpellResponse response = cast("Hold Person", 2, List.of(goblin), 2);

            ConcentrationInstance instance = wizard.getConcentration();
            assertEquals("Hold Person", instance.spell());
            assertEquals(2, instance.slotLevel());
            assertEquals(10, instance.durationRounds());
            ConditionEntry paralyzed = goblin.getActiveConditions().getFirst();
            assertEquals("paralyzed", paralyzed.name());
            assertEquals(instance.id(), paralyzed.concentrationInstanceId());
            assertEquals(EndRule.CONCENTRATION, paralyzed.endRule());
            assertEquals(10, paralyzed.duration());
            assertEquals(List.of("paralyzed"), response.getTargets().getFirst().getConditionsApplied());
        }

        @Test
        @DisplayName("An automatic spell deals its damage with no roll (Magic Missile)")
        void automatic() {
            f.spell("Magic Missile", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "components": {"verbal": true},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "3d4+3", "damageType": "force"}]}""");

            CastSpellResponse response = cast("Magic Missile", 1, List.of(goblin), 2, 3, 3);

            assertEquals("hit", response.getTargets().getFirst().getOutcome());
            assertNull(response.getTargets().getFirst().getAttackRoll());
            assertEquals(9, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A spell on its caster heals the caster whatever targets were named")
        void self() {
            wizard.setHpCurrent(10);
            f.spell("Cure Self", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SELF", "effects": [],
                     "healing": {"healingDice": "1d8+3"}, "conditionsInflicted": ["shielded"]}""");

            CastSpellResponse response = cast("Cure Self", 1, List.of(goblin), 5);

            assertEquals(18, wizard.getHpCurrent());
            assertEquals(20, goblin.getHpCurrent());
            assertEquals(wizard.getId(), response.getTargets().getFirst().getTargetId());
            assertEquals("self", response.getTargets().getFirst().getOutcome());
            assertEquals(List.of("shielded"), CombatFixture.conditionNames(wizard));
        }
    }

    @Nested
    @DisplayName("Multi-target resolution and the log")
    class MultiTarget {

        @Test
        @DisplayName("Fireball rolls its damage once; each target saves on its own and gets its own entry (D157, D161)")
        void eachTargetResolvedFromOneRoll() {
            EncounterParticipant orc = f.monster("Orc", 40, 13);
            orc.getMonster().setDexterity(12);
            goblin.setHpMax(40);
            goblin.setHpCurrent(40);
            f.spell("Fireball", 3, FIREBALL);

            // 28 on 8d6, then the goblin succeeds and the orc fails
            CastSpellResponse response = cast("Fireball", 3, List.of(goblin, orc), 4, 4, 4, 4, 3, 3, 3, 3, 18, 3);

            assertEquals(26, goblin.getHpCurrent());
            assertEquals(12, orc.getHpCurrent());
            assertEquals(List.of("saved", "failed_save"),
                    response.getTargets().stream().map(CastSpellResponse.TargetOutcome::getOutcome).toList());
            List<CombatLog> casts = f.entries(CombatActionType.SPELL_CAST);
            assertEquals(3, casts.size());
            assertNull(casts.getFirst().getTargetId());
            assertEquals("Wizard casts Fireball.", casts.getFirst().getDescription());
            assertEquals("Wizard casts Fireball at Goblin: DEX save d20(18) + 2 = 20 vs DC 15 — succeeds, "
                    + "14 fire damage (half).", casts.get(1).getDescription());
            assertEquals(orc.getId(), casts.get(2).getTargetId());
        }

        @Test
        @DisplayName("A higher slot is named in the summary; the spell's own level isn't (was: result description)")
        void upcastDescription() {
            f.spell("Guiding Bolt", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
                     "components": {"verbal": true},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "radiant",
                       "upcastScaling": {"additionalDicePerLevel": "1d6"}}]}""");

            CastSpellResponse upcast = cast("Guiding Bolt", 3, List.of(goblin), 18, 1, 1, 1, 1, 1, 1);
            CastSpellResponse base = cast("Guiding Bolt", 1, List.of(goblin), 18, 1, 1, 1, 1);

            assertTrue(upcast.getResultSummary().startsWith("Wizard casts Guiding Bolt at level 3."));
            assertFalse(base.getResultSummary().contains("at level"));
        }

        @Test
        @DisplayName("A spell the DM resolves logs the cast and its reason, still starts its concentration, and spends its slot")
        void manualSpell() {
            f.spell("Wall of Force", 4, """
                    {"spellLevel": 4, "deliveryMethod": "NONE", "requiresManualResolution": true,
                     "manualResolutionReason": "Complex area effect", "concentration": true, "effects": []}""");

            CastSpellResponse response = cast("Wall of Force", 4, List.of());

            assertFalse(response.isAutoResolved());
            assertEquals("Complex area effect", response.getManualResolutionReason());
            assertEquals("Wall of Force", wizard.getConcentration().spell());
            assertEquals(new SpellSlot(2, 1), wizard.getSpellSlotsCurrent().get("4"));
            assertEquals("Wizard casts Wall of Force — Complex area effect",
                    f.entries(CombatActionType.SPELL_CAST).getFirst().getDescription());
        }

        @Test
        @DisplayName("More targets than the spell allows at that level is a 400, before any slot is spent")
        void targetCountLimit() {
            EncounterParticipant orc = f.monster("Orc", 15, 13);
            f.spell("Hold Person", 2, HOLD_PERSON);

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> cast("Hold Person", 2, List.of(goblin, orc)));

            assertEquals("Hold Person can target at most 1 creature at level 2, but 2 targets were selected",
                    ex.getMessage());
            assertEquals(new SpellSlot(3, 0), wizard.getSpellSlotsCurrent().get("2"));
        }

        @Test
        @DisplayName("A player who names a hidden monster as a target gets the 400 of an unknown one (D157)")
        void hiddenTargetForAPlayer() {
            UUID playerUserId = f.playerControlling(wizard);
            EncounterParticipant bugbear = f.monster("Bugbear", 27, 16);
            bugbear.setIsVisibleToPlayers(false);
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName("Fire Bolt");
            request.setSlotLevel(0);
            request.setTargetIds(List.of(bugbear.getId()));

            assertThrows(ValidationException.class,
                    () -> f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), playerUserId));
        }
    }

    @Nested
    @DisplayName("Concentration and lasting spells")
    class Concentration {

        @Test
        @DisplayName("Recasting a concentration spell keeps the new casting's conditions and ends the old one's (D162)")
        void recastingKeepsTheNewConditions() {
            f.spell("Hold Person", 2, HOLD_PERSON);

            cast("Hold Person", 2, List.of(goblin), 2);
            cast("Hold Person", 2, List.of(goblin), 2);

            assertEquals(1, goblin.getActiveConditions().size());
            assertEquals(wizard.getConcentration().id(), goblin.getActiveConditions().getFirst().concentrationInstanceId());
            assertTrue(f.descriptions().contains("Wizard loses concentration on Hold Person (starts concentrating on Hold Person)"));
        }

        @Test
        @DisplayName("Using a concentration spell's effect again rolls it and links its conditions to the casting")
        void repeatEffect() {
            f.spell("Moonbeam", 2, """
                    {"spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "concentration": true, "repeatEffect": true, "components": {"verbal": true},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "radiant"}]}""");
            ConcentrationInstance moonbeam = ConcentrationInstance.start("Moonbeam", 2, 1, 10);
            wizard.setConcentration(moonbeam);
            RepeatSpellEffectRequest request = new RepeatSpellEffectRequest();
            request.setTargetIds(List.of(goblin.getId()));

            f.dice.script(5, 5, 3);
            CastSpellResponse response = f.spellcasting.repeatSpellEffect(f.encounterId(), request, wizard.getId(),
                    f.dmUserId);

            assertEquals("Moonbeam", response.getSpellName());
            assertEquals(10, goblin.getHpCurrent());
            assertEquals(moonbeam, wizard.getConcentration());
            assertEquals("Wizard uses Moonbeam's effect.",
                    f.entries(CombatActionType.SPELL_EFFECT_REPEAT).getFirst().getDescription());
        }

        @Test
        @DisplayName("Using an effect again with no concentration or lasting spell is a 409")
        void repeatWithoutASpell() {
            RepeatSpellEffectRequest request = new RepeatSpellEffectRequest();
            request.setTargetIds(List.of(goblin.getId()));

            assertThrows(ConflictException.class, () -> f.spellcasting.repeatSpellEffect(f.encounterId(), request,
                    wizard.getId(), f.dmUserId));
        }

        @Test
        @DisplayName("Setting concentration by hand ends the old instance and its conditions; clearing it ends it too")
        void concentrationByHand() {
            ConcentrationInstance bless = ConcentrationInstance.start("Bless", 1, 1, 10);
            wizard.setConcentration(bless);
            goblin.setActiveConditions(new ArrayList<>(List.of(
                    ConditionEntry.fromEffect("blessed", 10, 1, "Bless", wizard.getId(), bless))));
            ConcentrationRequest request = new ConcentrationRequest();
            request.setParticipantId(wizard.getId());
            request.setSpellName("Haste");

            f.spellcasting.setConcentration(f.encounterId(), request, f.dmUserId);
            assertEquals("Haste", wizard.getConcentration().spell());
            assertNull(wizard.getConcentration().slotLevel());
            assertTrue(CombatFixture.conditionNames(goblin).isEmpty());

            request.setSpellName(null);
            f.spellcasting.setConcentration(f.encounterId(), request, f.dmUserId);
            assertNull(wizard.getConcentration());
        }
    }

    @Nested
    @DisplayName("Spell slots ({max, used}, D168)")
    class SpellSlots {

        private SpellSlotRequest slotRequest(EncounterParticipant participant, int level) {
            SpellSlotRequest request = new SpellSlotRequest();
            request.setParticipantId(participant.getId());
            request.setSlotLevel(level);
            return request;
        }

        @Test
        @DisplayName("Using a slot adds to its used count, a pact slot is found under pact_N, and a spent level is a 409")
        void useSpellSlot() {
            EncounterParticipant warlock = f.player("Warlock", 30, 13);
            warlock.setSpellSlotsCurrent(Map.of("1", new SpellSlot(2, 0), "pact_3", new SpellSlot(2, 1)));

            f.spellcasting.useSpellSlot(f.encounterId(), slotRequest(warlock, 1), f.dmUserId);
            f.spellcasting.useSpellSlot(f.encounterId(), slotRequest(warlock, 3), f.dmUserId);

            assertEquals(Map.of("1", new SpellSlot(2, 1), "pact_3", new SpellSlot(2, 2)), warlock.getSpellSlotsCurrent());
            assertThrows(ConflictException.class,
                    () -> f.spellcasting.useSpellSlot(f.encounterId(), slotRequest(warlock, 3), f.dmUserId));
        }

        @Test
        @DisplayName("Restoring a slot takes one off its used count, and a level with none used is a 409")
        void restoreSpellSlot() {
            wizard.setSpellSlotsCurrent(Map.of("2", new SpellSlot(3, 1)));

            f.spellcasting.restoreSpellSlot(f.encounterId(), slotRequest(wizard, 2), f.dmUserId);

            assertEquals(Map.of("2", new SpellSlot(3, 0)), wizard.getSpellSlotsCurrent());
            assertThrows(ConflictException.class,
                    () -> f.spellcasting.restoreSpellSlot(f.encounterId(), slotRequest(wizard, 2), f.dmUserId));
        }

        @Test
        @DisplayName("A cast with a slot spends it, a pact slot when asked, and a level with none left is a 409")
        void castSpendsASlot() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.spell("Hold Person", 2, HOLD_PERSON);
            wizard.setSpellSlotsCurrent(Map.of("2", new SpellSlot(1, 0), "pact_2", new SpellSlot(1, 0)));

            cast("Hold Person", 2, List.of(goblin), 18);
            CastSpellRequest pact = new CastSpellRequest();
            pact.setSpellName("Hold Person");
            pact.setSlotLevel(2);
            pact.setUsePactSlot(true);
            pact.setTargetIds(List.of(goblin.getId()));
            f.dice.script(18);
            f.spellcasting.castSpell(f.encounterId(), pact, wizard.getId(), f.dmUserId);

            assertEquals(Map.of("2", new SpellSlot(1, 1), "pact_2", new SpellSlot(1, 1)), wizard.getSpellSlotsCurrent());
            assertThrows(ConflictException.class, () -> cast("Hold Person", 2, List.of(goblin), 18));
        }
    }
}
