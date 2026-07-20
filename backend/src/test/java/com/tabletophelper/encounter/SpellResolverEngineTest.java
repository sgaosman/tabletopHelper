package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpellResolverEngineTest {

    @Mock private SpellRepository spellRepository;

    private SpellResolverEngine engine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Encounter encounter;
    private EncounterParticipant caster;
    private EncounterParticipant target;

    @BeforeEach
    void setUp() {
        engine = new SpellResolverEngine(spellRepository, objectMapper);

        encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .participants(new ArrayList<>())
                .build();

        caster = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.PLAYER)
                .displayName("Wizard")
                .hpMax(30)
                .hpCurrent(30)
                .armourClass(12)
                .isAlive(true)
                .build();

        target = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.MONSTER)
                .displayName("Goblin")
                .hpMax(20)
                .hpCurrent(20)
                .armourClass(15)
                .isAlive(true)
                .build();

        encounter.getParticipants().add(caster);
        encounter.getParticipants().add(target);
    }

    private Spell buildSpell(String name, String effectTemplate) {
        return Spell.builder()
                .id(UUID.randomUUID())
                .name(name)
                .level(0)
                .effectTemplate(effectTemplate)
                .build();
    }

    private void stubSpell(String name, String effectTemplate) {
        Spell spell = buildSpell(name, effectTemplate);
        lenient().when(spellRepository.findByNameIgnoreCase(name)).thenReturn(Optional.of(spell));
    }

    // ================================================================
    // No effect template / spell not found
    // ================================================================

    @Nested
    @DisplayName("Manual resolution fallback")
    class ManualResolution {

        @Test
        @DisplayName("Spell not found returns manual result")
        void spellNotFound() {
            when(spellRepository.findByNameIgnoreCase("Unknown Spell")).thenReturn(Optional.empty());

            var result = engine.resolveSpell(encounter, caster, "Unknown Spell", 0,
                    List.of(target.getId()), 5, 13, null);

            assertFalse(result.resolved());
            assertTrue(result.requiresManualResolution());
            assertEquals("No effect template available", result.manualResolutionReason());
        }

        @Test
        @DisplayName("Spell without effect template returns manual result")
        void noEffectTemplate() {
            Spell spell = Spell.builder().id(UUID.randomUUID()).name("Wish").level(9).build();
            when(spellRepository.findByNameIgnoreCase("Wish")).thenReturn(Optional.of(spell));

            var result = engine.resolveSpell(encounter, caster, "Wish", 9,
                    List.of(target.getId()), 5, 13, null);

            assertFalse(result.resolved());
            assertTrue(result.requiresManualResolution());
        }

        @Test
        @DisplayName("Spell with requiresManualResolution=true returns manual result")
        void requiresManualResolution() {
            String template = """
                {"spellName":"Wall of Force","spellLevel":5,"deliveryMethod":"NONE",
                 "requiresManualResolution":true,"manualResolutionReason":"Complex area effect",
                 "concentration":true,"components":{"verbal":true,"somatic":true,"material":true},
                 "effects":[],"conditionsInflicted":null}""";
            stubSpell("Wall of Force", template);

            var result = engine.resolveSpell(encounter, caster, "Wall of Force", 5,
                    List.of(target.getId()), 5, 13, null);

            assertFalse(result.resolved());
            assertTrue(result.requiresManualResolution());
            assertEquals("Complex area effect", result.manualResolutionReason());
        }
    }

    // ================================================================
    // Silence check
    // ================================================================

    @Nested
    @DisplayName("Silence check")
    class SilenceCheck {

        @Test
        @DisplayName("Silenced caster cannot cast verbal spell")
        void silencedCasterCannotCastVerbalSpell() {
            caster.setActiveConditions("[{\"name\":\"silenced\",\"duration\":null}]");

            String template = """
                {"spellName":"Fire Bolt","spellLevel":0,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"1d10","damageType":"fire"}],
                 "conditionsInflicted":null}""";
            stubSpell("Fire Bolt", template);

            assertThrows(IllegalArgumentException.class, () ->
                    engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                            List.of(target.getId()), 5, 13, null));
        }

        @Test
        @DisplayName("Silenced caster can cast non-verbal spell")
        void silencedCasterCanCastNonVerbalSpell() {
            caster.setActiveConditions("[{\"name\":\"silenced\",\"duration\":null}]");

            String template = """
                {"spellName":"Counterspell","spellLevel":3,"deliveryMethod":"AUTO_HIT",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":false,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[],"conditionsInflicted":null}""";
            stubSpell("Counterspell", template);

            try (MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {
                var result = engine.resolveSpell(encounter, caster, "Counterspell", 3,
                        List.of(target.getId()), 5, 13, null);
                assertTrue(result.resolved());
            }
        }

        @Test
        @DisplayName("Non-silenced caster can cast verbal spell")
        void nonSilencedCasterCanCastVerbalSpell() {
            String template = """
                {"spellName":"Fire Bolt","spellLevel":0,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"1d10","damageType":"fire"}],
                 "conditionsInflicted":null}""";
            stubSpell("Fire Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(15, 10);
                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);
                assertTrue(result.resolved());
            }
        }
    }

    // ================================================================
    // Cantrip scaling
    // ================================================================

    @Nested
    @DisplayName("Cantrip scaling by character level")
    class CantripScaling {

        private final String FIRE_BOLT_TEMPLATE = """
            {"spellName":"Fire Bolt","spellLevel":0,"deliveryMethod":"SPELL_ATTACK",
             "requiresManualResolution":false,"concentration":false,
             "components":{"verbal":true,"somatic":true},
             "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
             "effects":[{"effectType":"DAMAGE","damageDice":"1d10","damageType":"fire",
               "cantripScaling":{"5":"2d10","11":"3d10","17":"4d10"}}],
             "conditionsInflicted":null}""";

        @Test
        @DisplayName("Level 1 caster gets base dice (1d10)")
        void level1GetsBaseDice() {
            PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).level(1).build();
            caster.setCharacter(pc);
            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("1d10"));
            }
        }

        @Test
        @DisplayName("Level 4 caster still gets base dice (1d10)")
        void level4GetsBaseDice() {
            PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).level(4).build();
            caster.setCharacter(pc);
            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("1d10"));
            }
        }

        @Test
        @DisplayName("Level 5 caster gets 2d10")
        void level5Gets2d10() {
            PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).level(5).build();
            caster.setCharacter(pc);
            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("2d10"))
                        .thenReturn(new DiceRoller.RollResult(14, 0, 14, 2, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("2d10"));
            }
        }

        @Test
        @DisplayName("Level 11 caster gets 3d10")
        void level11Gets3d10() {
            PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).level(11).build();
            caster.setCharacter(pc);
            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("3d10"))
                        .thenReturn(new DiceRoller.RollResult(21, 0, 21, 3, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("3d10"));
            }
        }

        @Test
        @DisplayName("Level 17 caster gets 4d10")
        void level17Gets4d10() {
            PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).level(17).build();
            caster.setCharacter(pc);
            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("4d10"))
                        .thenReturn(new DiceRoller.RollResult(28, 0, 28, 4, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("4d10"));
            }
        }

        @Test
        @DisplayName("Monster uses CR-derived level for cantrip scaling")
        void monsterCRBasedScaling() {
            Monster monster = Monster.builder()
                    .id(UUID.randomUUID())
                    .name("Lich")
                    .challengeRating("21")
                    .build();
            EncounterParticipant monsterCaster = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.MONSTER)
                    .displayName("Lich")
                    .hpMax(135)
                    .hpCurrent(135)
                    .armourClass(17)
                    .isAlive(true)
                    .monster(monster)
                    .build();
            encounter.getParticipants().add(monsterCaster);

            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("4d10"))
                        .thenReturn(new DiceRoller.RollResult(28, 0, 28, 4, 10));

                var result = engine.resolveSpell(encounter, monsterCaster, "Fire Bolt", 0,
                        List.of(target.getId()), 11, 19, null);

                diceMock.verify(() -> DiceRoller.roll("4d10"));
            }
        }

        @Test
        @DisplayName("Monster with fractional CR gets level 1")
        void monsterFractionalCR() {
            Monster monster = Monster.builder()
                    .id(UUID.randomUUID())
                    .name("Imp")
                    .challengeRating("1/4")
                    .build();
            EncounterParticipant monsterCaster = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.MONSTER)
                    .displayName("Imp")
                    .hpMax(10)
                    .hpCurrent(10)
                    .armourClass(13)
                    .isAlive(true)
                    .monster(monster)
                    .build();
            encounter.getParticipants().add(monsterCaster);

            stubSpell("Fire Bolt", FIRE_BOLT_TEMPLATE);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, monsterCaster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("1d10"));
            }
        }
    }

    // ================================================================
    // Upcast scaling
    // ================================================================

    @Nested
    @DisplayName("Upcast scaling")
    class UpcastScaling {

        @Test
        @DisplayName("Spell cast at base level uses base dice")
        void baseLevel() {
            String template = """
                {"spellName":"Guiding Bolt","spellLevel":1,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"4d6","damageType":"radiant",
                   "upcastScaling":{"additionalDicePerLevel":"1d6"}}],
                 "conditionsInflicted":null}""";
            stubSpell("Guiding Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("4d6"))
                        .thenReturn(new DiceRoller.RollResult(14, 0, 14, 4, 6));

                engine.resolveSpell(encounter, caster, "Guiding Bolt", 1,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("4d6"));
            }
        }

        @Test
        @DisplayName("Spell upcast by 1 level adds additional dice")
        void upcastBy1() {
            String template = """
                {"spellName":"Guiding Bolt","spellLevel":1,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"4d6","damageType":"radiant",
                   "upcastScaling":{"additionalDicePerLevel":"1d6"}}],
                 "conditionsInflicted":null}""";
            stubSpell("Guiding Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("5d6"))
                        .thenReturn(new DiceRoller.RollResult(18, 0, 18, 5, 6));

                engine.resolveSpell(encounter, caster, "Guiding Bolt", 2,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("5d6"));
            }
        }

        @Test
        @DisplayName("Spell upcast by 3 levels adds 3x additional dice")
        void upcastBy3() {
            String template = """
                {"spellName":"Guiding Bolt","spellLevel":1,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"4d6","damageType":"radiant",
                   "upcastScaling":{"additionalDicePerLevel":"1d6"}}],
                 "conditionsInflicted":null}""";
            stubSpell("Guiding Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("7d6"))
                        .thenReturn(new DiceRoller.RollResult(24, 0, 24, 7, 6));

                engine.resolveSpell(encounter, caster, "Guiding Bolt", 4,
                        List.of(target.getId()), 5, 13, null);

                diceMock.verify(() -> DiceRoller.roll("7d6"));
            }
        }
    }

    // ================================================================
    // SPELL_ATTACK delivery method
    // ================================================================

    @Nested
    @DisplayName("SPELL_ATTACK delivery")
    class SpellAttackDelivery {

        private final String FIRE_BOLT = """
            {"spellName":"Fire Bolt","spellLevel":0,"deliveryMethod":"SPELL_ATTACK",
             "requiresManualResolution":false,"concentration":false,
             "components":{"verbal":true,"somatic":true},
             "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
             "effects":[{"effectType":"DAMAGE","damageDice":"1d10","damageType":"fire"}],
             "conditionsInflicted":null}""";

        @Test
        @DisplayName("Roll + bonus >= AC is a hit")
        void normalHit() {
            stubSpell("Fire Bolt", FIRE_BOLT);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(12, 10);

                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                assertTrue(result.resolved());
                assertEquals(1, result.targetResults().size());
                assertEquals("hit", result.targetResults().get(0).attackOutcome());
                assertEquals(7, result.targetResults().get(0).damage());
                assertEquals(7, result.totalDamage());
            }
        }

        @Test
        @DisplayName("Roll + bonus < AC is a miss")
        void normalMiss() {
            stubSpell("Fire Bolt", FIRE_BOLT);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(5, 3);

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                assertTrue(result.resolved());
                assertEquals("miss", result.targetResults().get(0).attackOutcome());
                assertEquals(0, result.targetResults().get(0).damage());
                assertEquals(0, result.totalDamage());
            }
        }

        @Test
        @DisplayName("Natural 20 is always a critical hit with doubled dice")
        void nat20CriticalHit() {
            stubSpell("Fire Bolt", FIRE_BOLT);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(20, 10);

                diceMock.when(() -> DiceRoller.rollCritical("1d10"))
                        .thenReturn(new DiceRoller.RollResult(14, 0, 14, 2, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, null);

                assertEquals("critical", result.targetResults().get(0).attackOutcome());
                assertEquals(14, result.targetResults().get(0).damage());
            }
        }

        @Test
        @DisplayName("Natural 1 always misses regardless of total")
        void nat1AlwaysMisses() {
            stubSpell("Fire Bolt", FIRE_BOLT);
            target.setArmourClass(2); // total would be 1+20=21 >> AC 2

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(1, 10);

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 20, 13, null);

                assertEquals("miss", result.targetResults().get(0).attackOutcome());
                assertEquals(0, result.targetResults().get(0).damage());
                assertEquals(1, result.targetResults().get(0).rollValue());
            }
        }

        @Test
        @DisplayName("Advantage uses higher of two rolls")
        void advantageUsesHigherRoll() {
            stubSpell("Fire Bolt", FIRE_BOLT);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(5, 15);

                diceMock.when(() -> DiceRoller.roll("1d10"))
                        .thenReturn(new DiceRoller.RollResult(7, 0, 7, 1, 10));

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, true);

                assertEquals("hit", result.targetResults().get(0).attackOutcome());
                assertEquals(15, result.targetResults().get(0).rollValue());
            }
        }

        @Test
        @DisplayName("Disadvantage uses lower of two rolls")
        void disadvantageUsesLowerRoll() {
            stubSpell("Fire Bolt", FIRE_BOLT);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(15, 5);

                var result = engine.resolveSpell(encounter, caster, "Fire Bolt", 0,
                        List.of(target.getId()), 5, 13, false);

                assertEquals("miss", result.targetResults().get(0).attackOutcome());
                assertEquals(5, result.targetResults().get(0).rollValue());
            }
        }
    }

    // ================================================================
    // SAVING_THROW delivery method
    // ================================================================

    @Nested
    @DisplayName("SAVING_THROW delivery")
    class SavingThrowDelivery {

        private final String FIREBALL = """
            {"spellName":"Fireball","spellLevel":3,"deliveryMethod":"SAVING_THROW",
             "saveAbility":"dexterity","requiresManualResolution":false,"concentration":false,
             "components":{"verbal":true,"somatic":true,"material":true},
             "targetType":"MULTI_TARGET","halfOnSave":true,
             "effects":[{"effectType":"DAMAGE","damageDice":"8d6","damageType":"fire",
               "upcastScaling":{"additionalDicePerLevel":"1d6"}}],
             "conditionsInflicted":null}""";

        @Test
        @DisplayName("Target fails save — full damage applied")
        void targetFailsSave() {
            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Goblin").dexterity(14).build();
            target.setMonster(goblinMonster);
            stubSpell("Fireball", FIREBALL);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(3);

                diceMock.when(() -> DiceRoller.roll("8d6"))
                        .thenReturn(new DiceRoller.RollResult(28, 0, 28, 8, 6));

                var result = engine.resolveSpell(encounter, caster, "Fireball", 3,
                        List.of(target.getId()), 5, 15, null);

                assertTrue(result.resolved());
                assertFalse(result.targetResults().get(0).savedSuccessfully());
                assertEquals("failed_save", result.targetResults().get(0).attackOutcome());
                assertEquals(28, result.targetResults().get(0).damage());
            }
        }

        @Test
        @DisplayName("Target saves with halfOnSave — half damage")
        void targetSavesHalfDamage() {
            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Goblin").dexterity(14).build();
            target.setMonster(goblinMonster);
            stubSpell("Fireball", FIREBALL);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18);

                diceMock.when(() -> DiceRoller.roll("8d6"))
                        .thenReturn(new DiceRoller.RollResult(28, 0, 28, 8, 6));

                var result = engine.resolveSpell(encounter, caster, "Fireball", 3,
                        List.of(target.getId()), 5, 15, null);

                assertTrue(result.targetResults().get(0).savedSuccessfully());
                assertEquals("saved", result.targetResults().get(0).attackOutcome());
                assertEquals(14, result.targetResults().get(0).damage());
            }
        }

        @Test
        @DisplayName("Target saves without halfOnSave — zero damage")
        void targetSavesZeroDamage() {
            String template = """
                {"spellName":"Hold Person","spellLevel":2,"deliveryMethod":"SAVING_THROW",
                 "saveAbility":"wisdom","requiresManualResolution":false,"concentration":true,
                 "durationRounds":10,
                 "components":{"verbal":true,"somatic":true,"material":true},
                 "targetType":"SINGLE_TARGET","halfOnSave":false,
                 "effects":[{"effectType":"CONDITION","damageDice":null}],
                 "conditionsInflicted":["paralyzed"]}""";

            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Goblin").wisdom(8).build();
            target.setMonster(goblinMonster);
            stubSpell("Hold Person", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18);

                var result = engine.resolveSpell(encounter, caster, "Hold Person", 2,
                        List.of(target.getId()), 5, 15, null);

                assertTrue(result.targetResults().get(0).savedSuccessfully());
                assertEquals(0, result.targetResults().get(0).damage());
                assertTrue(result.targetResults().get(0).conditionsApplied().isEmpty());
            }
        }

        @Test
        @DisplayName("Failed save applies conditions from conditionsInflicted")
        void failedSaveAppliesConditions() {
            String template = """
                {"spellName":"Hold Person","spellLevel":2,"deliveryMethod":"SAVING_THROW",
                 "saveAbility":"wisdom","requiresManualResolution":false,"concentration":true,
                 "durationRounds":10,
                 "components":{"verbal":true,"somatic":true,"material":true},
                 "targetType":"SINGLE_TARGET","halfOnSave":false,
                 "effects":[{"effectType":"CONDITION","damageDice":null}],
                 "conditionsInflicted":["paralyzed"]}""";

            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Goblin").wisdom(8).build();
            target.setMonster(goblinMonster);
            stubSpell("Hold Person", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(2);

                var result = engine.resolveSpell(encounter, caster, "Hold Person", 2,
                        List.of(target.getId()), 5, 15, null);

                assertFalse(result.targetResults().get(0).savedSuccessfully());
                assertEquals(List.of("paralyzed"), result.targetResults().get(0).conditionsApplied());
                assertTrue(result.concentrationSet());
                assertEquals("Hold Person", result.concentrationSpellName());
            }
        }
    }

    // ================================================================
    // AUTO_HIT delivery method
    // ================================================================

    @Nested
    @DisplayName("AUTO_HIT delivery")
    class AutoHitDelivery {

        @Test
        @DisplayName("Auto-hit applies damage without any roll")
        void autoHitDamage() {
            String template = """
                {"spellName":"Magic Missile","spellLevel":1,"deliveryMethod":"AUTO_HIT",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":3,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"3d4+3","damageType":"force"}],
                 "conditionsInflicted":null}""";
            stubSpell("Magic Missile", template);

            try (MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {
                diceMock.when(() -> DiceRoller.roll("3d4+3"))
                        .thenReturn(new DiceRoller.RollResult(8, 3, 11, 3, 4));

                var result = engine.resolveSpell(encounter, caster, "Magic Missile", 1,
                        List.of(target.getId()), 5, 13, null);

                assertTrue(result.resolved());
                assertEquals("hit", result.targetResults().get(0).attackOutcome());
                assertEquals(11, result.targetResults().get(0).damage());
                assertNull(result.targetResults().get(0).rollValue());
                assertNull(result.targetResults().get(0).rollTotal());
            }
        }

        @Test
        @DisplayName("Auto-hit with no damage dice deals 0 damage")
        void autoHitNoDamage() {
            String template = """
                {"spellName":"Light","spellLevel":0,"deliveryMethod":"AUTO_HIT",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":false,"somatic":false,"material":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"UTILITY"}],
                 "conditionsInflicted":null}""";
            stubSpell("Light", template);

            var result = engine.resolveSpell(encounter, caster, "Light", 0,
                    List.of(target.getId()), 5, 13, null);

            assertTrue(result.resolved());
            assertEquals(0, result.targetResults().get(0).damage());
        }
    }

    // ================================================================
    // SELF delivery method
    // ================================================================

    @Nested
    @DisplayName("SELF delivery")
    class SelfDelivery {

        @Test
        @DisplayName("Self-healing spell heals the caster")
        void selfHealing() {
            String template = """
                {"spellName":"Cure Wounds","spellLevel":1,"deliveryMethod":"SELF",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SELF","halfOnSave":false,
                 "effects":[],
                 "healing":{"healingDice":"1d8+3",
                   "upcastScaling":{"additionalDicePerLevel":"1d8"}},
                 "conditionsInflicted":null}""";
            stubSpell("Cure Wounds", template);

            try (MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {
                diceMock.when(() -> DiceRoller.roll("1d8+3"))
                        .thenReturn(new DiceRoller.RollResult(5, 3, 8, 1, 8));

                var result = engine.resolveSpell(encounter, caster, "Cure Wounds", 1,
                        List.of(), 5, 13, null);

                assertTrue(result.resolved());
                assertEquals(8, result.totalHealing());
                assertEquals(0, result.totalDamage());
                assertEquals(1, result.targetResults().size());
                assertEquals(caster.getId(), result.targetResults().get(0).targetId());
                assertEquals(8, result.targetResults().get(0).healing());
                assertEquals("self", result.targetResults().get(0).attackOutcome());
            }
        }

        @Test
        @DisplayName("Self buff with conditions but no healing")
        void selfBuffNoHealing() {
            String template = """
                {"spellName":"Shield of Faith","spellLevel":1,"deliveryMethod":"SELF",
                 "requiresManualResolution":false,"concentration":true,"durationRounds":100,
                 "components":{"verbal":true,"somatic":true,"material":true},
                 "targetType":"SELF","halfOnSave":false,
                 "effects":[],
                 "conditionsInflicted":["shielded"]}""";
            stubSpell("Shield of Faith", template);

            var result = engine.resolveSpell(encounter, caster, "Shield of Faith", 1,
                    List.of(), 5, 13, null);

            assertTrue(result.resolved());
            assertEquals(0, result.totalHealing());
            assertTrue(result.concentrationSet());
            assertEquals("Shield of Faith", result.concentrationSpellName());
            assertEquals(100, result.durationRounds());
            assertEquals(List.of("shielded"), result.conditionsInflicted());
        }
    }

    // ================================================================
    // Multi-target
    // ================================================================

    @Nested
    @DisplayName("Multi-target resolution")
    class MultiTarget {

        @Test
        @DisplayName("Multiple targets each resolved independently")
        void multipleTargetsResolvedIndependently() {
            EncounterParticipant target2 = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.MONSTER)
                    .displayName("Orc")
                    .hpMax(15)
                    .hpCurrent(15)
                    .armourClass(13)
                    .isAlive(true)
                    .build();
            encounter.getParticipants().add(target2);

            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Goblin").dexterity(14).build();
            target.setMonster(goblinMonster);

            Monster orcMonster = Monster.builder()
                    .id(UUID.randomUUID()).name("Orc").dexterity(12).build();
            target2.setMonster(orcMonster);

            String template = """
                {"spellName":"Fireball","spellLevel":3,"deliveryMethod":"SAVING_THROW",
                 "saveAbility":"dexterity","requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true,"material":true},
                 "targetType":"MULTI_TARGET","halfOnSave":true,
                 "effects":[{"effectType":"DAMAGE","damageDice":"8d6","damageType":"fire"}],
                 "conditionsInflicted":null}""";
            stubSpell("Fireball", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 3);

                diceMock.when(() -> DiceRoller.roll("8d6"))
                        .thenReturn(new DiceRoller.RollResult(28, 0, 28, 8, 6));

                var result = engine.resolveSpell(encounter, caster, "Fireball", 3,
                        List.of(target.getId(), target2.getId()), 5, 15, null);

                assertEquals(2, result.targetResults().size());

                var firstTarget = result.targetResults().get(0);
                assertTrue(firstTarget.savedSuccessfully());
                assertEquals(14, firstTarget.damage());

                var secondTarget = result.targetResults().get(1);
                assertFalse(secondTarget.savedSuccessfully());
                assertEquals(28, secondTarget.damage());
            }
        }
    }

    // ================================================================
    // Concentration tracking
    // ================================================================

    @Nested
    @DisplayName("Concentration tracking in result")
    class ConcentrationTracking {

        @Test
        @DisplayName("Concentration spell sets concentrationSet=true with spell name")
        void concentrationSpellTracked() {
            String template = """
                {"spellName":"Haste","spellLevel":3,"deliveryMethod":"SELF",
                 "requiresManualResolution":false,"concentration":true,"durationRounds":10,
                 "components":{"verbal":true,"somatic":true,"material":true},
                 "targetType":"SELF","halfOnSave":false,
                 "effects":[],"conditionsInflicted":["hasted"]}""";
            stubSpell("Haste", template);

            var result = engine.resolveSpell(encounter, caster, "Haste", 3,
                    List.of(), 5, 13, null);

            assertTrue(result.concentrationSet());
            assertEquals("Haste", result.concentrationSpellName());
            assertEquals(10, result.durationRounds());
        }

        @Test
        @DisplayName("Non-concentration spell has concentrationSet=false")
        void nonConcentrationSpell() {
            String template = """
                {"spellName":"Magic Missile","spellLevel":1,"deliveryMethod":"AUTO_HIT",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"3d4+3","damageType":"force"}],
                 "conditionsInflicted":null}""";
            stubSpell("Magic Missile", template);

            try (MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {
                diceMock.when(() -> DiceRoller.roll("3d4+3"))
                        .thenReturn(new DiceRoller.RollResult(8, 3, 11, 3, 4));

                var result = engine.resolveSpell(encounter, caster, "Magic Missile", 1,
                        List.of(target.getId()), 5, 13, null);

                assertFalse(result.concentrationSet());
                assertNull(result.concentrationSpellName());
            }
        }
    }

    // ================================================================
    // getSaveModifier
    // ================================================================

    @Nested
    @DisplayName("getSaveModifier")
    class GetSaveModifier {

        @Test
        @DisplayName("Player character: ability mod without proficiency")
        void playerNoProficiency() {
            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID())
                    .dexterity(14) // mod +2
                    .proficiencyBonus(3)
                    .build();
            EncounterParticipant playerTarget = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.PLAYER)
                    .displayName("Fighter")
                    .hpMax(30)
                    .hpCurrent(30)
                    .armourClass(18)
                    .isAlive(true)
                    .character(pc)
                    .build();

            int mod = engine.getSaveModifier(playerTarget, "dexterity");
            assertEquals(2, mod);
        }

        @Test
        @DisplayName("Player character: ability mod + proficiency bonus")
        void playerWithProficiency() {
            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID())
                    .constitution(16) // mod +3
                    .proficiencyBonus(3)
                    .savingThrowProficiencies("[\"CON\",\"CHA\"]")
                    .build();
            EncounterParticipant playerTarget = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.PLAYER)
                    .displayName("Sorcerer")
                    .hpMax(30)
                    .hpCurrent(30)
                    .armourClass(12)
                    .isAlive(true)
                    .character(pc)
                    .build();

            int mod = engine.getSaveModifier(playerTarget, "constitution");
            assertEquals(6, mod); // +3 ability + 3 proficiency
        }

        @Test
        @DisplayName("Monster: uses ability mod when no explicit save")
        void monsterBaseAbilityMod() {
            Monster goblinMonster = Monster.builder()
                    .id(UUID.randomUUID())
                    .name("Goblin")
                    .wisdom(8) // mod -1
                    .build();
            target.setMonster(goblinMonster);

            int mod = engine.getSaveModifier(target, "wisdom");
            assertEquals(-1, mod);
        }

        @Test
        @DisplayName("Monster: uses explicit save proficiency when available")
        void monsterExplicitSave() {
            Monster dragon = Monster.builder()
                    .id(UUID.randomUUID())
                    .name("Adult Red Dragon")
                    .charisma(21) // mod +5
                    .savingThrows("{\"dex\":7,\"con\":13,\"wis\":7,\"cha\":11}")
                    .build();
            EncounterParticipant dragonParticipant = EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .encounter(encounter)
                    .participantType(ParticipantType.MONSTER)
                    .displayName("Adult Red Dragon")
                    .hpMax(256)
                    .hpCurrent(256)
                    .armourClass(19)
                    .isAlive(true)
                    .monster(dragon)
                    .build();

            int mod = engine.getSaveModifier(dragonParticipant, "charisma");
            assertEquals(11, mod);
        }

        @Test
        @DisplayName("Null save ability returns 0")
        void nullSaveAbility() {
            int mod = engine.getSaveModifier(target, null);
            assertEquals(0, mod);
        }
    }

    // ================================================================
    // Description formatting
    // ================================================================

    @Nested
    @DisplayName("Result description")
    class ResultDescription {

        @Test
        @DisplayName("Upcast description includes slot level")
        void upcastDescriptionIncludesLevel() {
            String template = """
                {"spellName":"Guiding Bolt","spellLevel":1,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"4d6","damageType":"radiant",
                   "upcastScaling":{"additionalDicePerLevel":"1d6"}}],
                 "conditionsInflicted":null}""";
            stubSpell("Guiding Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("6d6"))
                        .thenReturn(new DiceRoller.RollResult(21, 0, 21, 6, 6));

                var result = engine.resolveSpell(encounter, caster, "Guiding Bolt", 3,
                        List.of(target.getId()), 5, 13, null);

                assertTrue(result.description().contains("at level 3"));
            }
        }

        @Test
        @DisplayName("Base-level cast does not include 'at level' in description")
        void baseLevelNoUpcastDescription() {
            String template = """
                {"spellName":"Guiding Bolt","spellLevel":1,"deliveryMethod":"SPELL_ATTACK",
                 "requiresManualResolution":false,"concentration":false,
                 "components":{"verbal":true,"somatic":true},
                 "targetType":"SINGLE_TARGET","targetCount":1,"halfOnSave":false,
                 "effects":[{"effectType":"DAMAGE","damageDice":"4d6","damageType":"radiant"}],
                 "conditionsInflicted":null}""";
            stubSpell("Guiding Bolt", template);

            try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
                 MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

                ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
                tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
                when(mockRandom.nextInt(1, 21)).thenReturn(18, 10);

                diceMock.when(() -> DiceRoller.roll("4d6"))
                        .thenReturn(new DiceRoller.RollResult(14, 0, 14, 4, 6));

                var result = engine.resolveSpell(encounter, caster, "Guiding Bolt", 1,
                        List.of(target.getId()), 5, 13, null);

                assertFalse(result.description().contains("at level"));
            }
        }
    }
}
