package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.ConcentrationRequest;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.SpellSlotRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.tabletophelper.reference.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Casting in combat, end to end through SpellCaster and the EffectApplier. From the old CombatServiceTest:
 * the two spell slot cases. From the old SpellResolverEngineTest: the silence check, the deliveries applied to
 * their targets, multi-target resolution and the result description; its template reading went to
 * SpellEffectsTest. From MonsterActionServiceTest, whose monster spells now go through castSpell (D165): a
 * monster's concentration spell spending a slot, and its innate and at-will spells. SpellSourcesTest covers the
 * rules of each Spell Source on their own.
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

    private static final String CURE_WOUNDS = """
            {"spellName": "Cure Wounds", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
             "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""";

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

    // The wizard, who knows the spell and has it prepared, casts it with a slot of slotLevel, or 0 for a cantrip
    private CastSpellResponse cast(String spell, int slotLevel, List<EncounterParticipant> targets, int... rolls) {
        f.knows(wizard, spell);
        f.dice.script(rolls);
        return f.spellcasting.castSpell(f.encounterId(), request(spell, slotLevel, targets), wizard.getId(),
                f.dmUserId);
    }

    private static CastSpellRequest request(String spell, int slotLevel, List<EncounterParticipant> targets) {
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
        return request;
    }

    private static CastSpellRequest request(String spell, SpellSource source, int slotLevel,
                                            List<EncounterParticipant> targets) {
        CastSpellRequest request = request(spell, slotLevel, targets);
        request.setSpellSource(source);
        return request;
    }

    private static int pool(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals(poolId))
                .mapToInt(ResourcePoolEntry::currentUses)
                .findFirst()
                .orElseThrow();
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
            f.knows(wizard, "Fire Bolt");
            CastSpellRequest request = request("Fire Bolt", 0, List.of(bugbear));

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), playerUserId));
            assertEquals("Participant not found", ex.getMessage());
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
        @DisplayName("A cast with a slot spends it, a pact slot when asked, and a level with none left is a 400")
        void castSpendsASlot() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.spell("Hold Person", 2, HOLD_PERSON);
            wizard.setSpellSlotsCurrent(Map.of("2", new SpellSlot(1, 0), "pact_2", new SpellSlot(1, 0)));

            cast("Hold Person", 2, List.of(goblin), 18);
            f.dice.script(18);
            f.spellcasting.castSpell(f.encounterId(), request("Hold Person", SpellSource.PACT_SLOT, 2, List.of(goblin)),
                    wizard.getId(), f.dmUserId);

            assertEquals(Map.of("2", new SpellSlot(1, 1), "pact_2", new SpellSlot(1, 1)), wizard.getSpellSlotsCurrent());
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> cast("Hold Person", 2, List.of(goblin), 18));
            assertEquals("Wizard has no level 2 spell slots left", ex.getMessage());
            assertEquals(List.of("Wizard uses a level 2 spell slot (0/1 remaining)",
                    "Wizard uses a level 2 pact slot (0/1 remaining)"),
                    f.entries(CombatActionType.SPELL_SLOT_USE).stream().map(CombatLog::getDescription).toList());
        }
    }

    @Nested
    @DisplayName("Spell legality for player characters (D164)")
    class Legality {

        @Test
        @DisplayName("A spell the character doesn't have is a 400, and nothing is spent")
        void unknownSpell() {
            f.spell("Fireball", 3, FIREBALL);

            ValidationException ex = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Fireball", 3, List.of(goblin)), wizard.getId(), f.dmUserId));

            assertEquals("Wizard can't cast Fireball: it isn't one of their spells", ex.getMessage());
            assertEquals(new SpellSlot(3, 0), wizard.getSpellSlotsCurrent().get("3"));
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("A prepared caster's unprepared spell is a 400; a known caster's needs no preparing")
        void preparedSpells() {
            f.spell("Fireball", 3, FIREBALL);
            f.knows(wizard, new KnownSpell(null, "Fireball", 3, "class:Wizard", false, null, null, null, null));
            EncounterParticipant sorcerer = f.player("Sorcerer", 30, 12);
            sorcerer.setSpellSaveDc(14);
            sorcerer.setSpellSlotsCurrent(Map.of("3", new SpellSlot(2, 0)));
            f.knows(sorcerer, new KnownSpell(null, "Fireball", 3, "class:Sorcerer", false, null, null, null, null));

            ValidationException ex = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Fireball", 3, List.of(goblin)), wizard.getId(), f.dmUserId));
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 20);
            f.spellcasting.castSpell(f.encounterId(), request("Fireball", 3, List.of(goblin)), sorcerer.getId(),
                    f.dmUserId);

            assertEquals("Wizard hasn't prepared Fireball", ex.getMessage());
            assertEquals(new SpellSlot(2, 1), sorcerer.getSpellSlotsCurrent().get("3"));
        }

        @Test
        @DisplayName("A levelled spell needs a slot of at least its level: no slot, or a lower one, is a 400")
        void levelledSpellNeedsASlot() {
            f.spell("Hold Person", 2, HOLD_PERSON);
            f.knows(wizard, "Hold Person");

            ValidationException free = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Hold Person", 0, List.of(goblin)), wizard.getId(), f.dmUserId));
            ValidationException low = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Hold Person", 1, List.of(goblin)), wizard.getId(), f.dmUserId));

            assertEquals("Hold Person is a level 2 spell, so it needs a spell slot, a pact slot or an innate use",
                    free.getMessage());
            assertEquals("Hold Person is a level 2 spell, so it needs a slot of level 2 or higher", low.getMessage());
            assertEquals(new SpellSlot(4, 0), wizard.getSpellSlotsCurrent().get("1"));
            assertNull(wizard.getConcentration());
        }

        @Test
        @DisplayName("A subclass's always-prepared spells, which the sheet doesn't list, can be cast from their level")
        void subclassSpells() {
            f.spell("Bless", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "concentration": true, "durationRounds": 10,
                     "effects": [], "conditionsInflicted": ["blessed"]}""");
            f.spell("Spiritual Weapon", 2, """
                    {"spellLevel": 2, "deliveryMethod": "NONE", "effects": []}""");
            EncounterParticipant cleric = f.player("Cleric", 30, 16);
            cleric.setSpellSlotsCurrent(Map.of("1", new SpellSlot(2, 0), "2", new SpellSlot(1, 0)));
            cleric.getCharacter().setCharacterClass("Cleric");
            cleric.getCharacter().setSubclassRef(Subclass.builder().id(UUID.randomUUID()).name("Life Domain")
                    .alwaysPreparedSpells("{\"1\": [\"bless\"], \"3\": [\"spiritual weapon\"]}").build());

            f.spellcasting.castSpell(f.encounterId(), request("Bless", 1, List.of(cleric)), cleric.getId(), f.dmUserId);
            ValidationException locked = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Spiritual Weapon", 2, List.of()), cleric.getId(), f.dmUserId));

            assertEquals("Bless", cleric.getConcentration().spell());
            assertEquals("Cleric can't cast Spiritual Weapon: it isn't one of their spells", locked.getMessage());
        }

        @Test
        @DisplayName("A race spell with uses of its own spends one from a pool started full, from the level it unlocks at")
        void raceSpellUsesItsOwnUses() {
            f.spell("Hellish Rebuke", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "fire",
                       "upcastScaling": {"additionalDicePerLevel": "1d10"}}]}""");
            EncounterParticipant tiefling = f.player("Tiefling", 30, 12);
            tiefling.setSpellSaveDc(13);
            tiefling.setSpellSlotsCurrent(Map.of("2", new SpellSlot(1, 0)));
            f.knows(tiefling, new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1, 3));

            ValidationException locked = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Hellish Rebuke", SpellSource.INNATE, 0, List.of(goblin)),
                    tiefling.getId(), f.dmUserId));
            tiefling.getCharacter().setLevel(3);
            f.dice.script(5, 5, 1);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(),
                    request("Hellish Rebuke", SpellSource.INNATE, 0, List.of(goblin)), tiefling.getId(), f.dmUserId);
            ValidationException spent = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Hellish Rebuke", SpellSource.INNATE, 0, List.of(goblin)),
                    tiefling.getId(), f.dmUserId));

            assertEquals("Tiefling can cast Hellish Rebuke from level 3", locked.getMessage());
            assertEquals(SpellSource.INNATE, response.getSpellSource());
            assertEquals(1, response.getSlotLevelUsed());
            assertEquals(10, goblin.getHpCurrent());
            assertEquals(0, pool(tiefling, "innate:hellish-rebuke"));
            assertEquals("Tiefling has no uses of Hellish Rebuke left", spent.getMessage());
            assertEquals(new SpellSlot(1, 0), tiefling.getSpellSlotsCurrent().get("2"));
            assertTrue(f.descriptions().contains("Tiefling uses an innate use of Hellish Rebuke (0/1 remaining)"));
        }
    }

    @Nested
    @DisplayName("Class spell numbers (D164)")
    class ClassSpellNumbers {

        @Test
        @DisplayName("A class spell uses its class's save DC, worked out from the live sheet, not the caster's main one")
        void classSpellUsesItsClassDc() {
            f.spell("Hold Person", 2, HOLD_PERSON);
            EncounterParticipant mystic = f.player("Mystic", 30, 12);
            mystic.setSpellSaveDc(15);
            mystic.setSpellSlotsCurrent(Map.of("2", new SpellSlot(2, 0)));
            f.knows(mystic, new KnownSpell(null, "Hold Person", 2, "class:Cleric", true, null, null, null, null));
            when(f.characterService.classSpellcasting(mystic.getCharacter(), "Cleric")).thenReturn(Optional.of(
                    new ClassSpellcasting("Cleric", null, "Cleric", 3, "WIS", 13, 5, 2, 3, null, 4, false)));

            f.dice.script(14);
            f.spellcasting.castSpell(f.encounterId(), request("Hold Person", 2, List.of(goblin)), mystic.getId(),
                    f.dmUserId);

            // 14 saves against the Cleric's DC 13, though it would fail against the main DC 15
            assertEquals(List.of(), CombatFixture.conditionNames(goblin));
        }

        @Test
        @DisplayName("A race spell keeps the caster's main save DC, and asks the character module for no class's")
        void raceSpellKeepsTheMainDc() {
            f.spell("Hellish Rebuke", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "fire"}]}""");
            EncounterParticipant tiefling = f.player("Tiefling", 30, 12);
            tiefling.setSpellSaveDc(15);
            tiefling.getCharacter().setLevel(3);
            f.knows(tiefling, new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1, 3));

            f.dice.script(5, 5, 12);
            f.spellcasting.castSpell(f.encounterId(),
                    request("Hellish Rebuke", SpellSource.INNATE, 0, List.of(goblin)), tiefling.getId(), f.dmUserId);

            // 12 + the goblin's DEX 2 fails against DC 15, so it takes all 10
            assertEquals(10, goblin.getHpCurrent());
            verify(f.characterService, never()).classSpellcasting(any(), any());
        }

        @Test
        @DisplayName("A class spell's healing adds the modifier of its class's ability score, not the attack bonus less proficiency")
        void healingAddsTheAbilityModifier() {
            EncounterParticipant cleric = clericWithAdjustedAttack();
            EncounterParticipant fighter = f.player("Fighter", 40, 18);
            fighter.setHpCurrent(20);

            f.dice.script(5);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(),
                    request("Cure Wounds", 1, List.of(fighter)), cleric.getId(), f.dmUserId);

            // WIS 16 gives +3; the attack bonus of +6 less +2 proficiency would have given +4
            assertEquals(8, response.getTargets().getFirst().getHealing());
            assertEquals(28, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("A Manual Modifier to the spell attack bonus doesn't change a spell's healing")
        void manualModifierLeavesHealingAlone() {
            EncounterParticipant cleric = clericWithAdjustedAttack();
            EncounterParticipant fighter = f.player("Fighter", 40, 18);
            fighter.setHpCurrent(20);
            CastSpellRequest request = request("Cure Wounds", 1, List.of(fighter));
            request.setOverrideSpellAttackBonus(9);

            f.dice.script(5);
            f.spellcasting.castSpell(f.encounterId(), request, cleric.getId(), f.dmUserId);

            assertEquals(28, fighter.getHpCurrent());
        }

        // A Cleric 1 with WIS 16 whose spell attack bonus is +6, one more than +2 proficiency and +3, as a DM
        // Adjustment of +1 makes it, and who knows Cure Wounds
        private EncounterParticipant clericWithAdjustedAttack() {
            f.spell("Cure Wounds", 1, CURE_WOUNDS);
            EncounterParticipant cleric = f.player("Cleric", 30, 16);
            cleric.getCharacter().setWisdom(16);
            cleric.setSpellSlotsCurrent(Map.of("1", new SpellSlot(2, 0)));
            f.knows(cleric, new KnownSpell(null, "Cure Wounds", 1, "class:Cleric", true, null, null, null, null));
            when(f.characterService.classSpellcasting(cleric.getCharacter(), "Cleric")).thenReturn(Optional.of(
                    new ClassSpellcasting("Cleric", null, "Cleric", 1, "WIS", 13, 6, 1, 3, null, 4, false)));
            return cleric;
        }
    }

    @Nested
    @DisplayName("Spell Sources (D165)")
    class Sources {

        @Test
        @DisplayName("A pact slot casts at the pact slots' level, upcasting the spell, and the response says so")
        void pactSlotCastsAtItsLevel() {
            f.spell("Hold Person", 2, HOLD_PERSON);
            EncounterParticipant warlock = f.player("Warlock", 30, 13);
            warlock.setSpellSaveDc(14);
            warlock.setSpellSlotsCurrent(Map.of("pact_3", new SpellSlot(2, 0)));
            f.knows(warlock, new KnownSpell(null, "Hold Person", 2, "class:Warlock", null, null, null, null, null));
            EncounterParticipant orc = f.monster("Orc", 15, 13);

            f.dice.script(2, 2);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(),
                    request("Hold Person", SpellSource.PACT_SLOT, 0, List.of(goblin, orc)), warlock.getId(),
                    f.dmUserId);

            assertEquals(3, response.getSlotLevelUsed());
            assertEquals(SpellSource.PACT_SLOT, response.getSpellSource());
            assertEquals(new SpellSlot(2, 1), warlock.getSpellSlotsCurrent().get("pact_3"));
            assertEquals(3, warlock.getConcentration().slotLevel());
            assertEquals(List.of("paralyzed"), CombatFixture.conditionNames(orc));
        }

        @Test
        @DisplayName("A cantrip costs nothing, and naming a slot for one is a 400")
        void cantripsAreFree() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            CastSpellResponse response = cast("Fire Bolt", 0, List.of(goblin), 15, 4);
            ValidationException ex = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Fire Bolt", SpellSource.NONE, 2, List.of(goblin)), wizard.getId(),
                    f.dmUserId));

            assertEquals(SpellSource.NONE, response.getSpellSource());
            assertEquals("Fire Bolt is a cantrip, so it is cast without a slot", ex.getMessage());
            assertTrue(f.entries(CombatActionType.SPELL_SLOT_USE).isEmpty());
        }

        @Test
        @DisplayName("The options list what the character can cast now and what pays for each; unprepared spells aren't")
        void options() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.spell("Fireball", 3, FIREBALL);
            f.spell("Hold Person", 2, HOLD_PERSON);
            f.knows(wizard, "Fire Bolt");
            f.knows(wizard, "Fireball");
            f.knows(wizard, new KnownSpell(null, "Hold Person", 2, "class:Wizard", false, null, null, null, null));
            wizard.setSpellSlotsCurrent(Map.of("3", new SpellSlot(3, 3), "4", new SpellSlot(2, 0)));

            List<SpellOption> options = f.spellcasting.spellOptions(f.encounterId(), wizard.getId(), f.dmUserId);

            assertEquals(List.of("Fire Bolt", "Fireball"), options.stream().map(SpellOption::name).toList());
            assertEquals(List.of(new SpellOption.SourceOption(SpellSource.SLOT, List.of(4), null, null)),
                    options.get(1).sources());
        }

        @Test
        @DisplayName("A player reads only their own character's options")
        void optionsAreForTheController() {
            UUID playerUserId = f.playerControlling(wizard);
            EncounterParticipant cleric = f.player("Cleric", 30, 16);
            f.playerControlling(cleric);

            assertTrue(f.spellcasting.spellOptions(f.encounterId(), wizard.getId(), playerUserId).isEmpty());
            assertThrows(ForbiddenException.class,
                    () -> f.spellcasting.spellOptions(f.encounterId(), cleric.getId(), playerUserId));
            assertThrows(ForbiddenException.class,
                    () -> f.spellcasting.spellOptions(f.encounterId(), goblin.getId(), playerUserId));
        }
    }

    @Nested
    @DisplayName("Monster spells, cast the way player characters cast (D165)")
    class MonsterSpells {

        private EncounterParticipant fighter;

        @BeforeEach
        void setUpFighter() {
            fighter = f.player("Fighter", 40, 18);
        }

        @Test
        @DisplayName("A monster's concentration spell spends a slot and starts its Concentration Instance (D162)")
        void monsterConcentrationSpell() {
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            mage.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"ability": "INT", "attackBonus": 6, "saveDC": 14, "slots": {"2": 3},
                     "spellsByLevel": {"2": ["Hold Person"]}}}"""));
            f.spell("Hold Person", 2, HOLD_PERSON);

            f.dice.script(4);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(),
                    request("Hold Person", 2, List.of(fighter)), mage.getId(), f.dmUserId);

            assertEquals("failed_save", response.getTargets().getFirst().getOutcome());
            assertEquals("Hold Person", mage.getConcentration().spell());
            assertEquals(EndRule.CONCENTRATION, fighter.getActiveConditions().getFirst().endRule());
            assertEquals(mage.getConcentration().id(), fighter.getActiveConditions().getFirst().concentrationInstanceId());
            assertEquals(1, mage.getSpellSlotsCurrent().get("2").used());
            assertEquals(List.of("Mage uses a level 2 spell slot (2/3 remaining)", "Mage casts Hold Person.",
                    "Mage casts Hold Person at Fighter: WIS save d20(4) + 0 = 4 vs DC 14 — fails, gains paralyzed."),
                    f.descriptions());
        }

        @Test
        @DisplayName("An innate spell spends a daily use, never a slot; at will costs nothing; none left is a 400")
        void innateSpells() {
            EncounterParticipant djinni = f.monster("Djinni", 161, 17);
            djinni.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"innateSpells": true, "ability": "CHA", "saveDC": 17,
                     "atWillSpells": ["Detect Evil"], "dailySpells": {"3e/day each": ["Thunderwave"]}}}"""));
            f.spell("Thunderwave", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "thunder"}]}""");
            f.spell("Detect Evil", 1, """
                    {"spellLevel": 1, "deliveryMethod": "NONE", "concentration": true, "effects": []}""");

            f.dice.script(4, 4, 18);
            CastSpellResponse thunderwave = f.spellcasting.castSpell(f.encounterId(),
                    request("Thunderwave", 0, List.of(fighter)), djinni.getId(), f.dmUserId);
            f.spellcasting.castSpell(f.encounterId(), request("Detect Evil", 0, List.of()), djinni.getId(), f.dmUserId);
            ValidationException upcast = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Thunderwave", SpellSource.INNATE, 2, List.of(fighter)), djinni.getId(),
                    f.dmUserId));

            // "3e/day each" gives three uses of a level 1 spell, not a level 3 one
            assertEquals(1, thunderwave.getSlotLevelUsed());
            assertEquals(SpellSource.INNATE, thunderwave.getSpellSource());
            assertEquals(2, pool(djinni, "innate:thunderwave"));
            assertEquals(36, fighter.getHpCurrent());
            assertEquals("Detect Evil", djinni.getConcentration().spell());
            assertEquals("Thunderwave is cast at its own level, 1, with an innate use: innate spells aren't upcast",
                    upcast.getMessage());

            djinni.setResourcePoolsCurrent(List.of(SpellSources.innatePool("Thunderwave", 3, "MONSTER", "Djinni")
                    .withCurrentUses(0)));
            ValidationException spent = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Thunderwave", 0, List.of(fighter)), djinni.getId(), f.dmUserId));
            assertEquals("Djinni has no uses of Thunderwave left", spent.getMessage());
        }

        @Test
        @DisplayName("A monster's spell adds the modifier of its spellcasting ability, whatever its stat block's attack bonus")
        void monsterUsesItsAbilityModifier() {
            EncounterParticipant priest = f.monster("Priest", 27, 13);
            priest.getMonster().setWisdom(16);
            priest.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"ability": "WIS", "attackBonus": 9, "saveDC": 13, "slots": {"1": 4},
                     "spellsByLevel": {"1": ["Cure Wounds"]}}}"""));
            f.spell("Cure Wounds", 1, CURE_WOUNDS);
            goblin.setHpCurrent(5);

            f.dice.script(4);
            f.spellcasting.castSpell(f.encounterId(), request("Cure Wounds", 1, List.of(goblin)), priest.getId(),
                    f.dmUserId);

            // WIS 16 gives +3, where +9 less a CR 1 monster's +2 proficiency would have given +7
            assertEquals(12, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A monster can't cast a spell its stat block doesn't list, or a levelled spell without a slot")
        void monsterRefusals() {
            EncounterParticipant mage = f.monster("Mage", 40, 12);
            mage.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"saveDC": 14, "slots": {"1": 4}, "spellsByLevel": {"1": ["Thunderwave"]}}}"""));
            f.spell("Thunderwave", 1, "{\"spellLevel\": 1, \"deliveryMethod\": \"NONE\", \"effects\": []}");
            f.spell("Fireball", 3, FIREBALL);

            ValidationException unknown = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Fireball", 3, List.of(fighter)), mage.getId(), f.dmUserId));
            ValidationException free = assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(
                    f.encounterId(), request("Thunderwave", 0, List.of(fighter)), mage.getId(), f.dmUserId));

            assertEquals("Mage can't cast Fireball: it isn't one of their spells", unknown.getMessage());
            assertEquals("Thunderwave is a level 1 spell, so it needs a spell slot, a pact slot or an innate use",
                    free.getMessage());
            assertEquals(0, mage.getSpellSlotsCurrent().get("1").used());
        }
    }

    @Nested
    @DisplayName("Manual Modifiers (D164)")
    class ManualModifierCases {

        @Test
        @DisplayName("The DM's spell attack bonus replaces the caster's, and is logged with its value")
        void dmOverride() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.knows(wizard, "Fire Bolt");
            CastSpellRequest request = request("Fire Bolt", 0, List.of(goblin));
            request.setOverrideSpellAttackBonus(9);

            f.dice.script(6, 4);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), f.dmUserId);

            assertEquals(15, response.getTargets().getFirst().getAttackRoll());
            assertEquals("Manual Modifier (DM): Wizard's Fire Bolt uses spell attack bonus +9 instead of +5.",
                    f.descriptions().getFirst());
        }

        @Test
        @DisplayName("A player's save DC is used and logged while the campaign allows players Manual Modifiers")
        void playerOverrideAllowed() {
            UUID playerUserId = f.playerControlling(wizard);
            f.spell("Hold Person", 2, HOLD_PERSON);
            f.knows(wizard, "Hold Person");
            CastSpellRequest request = request("Hold Person", 2, List.of(goblin));
            request.setOverrideSpellSaveDC(20);

            f.dice.script(17);
            CastSpellResponse response = f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(),
                    playerUserId);

            assertEquals("failed_save", response.getTargets().getFirst().getOutcome());
            assertTrue(f.descriptions().contains(
                    "Manual Modifier (player): Wizard's Hold Person uses spell save DC 20 instead of 15."));
        }

        @Test
        @DisplayName("A player's Manual Modifier is a 403 when the campaign doesn't allow them, and nothing is spent")
        void playerOverrideRefused() {
            UUID playerUserId = f.playerControlling(wizard);
            f.campaign.setAllowPlayerManualModifiers(false);
            f.spell("Hold Person", 2, HOLD_PERSON);
            f.knows(wizard, "Hold Person");
            CastSpellRequest request = request("Hold Person", 2, List.of(goblin));
            request.setOverrideSpellSaveDC(20);
            RepeatSpellEffectRequest repeat = new RepeatSpellEffectRequest();
            repeat.setTargetIds(List.of(goblin.getId()));
            repeat.setOverrideSpellAttackBonus(9);

            ForbiddenException cast = assertThrows(ForbiddenException.class,
                    () -> f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), playerUserId));
            ForbiddenException again = assertThrows(ForbiddenException.class,
                    () -> f.spellcasting.repeatSpellEffect(f.encounterId(), repeat, wizard.getId(), playerUserId));

            assertEquals("This campaign doesn't allow players to enter Manual Modifiers", cast.getMessage());
            assertEquals(cast.getMessage(), again.getMessage());
            assertEquals(new SpellSlot(3, 0), wizard.getSpellSlotsCurrent().get("2"));
            assertTrue(f.log.isEmpty());
        }
    }

    @Nested
    @DisplayName("The action economy (D174)")
    class Economy {

        private static final String HEALING_WORD = """
                {"spellName": "Healing Word", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                 "healing": {"healingDice": "1d4+MOD"}}""";

        private static final String SHIELD = """
                {"spellName": "Shield", "spellLevel": 1, "deliveryMethod": "SELF", "effects": []}""";

        private UUID playerUserId;

        @BeforeEach
        void player() {
            playerUserId = f.playerControlling(wizard);
            f.spell("Fire Bolt", 0, FIRE_BOLT).setCastingTime("1 action");
            f.spell("Fireball", 3, FIREBALL).setCastingTime("1 action");
            f.spell("Healing Word", 1, HEALING_WORD).setCastingTime("1 bonus action");
            f.spell("Shield", 1, SHIELD).setCastingTime(
                    "1 reaction, which you take when you are hit by an attack or targeted by the magic missile spell");
            for (String spell : List.of("Fire Bolt", "Fireball", "Healing Word", "Shield")) {
                f.knows(wizard, spell);
            }
        }

        private CastSpellResponse playerCasts(String spell, int slotLevel, List<EncounterParticipant> targets,
                                              int... rolls) {
            f.dice.script(rolls);
            return f.spellcasting.castSpell(f.encounterId(), request(spell, slotLevel, targets), wizard.getId(),
                    playerUserId);
        }

        @Test
        @DisplayName("After a bonus-action spell a player's levelled spell is a 409 before the slot is spent, and a "
                + "cantrip of 1 action is allowed (PHB p.202)")
        void bonusActionSpellRule() {
            playerCasts("Healing Word", 1, List.of(wizard), 2);
            int entries = f.log.size();

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> playerCasts("Fireball", 3, List.of(goblin)));

            assertEquals("It cast a spell as a bonus action this turn, so the only other spell it can cast is a "
                    + "cantrip with a casting time of 1 action", ex.getMessage());
            assertEquals(3, wizard.getSpellSlotsCurrent().get("3").remaining());
            assertEquals(entries, f.log.size());
            assertEquals(20, goblin.getHpCurrent());

            playerCasts("Fire Bolt", 0, List.of(goblin), 15, 6);
            assertEquals(14, goblin.getHpCurrent());
            assertTrue(f.descriptions().contains("Wizard casts Healing Word (bonus action)."));
            assertTrue(wizard.getTurnEconomy().bonusActionUsed());
            assertEquals(1, wizard.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("A player's reaction spell may be cast on another creature's turn, once until their own turn")
        void reactionSpellOffTurn() {
            f.turnOf(goblin);

            playerCasts("Shield", 1, List.of(wizard));
            ConflictException ex = assertThrows(ConflictException.class,
                    () -> playerCasts("Shield", 1, List.of(wizard)));
            ForbiddenException offTurn = assertThrows(ForbiddenException.class,
                    () -> playerCasts("Fire Bolt", 0, List.of(goblin)));

            assertTrue(wizard.getTurnEconomy().reactionUsed());
            assertEquals(3, wizard.getSpellSlotsCurrent().get("1").remaining());
            assertEquals("It has already taken its reaction; it comes back at the start of its turn", ex.getMessage());
            assertEquals("You can only cast a spell on your turn; off your turn only a reaction can be taken",
                    offTurn.getMessage());
            assertTrue(f.descriptions().contains("Wizard casts Shield (reaction)."));
        }

        @Test
        @DisplayName("A spell longer than a turn is a 409 for a player, and the DM's is cast and logged as over")
        void longerCastingTime() {
            f.spell("Cure Wounds", 1, CURE_WOUNDS).setCastingTime("1 minute");
            f.knows(wizard, "Cure Wounds");

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> playerCasts("Cure Wounds", 1, List.of(wizard)));
            assertEquals("Cure Wounds takes longer than a turn to cast, so it can't be cast in combat",
                    ex.getMessage());
            assertEquals(4, wizard.getSpellSlotsCurrent().get("1").remaining());

            f.dice.script(4);
            f.spellcasting.castSpell(f.encounterId(), request("Cure Wounds", 1, List.of(wizard)), wizard.getId(),
                    f.dmUserId);
            assertEquals(3, wizard.getSpellSlotsCurrent().get("1").remaining());
            assertTrue(f.descriptions().contains("Wizard's Cure Wounds (longer than a turn) is over the action "
                    + "economy: Cure Wounds takes longer than a turn to cast, so it can't be cast in combat"));
        }

        @Test
        @DisplayName("The DM's over-budget cast is spent and logged for a player character, not for a monster")
        void dmOverBudget() {
            goblin.setHpMax(200);
            goblin.setHpCurrent(200);
            f.dice.script(3, 3, 3, 3, 3, 3, 3, 3, 5);
            f.spellcasting.castSpell(f.encounterId(), request("Fireball", 3, List.of(goblin)), wizard.getId(),
                    f.dmUserId);
            f.dice.script(3, 3, 3, 3, 3, 3, 3, 3, 3, 5);
            f.spellcasting.castSpell(f.encounterId(), request("Fireball", 4, List.of(goblin)), wizard.getId(),
                    f.dmUserId);

            assertEquals(1, wizard.getSpellSlotsCurrent().get("4").remaining());
            assertEquals(2, wizard.getTurnEconomy().actionsUsed());
            assertTrue(f.descriptions().contains("Wizard's Fireball is over the action economy: it has no action "
                    + "left this turn"));

            EncounterParticipant mage = f.monster("Mage", 40, 12);
            mage.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"ability": "INT", "attackBonus": 5, "saveDC": 15,
                     "spellsByLevel": {"0": ["Fire Bolt"]}}}"""));
            int overEntries = (int) f.descriptions().stream().filter(entry -> entry.contains("over the action"))
                    .count();
            for (int i = 0; i < 2; i++) {
                f.dice.script(15, 2);
                f.spellcasting.castSpell(f.encounterId(), request("Fire Bolt", 0, List.of(wizard)), mage.getId(),
                        f.dmUserId);
            }
            assertEquals(2, mage.getTurnEconomy().actionsUsed());
            assertEquals(overEntries, f.descriptions().stream().filter(entry -> entry.contains("over the action"))
                    .count());
        }

        @Test
        @DisplayName("Using an effect again costs what its template says: a bonus action, an action, or nothing")
        void repeatCosts() {
            f.spell("Spiritual Weapon", 2, """
                    {"spellLevel": 2, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE",
                     "repeatEffect": true, "repeatActionType": "BONUS_ACTION", "persistsWithoutConcentration": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "force"}]}""");
            wizard.setActiveSpell("Spiritual Weapon");
            wizard.setActiveSpellSlotLevel(2);
            RepeatSpellEffectRequest request = new RepeatSpellEffectRequest();
            request.setTargetIds(List.of(goblin.getId()));

            f.dice.script(15, 4);
            f.spellcasting.repeatSpellEffect(f.encounterId(), request, wizard.getId(), playerUserId);
            ConflictException ex = assertThrows(ConflictException.class,
                    () -> f.spellcasting.repeatSpellEffect(f.encounterId(), request, wizard.getId(), playerUserId));

            assertEquals("It has already taken its bonus action this turn", ex.getMessage());
            assertEquals(16, goblin.getHpCurrent());
            assertEquals(0, wizard.getTurnEconomy().actionsUsed());
            assertTrue(f.descriptions().contains("Wizard uses Spiritual Weapon's effect (bonus action)."));

            f.spell("Spirit Guardians", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS", "halfOnSave": true,
                     "concentration": true, "repeatEffect": true, "repeatActionType": "NONE",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "3d8", "damageType": "radiant"}]}""");
            wizard.setConcentration(ConcentrationInstance.start("Spirit Guardians", 3, 1, 100));
            for (int i = 0; i < 2; i++) {
                f.dice.script(1, 1, 1, 1);
                f.spellcasting.repeatSpellEffect(f.encounterId(), request, wizard.getId(), playerUserId);
            }
            assertEquals(16 - 6, goblin.getHpCurrent());
            assertEquals(0, wizard.getTurnEconomy().actionsUsed());
        }
    }

    @Nested
    @DisplayName("Armour worn without proficiency (M13.5, PHB p.144)")
    class ArmourPenalty {

        private final Item chainMail = CombatFixture.armour("Chain Mail", Item.ArmourCategory.HEAVY, 16, true);

        @Test
        @DisplayName("A caster in armour it isn't proficient with can't cast, a 409 that spends no slot")
        void cannotCast() {
            f.wears(wizard, chainMail);
            f.spell("Fireball", 3, FIREBALL);

            ConflictException ex = assertThrows(ConflictException.class, () -> cast("Fireball", 3, List.of(goblin)));

            assertEquals("Wizard can't cast spells while wearing Chain Mail without proficiency", ex.getMessage());
            assertEquals(0, wizard.getSpellSlotsCurrent().get("3").used());
        }

        @Test
        @DisplayName("The options of a caster in armour it isn't proficient with each say why it can't cast, for the DM too; without the armour they say nothing")
        void optionsGiveTheReason() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.knows(wizard, "Fire Bolt");
            assertNull(f.spellcasting.spellOptions(f.encounterId(), wizard.getId(), f.dmUserId).getFirst()
                    .unavailableReason());

            f.wears(wizard, chainMail);
            List<SpellOption> options = f.spellcasting.spellOptions(f.encounterId(), wizard.getId(), f.dmUserId);

            assertEquals(List.of("Wizard can't cast spells while wearing Chain Mail without proficiency"),
                    options.stream().map(SpellOption::unavailableReason).toList());
        }

        @Test
        @DisplayName("A target in armour it isn't proficient with makes its DEX save with disadvantage")
        void targetSavesWithDisadvantage() {
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            f.wears(fighter, chainMail);
            f.spell("Fireball", 3, FIREBALL);

            // 8d6 of 3s, then the two d20s of the save: 18 and 4 keep 4, short of DC 15
            CastSpellResponse response = cast("Fireball", 3, List.of(fighter), 3, 3, 3, 3, 3, 3, 3, 3, 18, 4);

            assertEquals("failed_save", response.getTargets().getFirst().getOutcome());
            assertEquals(4, response.getTargets().getFirst().getSaveRoll());
            assertEquals(6, fighter.getHpCurrent());
        }
    }
}
