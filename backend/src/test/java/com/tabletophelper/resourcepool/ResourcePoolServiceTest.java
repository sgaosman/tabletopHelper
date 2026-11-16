package com.tabletophelper.resourcepool;

import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.PoolLevelTable.Row;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceFeaturePool;
import com.tabletophelper.reference.RaceFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.rules.ExpressionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourcePoolServiceTest {

    @Mock
    private ClassFeaturePoolRepository classFeaturePoolRepository;

    @Mock
    private RaceFeaturePoolRepository raceFeaturePoolRepository;

    @Mock
    private com.tabletophelper.reference.ItemRepository itemRepository;

    private ResourcePoolService service;

    @BeforeEach
    void setUp() {
        service = new ResourcePoolService(classFeaturePoolRepository, raceFeaturePoolRepository,
                JacksonDefaults.mapper(), itemRepository);
    }

    // ── Pool reset: full reset ────────────────────────────────────

    @Test
    @DisplayName("shortRest resets only pools with resetOn=shortRest (D127)")
    void shortRestResetsShortAndLongRestPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("ki", 5, 1, "shortRest", null),
                entry("rage", 2, 0, "longRest", null),
                entry("channelDivinity", 1, 0, "shortRest", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of()).pools();

        assertEquals(5, findPool(reset, "ki").currentUses(), "Ki should reset to full");
        assertEquals(0, findPool(reset, "rage").currentUses(), "Rage (longRest) should NOT reset on short rest per D127");
        assertEquals(1, findPool(reset, "channelDivinity").currentUses(), "CD should reset to full");
    }

    @Test
    @DisplayName("shortRest does NOT reset pools with resetOn=turn")
    void shortRestDoesNotResetTurnPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("legendaryActions", 3, 0, "turn", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of()).pools();

        assertEquals(0, findPool(reset, "legendaryActions").currentUses(),
                "Turn pools should not reset on short rest");
    }

    @Test
    @DisplayName("longRest resets ALL pools regardless of resetOn")
    void longRestResetsAllPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("ki", 5, 0, "shortRest", null),
                entry("rage", 2, 0, "longRest", null),
                entry("legendaryActions", 3, 0, "turn", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", Map.of()).pools();

        assertEquals(5, findPool(reset, "ki").currentUses());
        assertEquals(2, findPool(reset, "rage").currentUses());
        assertEquals(3, findPool(reset, "legendaryActions").currentUses());
    }

    @Test
    @DisplayName("turn resets only pools with resetOn=turn")
    void turnResetsOnlyTurnPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("ki", 5, 1, "shortRest", null),
                entry("legendaryActions", 3, 1, "turn", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "turn", Map.of()).pools();

        assertEquals(1, findPool(reset, "ki").currentUses(), "Ki should NOT reset on turn");
        assertEquals(3, findPool(reset, "legendaryActions").currentUses(),
                "Legendary actions should reset on turn");
    }

    // ── Pool reset: partial recovery ──────────────────────────────

    @Test
    @DisplayName("resetAmount expression: floor(maxUses/2) recovers half (rounded down)")
    void partialResetFloorHalf() {
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("hitDice", 8, 3, "longRest", "floor(maxUses/2)"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", Map.of()).pools();

        assertEquals(4, findPool(reset, "hitDice").currentUses(),
                "floor(8/2) = 4 hit dice recovered (not reset to 8)");
    }

    @Test
    @DisplayName("resetAmount: constant '1' recovers exactly 1")
    void partialResetConstantOne() {
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("psionicEnergy", 4, 0, "shortRest", "1"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of()).pools();

        assertEquals(1, findPool(reset, "psionicEnergy").currentUses(),
                "Should recover exactly 1 use per short rest");
    }

    @Test
    @DisplayName("resetAmount: ceil(level/2) with level=5 → ceil(2.5) = 3")
    void partialResetCeilLevelHalved() {
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("arcaneRecovery", 10, 0, "shortRest", "ceil(level/2)"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of("level", 5)).pools();

        assertEquals(3, findPool(reset, "arcaneRecovery").currentUses(), "ceil(5/2) = 3");
    }

    // ── Pool reset: recharge checks ───────────────────────────────

    @Test
    @DisplayName("Pools with resetCheck trigger probabilistic recharge on turn when empty")
    void rechargeCheckOnTurnWhenEmpty() {
        // A depleted breath weapon with recharge 5-6
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetCheck("breathWeapon", 1, 0, "shortRest", "1d6>=5"));

        // Run many iterations to verify it sometimes recharges
        int recharges = 0;
        for (int i = 0; i < 500; i++) {
            List<ResourcePoolEntry> poolsCopy = List.of(
                    entryWithResetCheck("breathWeapon", 1, 0, "shortRest", "1d6>=5"));
            List<ResourcePoolEntry> result = service.resetPools(poolsCopy, "turn", Map.of()).pools();
            if (findPool(result, "breathWeapon").currentUses() > 0) recharges++;
        }

        assertTrue(recharges >= 100 && recharges <= 400,
                "Recharge 5-6 should succeed ~33% of the time, got " + recharges + "/500");
    }

    @Test
    @DisplayName("Recharge check only fires when pool is at 0")
    void rechargeOnlyWhenEmpty() {
        // A pool with uses remaining should not recharge
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetCheck("breathWeapon", 1, 1, "shortRest", "1d6>=5"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "turn", Map.of()).pools();

        // currentUses should stay at 1 since pool wasn't empty
        assertEquals(1, findPool(reset, "breathWeapon").currentUses());
    }

    // ── Sheet pools (typed since R6, D168) ───────────────────────

    @Test
    @DisplayName("reconciledPools adds an innate pool for each levelled spell the character can cast now with uses of its own, keeping its uses (D165)")
    void syncAddsInnatePools() {
        ResourcePoolEntry spent = ResourcePoolEntry.innate("Hellish Rebuke", 1, "RACE", "Tiefling").withCurrentUses(0);
        PlayerCharacter tiefling = PlayerCharacter.builder().level(3)
                .spellsKnown(List.of(
                        new KnownSpell(null, "Thaumaturgy", 0, "race:Tiefling", null, null, true, null, null),
                        new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1, 3),
                        new KnownSpell(null, "Darkness", 2, "race:Tiefling", null, null, null, 1, 5),
                        new KnownSpell(null, "Hellish Rebuke", 1, "class:Warlock", null, null, null, null, null),
                        new KnownSpell(null, "Misty Step", 2, "feat:Fey Touched", null, null, null, 1, null)))
                .resourcePools(List.of(spent, entry("orphan", 1, 1, "longRest", null)))
                .build();

        assertEquals(List.of(spent, ResourcePoolEntry.innate("Misty Step", 1, "FEAT", "Fey Touched")),
                reconcile(tiefling));
    }

    // ── Race pools (M24.5b, D208) ────────────────────────────────

    private static final Race DRAGONBORN = Race.builder().id(UUID.randomUUID()).name("Dragonborn").source("PHB").build();
    private static final Race HALF_ORC = Race.builder().id(UUID.randomUUID()).name("Half-Orc").source("PHB").build();

    private static RaceFeaturePool racePool(Race race, String poolId, String name, String resetOn, String effect) {
        return RaceFeaturePool.builder().race(race).minLevel(1).poolDefinition(ResourcePoolDefinition.builder()
                .poolId(poolId).displayName(name).sourceType("RACE").maxUsesFormula("1").resetOn(resetOn)
                .spendActionType("ACTION").metadata("{\"effect\": \"" + effect + "\"}").build()).build();
    }

    private void racePools(Race race, int level, RaceFeaturePool... pools) {
        when(raceFeaturePoolRepository.findByRaceIdAndMinLevelLessThanEqual(race.getId(), level))
                .thenReturn(List.of(pools));
    }

    @Test
    @DisplayName("reconciledPools adds the pools the character's race row grants at its total level, as RACE pools named after the race, with their metadata (D208)")
    void syncAddsRacePools() {
        racePools(DRAGONBORN, 6, racePool(DRAGONBORN, "race:breath-weapon", "Breath Weapon", "shortRest",
                "breath-weapon"));
        PlayerCharacter character = PlayerCharacter.builder().level(6).raceRef(DRAGONBORN).build();

        ResourcePoolEntry breath = findPool(reconcile(character), "race:breath-weapon");

        assertEquals("RACE", breath.sourceType());
        assertEquals("Dragonborn", breath.sourceName());
        assertEquals(1, breath.maxUses());
        assertEquals(1, breath.currentUses());
        assertEquals("shortRest", breath.resetOn());
        assertEquals("ACTION", breath.spendActionType());
        assertEquals(Map.of("effect", "breath-weapon"), breath.metadata());
    }

    @Test
    @DisplayName("A race pool the character has keeps its spent use; a new race's pools come in full and the old race's go, while class pools stay (D208)")
    void raceChangeSwapsRacePools() {
        racePools(DRAGONBORN, 3, racePool(DRAGONBORN, "race:breath-weapon", "Breath Weapon", "shortRest",
                "breath-weapon"));
        PlayerCharacter character = PlayerCharacter.builder().level(3).raceRef(DRAGONBORN)
                .multiclassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Fighter", null, null, 3)))
                .build();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(featurePool("class:fighter-second-wind", "1")));
        List<ResourcePoolEntry> first = reconcile(character);
        character.setResourcePools(first.stream()
                .map(pool -> pool.poolId().equals("race:breath-weapon") ? pool.withCurrentUses(0) : pool).toList());

        assertEquals(0, findPool(reconcile(character), "race:breath-weapon").currentUses(),
                "the spent breath stays spent");

        racePools(HALF_ORC, 3, racePool(HALF_ORC, "race:relentless-endurance", "Relentless Endurance", "longRest",
                "relentless-endurance"));
        character.setRaceRef(HALF_ORC);
        List<ResourcePoolEntry> swapped = reconcile(character);

        assertEquals(List.of("class:fighter-second-wind", "race:relentless-endurance"),
                swapped.stream().map(ResourcePoolEntry::poolId).toList());
        assertEquals(1, findPool(swapped, "race:relentless-endurance").currentUses());
        assertEquals("Half-Orc", findPool(swapped, "race:relentless-endurance").sourceName());
    }

    @Test
    @DisplayName("A race pool from a later level, as an Aasimar's transformation at 3rd, is asked for by the character's total level (D208)")
    void racePoolsAskedByTotalLevel() {
        PlayerCharacter character = PlayerCharacter.builder().level(2).raceRef(DRAGONBORN).build();

        assertTrue(reconcile(character).isEmpty());
        org.mockito.Mockito.verify(raceFeaturePoolRepository).findByRaceIdAndMinLevelLessThanEqual(DRAGONBORN.getId(), 2);
    }

    @Test
    @DisplayName("reconciledPools adds a Mystic Arcanum pool of one long-rest use for each arcanum spell, by its level, keeping a spent use, and drops one whose spell is gone (PHB p.108)")
    void syncAddsMysticArcanumPools() {
        ResourcePoolEntry spentSixth = ResourcePoolEntry.mysticArcanum(6, "Mass Suggestion").withCurrentUses(0);
        ResourcePoolEntry eighth = ResourcePoolEntry.mysticArcanum(8, "Feeblemind");
        PlayerCharacter warlock = PlayerCharacter.builder().level(13)
                .spellsKnown(List.of(
                        new KnownSpell(null, "Hold Monster", 5, "class:Warlock", null, null, null, null, null),
                        new KnownSpell(null, "Mass Suggestion", 6, "arcanum:Warlock", null, null, null, null, null),
                        new KnownSpell(null, "Plane Shift", 7, "arcanum:Warlock", null, null, null, null, null)))
                .resourcePools(List.of(spentSixth, eighth))
                .build();

        List<ResourcePoolEntry> pools = reconcile(warlock);

        assertEquals(List.of(spentSixth, ResourcePoolEntry.mysticArcanum(7, "Plane Shift")), pools);
        assertEquals("Mystic Arcanum (7th)", pools.get(1).displayName());
        assertEquals("longRest", pools.get(1).resetOn());
        assertEquals(1, pools.get(1).maxUses());
        assertEquals(Map.of("spell", "Plane Shift", "spellLevel", 7), pools.get(1).metadata());
    }

    @Test
    @DisplayName("reconciledPools adds a feat pool for each feat with limited uses the level history records, keeping its uses (D158)")
    void syncAddsFeatPools() {
        FeatResourceEntry luck = new FeatResourceEntry("Lucky", "Luck Points", 3, 3, "longRest");
        FeatResourceEntry grit = new FeatResourceEntry("Second Chance", "Second Chances", 1, 1, "shortRest");
        ResourcePoolEntry spentLuck = ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest").withCurrentUses(1);
        PlayerCharacter lucky = PlayerCharacter.builder().level(8)
                .levelHistory(List.of(
                        new LevelHistoryEntry(1, null, "Fighter", 1, 12, 10, null, LevelChoices.NONE, null, null),
                        featLevel(4, luck),
                        featLevel(6, grit),
                        // A feat taken twice has one pool
                        featLevel(8, luck)))
                .resourcePools(List.of(spentLuck, entry("feat:alert", 1, 1, "longRest", null)))
                .build();

        List<ResourcePoolEntry> pools = reconcile(lucky);

        assertEquals(List.of(spentLuck, ResourcePoolEntry.feat("Second Chance", "Second Chances", 1, "shortRest")),
                pools);
        assertEquals("feat:lucky", pools.getFirst().poolId());
        assertEquals("FEAT", pools.getFirst().sourceType());
        assertEquals("Lucky", pools.getFirst().sourceName());
    }

    @Test
    @DisplayName("A feat the DM granted has a pool as one a level took does, until the grant is removed (D158)")
    void grantedFeatsHavePools() {
        FeatResourceEntry luck = new FeatResourceEntry("Lucky", "Luck Points", 3, 3, "longRest");
        AppliedFeatEffects lucky = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, luck, null, "Lucky");
        DmAdjustment grant = new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.FEAT, "Lucky", null, null,
                UUID.randomUUID(), lucky, "Blessing of Tymora", UUID.randomUUID(), Instant.now(), null, null);
        PlayerCharacter blessed = PlayerCharacter.builder().level(3)
                .levelHistory(List.of(new LevelHistoryEntry(1, null, "Fighter", 1, 12, 10, null, LevelChoices.NONE,
                        null, null)))
                .resourcePools(List.of(ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest").withCurrentUses(2)))
                .dmAdjustments(List.of(grant))
                .build();

        assertEquals(List.of(ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest").withCurrentUses(2)),
                reconcile(blessed));

        blessed.setDmAdjustments(List.of(grant.removed(UUID.randomUUID(), Instant.now())));
        assertEquals(List.of(), reconcile(blessed));
    }

    @Test
    @DisplayName("The ability modifiers pool formulas read include the DM's adjustments to the scores (D158)")
    void contextModifiersIncludeDmAdjustments() {
        PlayerCharacter bard = PlayerCharacter.builder().charisma(15)
                .dmAdjustments(List.of(new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "CHA", 2, null,
                        null, null, "Cloak of the Bard", UUID.randomUUID(), Instant.now(), null, null)))
                .build();

        assertEquals(3, service.buildCharacterContext(bard).get("charismaModifier"));
        assertEquals(0, service.buildCharacterContext(bard).get("strengthModifier"));
    }

    @Test
    @DisplayName("A feat's pool goes when the level that took the feat goes")
    void featPoolGoesWithItsLevel() {
        PlayerCharacter unlucky = PlayerCharacter.builder().level(3)
                .levelHistory(List.of(new LevelHistoryEntry(1, null, "Fighter", 1, 12, 10, null, LevelChoices.NONE,
                        null, null)))
                .resourcePools(List.of(ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest")))
                .build();

        assertEquals(List.of(), reconcile(unlucky));
    }

    private static LevelHistoryEntry featLevel(int level, FeatResourceEntry resource) {
        AppliedFeatEffects effects = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, resource, null, resource.featName());
        return new LevelHistoryEntry(level, null, "Fighter", level, 7, 6, null, LevelChoices.NONE.withAsi(
                new AbilityScoreImprovement("feat", null, resource.featName(), UUID.randomUUID(), null, effects, null)),
                null, null);
    }

    @Test
    @DisplayName("A class pool keeps the uses it has spent through a sync, whatever its new maximum, and a new one starts full")
    void syncKeepsSpentUses() {
        UUID classId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(classId, 2))
                .thenReturn(List.of(featurePool("class:ki", "5"), featurePool("class:surge", "1"),
                        featurePool("class:wind", "2")));
        PlayerCharacter monk = PlayerCharacter.builder().level(2)
                .multiclassEntries(List.of(new ClassEntry(classId, "Monk", null, null, 2)))
                .resourcePools(List.of(entry("class:ki", 3, 1, "shortRest", null),
                        entry("class:surge", 3, 1, "shortRest", null)))
                .build();

        List<ResourcePoolEntry> pools = reconcile(monk);

        // Ki had 2 of 3 spent: 3 of 5 left; Surge had 2 spent, more than its new maximum of 1
        assertEquals(List.of(5, 1, 2), pools.stream().map(ResourcePoolEntry::maxUses).toList());
        assertEquals(List.of(3, 0, 2), pools.stream().map(ResourcePoolEntry::currentUses).toList());
    }

    @Test
    @DisplayName("A sync that fails stops the command and leaves the sheet's pools as they were, rather than carrying on (D155)")
    void syncFailureStopsTheCommand() {
        UUID classId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(classId, 1))
                .thenThrow(new IllegalStateException("The pool definitions can't be read"));
        List<ResourcePoolEntry> pools = List.of(entry("rage", 2, 1, "longRest", null));
        PlayerCharacter psion = PlayerCharacter.builder().level(1)
                .multiclassEntries(List.of(new ClassEntry(classId, "Psion", null, null, 1)))
                .resourcePools(pools)
                .build();

        assertThrows(IllegalStateException.class, () -> reconcile(psion));
        assertEquals(pools, psion.getResourcePools());
    }

    // ── Level tables (V27, D169) ─────────────────────────────────

    // Bardic Inspiration as resource-pools.json defines it
    private static ClassFeaturePool bardicInspiration() {
        return ClassFeaturePool.builder().minLevel(1).poolDefinition(ResourcePoolDefinition.builder()
                .poolId("class:bard-bardic-inspiration").displayName("Bardic Inspiration").sourceType("CLASS")
                .maxUsesFormula("max(1, charismaModifier)").resetOn("longRest").metadata("{\"die\": \"d6\"}")
                .levelTable(new PoolLevelTable("Bard", List.of(new Row(5, null, "shortRest", "d8", null),
                        new Row(10, null, "shortRest", "d10", null), new Row(15, null, "shortRest", "d12", null))))
                .build()).build();
    }

    private ResourcePoolEntry bardPool(int bardLevel, int charisma) {
        UUID bardId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(bardId,
                bardLevel)).thenReturn(List.of(bardicInspiration()));
        PlayerCharacter bard = PlayerCharacter.builder().level(bardLevel).charisma(charisma)
                .multiclassEntries(List.of(new ClassEntry(bardId, "Bard", null, null, bardLevel)))
                .build();
        return findPool(reconcile(bard), "class:bard-bardic-inspiration");
    }

    @Test
    @DisplayName("Bardic Inspiration comes back on a long rest at Bard 4, with a d6, and on a short rest from Bard 5 (Font of Inspiration), with a d8")
    void resetTypeChangesWithLevel() {
        ResourcePoolEntry fourth = bardPool(4, 16);
        assertEquals("longRest", fourth.resetOn());
        assertEquals(Map.of("die", "d6"), fourth.metadata());
        assertEquals(3, fourth.maxUses());

        ResourcePoolEntry fifth = bardPool(5, 16);
        assertEquals("shortRest", fifth.resetOn());
        assertEquals(Map.of("die", "d8"), fifth.metadata());

        assertEquals("d12", ((Map<?, ?>) bardPool(15, 16).metadata()).get("die"));
        // A minimum of once
        assertEquals(1, bardPool(1, 8).maxUses());
    }

    @Test
    @DisplayName("A short rest after Bard 5 brings Bardic Inspiration back, and before it doesn't")
    void shortRestFollowsTheRow() {
        ResourcePoolEntry fourth = bardPool(4, 16).withCurrentUses(0);
        ResourcePoolEntry fifth = bardPool(5, 16).withCurrentUses(0);

        List<ResourcePoolEntry> rested = service.resetPools(List.of(fourth, fifth), "shortRest", Map.of()).pools();

        assertEquals(List.of(0, 3), rested.stream().map(ResourcePoolEntry::currentUses).toList());
    }

    @Test
    @DisplayName("The row is picked by the named class's level, not the character's: a Paladin 12 / Bard 3 has Bard 3's d6")
    void rowFollowsTheNamedClass() {
        UUID bardId = UUID.randomUUID();
        UUID paladinId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(bardId, 3))
                .thenReturn(List.of(bardicInspiration()));
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(paladinId,
                12)).thenReturn(List.of());
        PlayerCharacter character = PlayerCharacter.builder().level(15).charisma(16)
                .multiclassEntries(List.of(new ClassEntry(paladinId, "Paladin", null, null, 12),
                        new ClassEntry(bardId, "Bard", null, null, 3)))
                .build();

        ResourcePoolEntry pool = findPool(reconcile(character), "class:bard-bardic-inspiration");

        assertEquals("longRest", pool.resetOn());
        assertEquals(Map.of("die", "d6"), pool.metadata());
    }

    @Test
    @DisplayName("Rage has the row's maximum at each level, and at Barbarian 20 is unlimited, which a spend never refuses or runs down")
    void unlimitedPool() {
        UUID barbarianId = UUID.randomUUID();
        ClassFeaturePool rage = ClassFeaturePool.builder().minLevel(1).poolDefinition(ResourcePoolDefinition.builder()
                .poolId("class:barbarian-rage").displayName("Rage").sourceType("CLASS").maxUsesFormula("2")
                .resetOn("longRest")
                .levelTable(new PoolLevelTable("Barbarian", List.of(new Row(3, "3", null, null, null),
                        new Row(17, "6", null, null, null), new Row(20, "6", null, null, true))))
                .build()).build();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(
                org.mockito.ArgumentMatchers.eq(barbarianId), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(rage));

        List<Integer> sizes = new java.util.ArrayList<>();
        for (int level : new int[]{1, 3, 16, 17}) {
            PlayerCharacter barbarian = PlayerCharacter.builder().level(level)
                    .multiclassEntries(List.of(new ClassEntry(barbarianId, "Barbarian", null, null, level))).build();
            ResourcePoolEntry pool = findPool(reconcile(barbarian), "class:barbarian-rage");
            assertFalse(pool.unlimited());
            sizes.add(pool.maxUses());
        }
        assertEquals(List.of(2, 3, 3, 6), sizes);

        PlayerCharacter twentieth = PlayerCharacter.builder().level(20)
                .multiclassEntries(List.of(new ClassEntry(barbarianId, "Barbarian", null, null, 20))).build();
        ResourcePoolEntry pool = findPool(reconcile(twentieth), "class:barbarian-rage");
        assertTrue(pool.unlimited());
        assertEquals(Map.of("unlimited", true), pool.metadata());

        EncounterParticipant participant = EncounterParticipant.builder()
                .resourcePoolsCurrent(List.of(pool.withCurrentUses(0))).build();
        assertTrue(service.spendPool(participant, "class:barbarian-rage", 1));
        assertEquals(0, findPool(participant.getResourcePoolsCurrent(), "class:barbarian-rage").currentUses());
    }

    @Test
    @DisplayName("A definition without a formula is an error, not 1 use (D169)")
    void blankFormulaIsAnError() {
        UUID classId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(classId, 1))
                .thenReturn(List.of(featurePool("class:blank", " ")));
        PlayerCharacter monk = PlayerCharacter.builder().level(1)
                .multiclassEntries(List.of(new ClassEntry(classId, "Monk", null, null, 1))).build();

        assertThrows(ExpressionException.class, () -> reconcile(monk));
    }

    @Test
    @DisplayName("A formula naming something the character's context hasn't is a 409, not 0")
    void unknownNameIsAConflict() {
        UUID classId = UUID.randomUUID();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(classId, 1))
                .thenReturn(List.of(featurePool("class:other", "wizardLevel")));
        PlayerCharacter monk = PlayerCharacter.builder().level(1)
                .multiclassEntries(List.of(new ClassEntry(classId, "Monk", null, null, 1))).build();

        assertInstanceOf(com.tabletophelper.shared.ConflictException.class,
                assertThrows(ExpressionException.class, () -> reconcile(monk)));
    }

    // ── Participant pools (typed since R5, D168) ─────────────────

    @Test
    @DisplayName("spendPool takes uses from the named pool only, and refuses a spend larger than what is left")
    void spendPoolTakesFromTheNamedPool() {
        EncounterParticipant dragon = EncounterParticipant.builder()
                .resourcePoolsCurrent(List.of(
                        entry("legendary-actions", 3, 2, "turn", null), entry("rage", 2, 2, "longRest", null)))
                .build();

        assertTrue(service.spendPool(dragon, "legendary-actions", 2));
        assertFalse(service.spendPool(dragon, "legendary-actions", 1));

        assertEquals(0, findPool(dragon.getResourcePoolsCurrent(), "legendary-actions").currentUses());
        assertEquals(2, findPool(dragon.getResourcePoolsCurrent(), "rage").currentUses());
        assertFalse(service.spendPool(dragon, "no-such-pool", 1));
    }

    @Test
    @DisplayName("A participant's Psionic Recovery regains one expended Psionic Energy die, as on the sheet, and is a "
            + "409 with none expended (D127)")
    void participantPsionicRecovery() {
        ResourcePoolEntry dice = entry("subclass:psi-warrior-psionic-energy", 4, 2, "longRest", null);
        ResourcePoolEntry recovery = new ResourcePoolEntry("subclass:psi-warrior-psionic-recovery",
                "Psionic Recovery", "CLASS", "Fighter", 1, "1", 1, "shortRest", null, null, "BONUS_ACTION", null,
                Map.of("recovers", "subclass:psi-warrior-psionic-energy"));
        EncounterParticipant psiWarrior = EncounterParticipant.builder().displayName("Kel")
                .resourcePoolsCurrent(List.of(dice, recovery)).build();

        assertTrue(service.spendPool(psiWarrior, "subclass:psi-warrior-psionic-recovery", 1));

        assertEquals(3, findPool(psiWarrior.getResourcePoolsCurrent(), "subclass:psi-warrior-psionic-energy")
                .currentUses());
        assertEquals(0, findPool(psiWarrior.getResourcePoolsCurrent(), "subclass:psi-warrior-psionic-recovery")
                .currentUses());

        EncounterParticipant full = EncounterParticipant.builder().displayName("Kel")
                .resourcePoolsCurrent(List.of(dice.withCurrentUses(4), recovery)).build();
        com.tabletophelper.shared.ConflictException refused = assertThrows(
                com.tabletophelper.shared.ConflictException.class,
                () -> service.spendPool(full, "subclass:psi-warrior-psionic-recovery", 1));
        assertEquals("subclass:psi-warrior-psionic-energy has 4 of 4 left, so Psionic Recovery has nothing to regain",
                refused.getMessage());
        assertEquals(1, findPool(full.getResourcePoolsCurrent(), "subclass:psi-warrior-psionic-recovery")
                .currentUses());
    }

    @Test
    @DisplayName("spend, the rule sheets and participants share, refuses a pool that isn't there or hasn't the uses, "
            + "and leaves an unlimited pool's uses")
    void sharedSpendRule() {
        List<ResourcePoolEntry> pools = List.of(entry("ki", 5, 1, "shortRest", null),
                new ResourcePoolEntry("rage", "Rage", "CLASS", "Barbarian", 6, null, 0, "longRest", null, null,
                        "BONUS_ACTION", null, Map.of("unlimited", true)));

        assertEquals(0, findPool(ResourcePoolService.spend(pools, "ki", 1, "Kel"), "ki").currentUses());
        assertEquals("ki has 1 of 5 uses left, not 2", assertThrows(com.tabletophelper.shared.ConflictException.class,
                () -> ResourcePoolService.spend(pools, "ki", 2, "Kel")).getMessage());
        assertEquals("Kel has no pool sorcery", assertThrows(com.tabletophelper.shared.ValidationException.class,
                () -> ResourcePoolService.spend(pools, "sorcery", 1, "Kel")).getMessage());
        assertEquals(pools, ResourcePoolService.spend(pools, "rage", 3, "Kel"));
    }

    @Test
    @DisplayName("recoverPool gives uses back up to the maximum; a participant without pools recovers nothing")
    void recoverPoolStopsAtTheMaximum() {
        EncounterParticipant monk = EncounterParticipant.builder()
                .resourcePoolsCurrent(List.of(entry("ki", 5, 4, "shortRest", null)))
                .build();

        assertTrue(service.recoverPool(monk, "ki", 3));

        assertEquals(5, findPool(monk.getResourcePoolsCurrent(), "ki").currentUses());
        assertFalse(service.recoverPool(EncounterParticipant.builder().build(), "ki", 1));
    }

    // ── Magic item charges (D210) ─────────────────────────────────

    @Test
    @DisplayName("A linked Wand of Magic Missiles gets a 7-charge MAGIC_ITEM pool, spent with an action, regaining 1d6 + 1 on a long rest (D210)")
    void wandPool() {
        com.tabletophelper.reference.Item wand = wand(false);
        PlayerCharacter character = carrying(new com.tabletophelper.character.state.InventoryItem(wand.getName(), 1,
                null, wand.getId(), null, null));
        when(itemRepository.findAllById(List.of(wand.getId()))).thenReturn(List.of(wand));

        ResourcePoolEntry pool = findPool(reconcile(character), "item:wand-of-magic-missiles");

        assertEquals("MAGIC_ITEM", pool.sourceType());
        assertEquals(7, pool.maxUses());
        assertEquals(7, pool.currentUses());
        assertEquals("longRest", pool.resetOn());
        assertEquals("currentUses + (1d6 + 1)", pool.resetAmount());
        assertEquals("ACTION", pool.spendActionType());
    }

    @Test
    @DisplayName("An item requiring attunement has its pool only while attuned; unattuning drops it, and the spent charges are kept while it stays (D210)")
    void attunedPool() {
        com.tabletophelper.reference.Item wand = wand(true);
        when(itemRepository.findAllById(List.of(wand.getId()))).thenReturn(List.of(wand));
        PlayerCharacter character = carrying(new com.tabletophelper.character.state.InventoryItem(wand.getName(), 1,
                null, wand.getId(), null, null));
        assertTrue(reconcile(character).stream().noneMatch(p -> p.poolId().startsWith("item:")));

        character.setEquipment(List.of(new com.tabletophelper.character.state.InventoryItem(wand.getName(), 1, null,
                wand.getId(), null, true)));
        character.setResourcePools(reconcile(character).stream()
                .map(p -> p.poolId().equals("item:wand-of-magic-missiles") ? p.withCurrentUses(3) : p).toList());
        assertEquals(3, findPool(reconcile(character), "item:wand-of-magic-missiles").currentUses());

        character.setEquipment(List.of());
        assertTrue(reconcile(character).stream().noneMatch(p -> p.poolId().startsWith("item:")));
    }

    @Test
    @DisplayName("A long rest gives an item's pool its dice of charges back, up to the maximum, and leaves one that never recharges (D210)")
    void itemPoolsOnALongRest() {
        ResourcePoolEntry wand = ResourcePoolEntry.magicItem("Wand of Web", 7, "dawn", "3").withCurrentUses(2);
        ResourcePoolEntry full = ResourcePoolEntry.magicItem("Staff of Fire", 10, "dawn", "6").withCurrentUses(8);
        ResourcePoolEntry never = ResourcePoolEntry.magicItem("Ring of Spell Turning", 3, null, null).withCurrentUses(1);

        List<ResourcePoolEntry> reset = service.resetPools(List.of(wand, full, never), "longRest", Map.of()).pools();

        assertEquals(5, findPool(reset, "item:wand-of-web").currentUses());
        assertEquals(10, findPool(reset, "item:staff-of-fire").currentUses());
        assertEquals(1, findPool(reset, "item:ring-of-spell-turning").currentUses());
        assertEquals("none", never.resetOn());
    }

    private static com.tabletophelper.reference.Item wand(boolean attunement) {
        return com.tabletophelper.reference.Item.builder().id(UUID.randomUUID()).name("Wand of Magic Missiles")
                .source("DMG").charges(7).recharge("dawn").rechargeAmount("1d6 + 1").requiresAttunement(attunement)
                .build();
    }

    private static PlayerCharacter carrying(com.tabletophelper.character.state.InventoryItem entry) {
        return PlayerCharacter.builder().level(3).equipment(new java.util.ArrayList<>(List.of(entry))).build();
    }

    // ── Context building ─────────────────────────────────────────

    @Test
    @DisplayName("abilityMod calculates correct modifier: charismaModifier=2 → recovers 2")
    void abilityModCalculation() {
        Map<String, Integer> ctx = Map.of("charismaModifier", 2);
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("inspiration", 5, 0, "longRest", "charismaModifier"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", ctx).pools();
        assertEquals(2, findPool(reset, "inspiration").currentUses());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ResourcePoolEntry entry(String poolId, int maxUses, int currentUses,
                                     String resetOn, String resetAmount) {
        return new ResourcePoolEntry(poolId, poolId, "CLASS", "Test",
                maxUses, null, currentUses, resetOn,
                resetAmount, null, "FREE", null, Map.of());
    }

    private ResourcePoolEntry entryWithResetAmount(String poolId, int maxUses, int currentUses,
                                                    String resetOn, String resetAmount) {
        return new ResourcePoolEntry(poolId, poolId, "CLASS", "Test",
                maxUses, null, currentUses, resetOn,
                resetAmount, null, "FREE", null, Map.of());
    }

    private ResourcePoolEntry entryWithResetCheck(String poolId, int maxUses, int currentUses,
                                                   String resetOn, String resetCheck) {
        return new ResourcePoolEntry(poolId, poolId, "MONSTER", "Test",
                maxUses, null, currentUses, resetOn,
                null, resetCheck, "ACTION", null, Map.of());
    }

    // ── Channel Divinity from two classes (PHB p.164) ─────────────

    private static final String CLERIC_CD = "class:cleric-channel-divinity";
    private static final String PALADIN_CD = "class:paladin-channel-divinity";

    // A cleric-paladin at these levels, with the sheet's pools, each class's Channel Divinity pool as the data defines it
    private PlayerCharacter clericPaladin(int clericLevel, int paladinLevel, ResourcePoolEntry... onSheet) {
        UUID clericId = UUID.randomUUID();
        UUID paladinId = UUID.randomUUID();
        ClassFeaturePool cleric = ClassFeaturePool.builder().minLevel(2).poolDefinition(ResourcePoolDefinition.builder()
                .poolId(CLERIC_CD).displayName("Channel Divinity").sourceType("CLASS").maxUsesFormula("1")
                .resetOn("shortRest").metadata("{\"effect\": \"channel-divinity\"}")
                .levelTable(new PoolLevelTable("Cleric", List.of(new Row(6, "2", null, null, null),
                        new Row(18, "3", null, null, null))))
                .build()).build();
        ClassFeaturePool paladin = ClassFeaturePool.builder().minLevel(3).poolDefinition(ResourcePoolDefinition.builder()
                .poolId(PALADIN_CD).displayName("Channel Divinity").sourceType("CLASS").maxUsesFormula("1")
                .resetOn("shortRest").metadata("{\"effect\": \"channel-divinity\"}").build()).build();
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(clericId,
                clericLevel)).thenReturn(clericLevel >= 2 ? List.of(cleric) : List.of());
        when(classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(paladinId,
                paladinLevel)).thenReturn(paladinLevel >= 3 ? List.of(paladin) : List.of());
        return PlayerCharacter.builder().level(clericLevel + paladinLevel)
                .multiclassEntries(List.of(new ClassEntry(paladinId, "Paladin", null, null, paladinLevel),
                        new ClassEntry(clericId, "Cleric", null, null, clericLevel)))
                .resourcePools(List.of(onSheet))
                .build();
    }

    private static ResourcePoolEntry channelDivinity(String poolId, int maxUses, int currentUses) {
        return new ResourcePoolEntry(poolId, "Channel Divinity", "CLASS", null, maxUses, "1", currentUses, "shortRest",
                null, null, "ACTION", null, Map.of("effect", "channel-divinity"));
    }

    @Test
    @DisplayName("A Paladin 3 / Cleric 6 has one Channel Divinity pool, the cleric's 2 uses, not the paladin's on top (PHB p.164)")
    void channelDivinityFromTwoClassesIsOnePool() {
        List<ResourcePoolEntry> pools = reconcile(clericPaladin(6, 3));

        List<ResourcePoolEntry> divinity = pools.stream().filter(p -> p.poolId().contains("channel-divinity")).toList();
        assertEquals(1, divinity.size());
        assertEquals(CLERIC_CD, divinity.getFirst().poolId());
        assertEquals(2, divinity.getFirst().maxUses());
        assertEquals(2, divinity.getFirst().currentUses());
    }

    @Test
    @DisplayName("On a tie the pool the sheet already has is kept: a Paladin 3 who took Cleric 2 keeps the paladin's single use")
    void channelDivinityTieKeepsTheSheetsPool() {
        List<ResourcePoolEntry> pools = reconcile(clericPaladin(2, 3, channelDivinity(PALADIN_CD, 1, 0)));

        assertEquals(List.of(PALADIN_CD), pools.stream().map(ResourcePoolEntry::poolId)
                .filter(id -> id.contains("channel-divinity")).toList());
        assertEquals(0, findPool(pools, PALADIN_CD).currentUses(), "the use spent stays spent");
    }

    @Test
    @DisplayName("A sheet with both pools reconciles to the one with more uses, the uses spent from either staying spent, never below 0")
    void channelDivinityExistingSheetWithBothPools() {
        List<ResourcePoolEntry> onePaladinUseSpent = reconcile(clericPaladin(6, 3,
                channelDivinity(CLERIC_CD, 2, 2), channelDivinity(PALADIN_CD, 1, 0)));
        assertEquals(List.of(CLERIC_CD), onePaladinUseSpent.stream().map(ResourcePoolEntry::poolId)
                .filter(id -> id.contains("channel-divinity")).toList());
        assertEquals(1, findPool(onePaladinUseSpent, CLERIC_CD).currentUses());

        List<ResourcePoolEntry> allSpent = reconcile(clericPaladin(6, 3,
                channelDivinity(CLERIC_CD, 2, 0), channelDivinity(PALADIN_CD, 1, 0)));
        assertEquals(0, findPool(allSpent, CLERIC_CD).currentUses());
    }

    @Test
    @DisplayName("A single-class paladin keeps its own Channel Divinity pool")
    void channelDivinitySingleClassUnchanged() {
        List<ResourcePoolEntry> pools = reconcile(clericPaladin(1, 3));

        assertEquals(1, findPool(pools, PALADIN_CD).maxUses());
    }

    private List<ResourcePoolEntry> reconcile(PlayerCharacter character) {
        return service.reconciledPools(character, service.buildCharacterContext(character));
    }

    private static ClassFeaturePool featurePool(String poolId, String formula) {
        return ClassFeaturePool.builder().minLevel(1).poolDefinition(ResourcePoolDefinition.builder()
                .poolId(poolId).displayName(poolId).sourceType("CLASS").maxUsesFormula(formula).resetOn("shortRest")
                .build()).build();
    }

    private ResourcePoolEntry findPool(List<ResourcePoolEntry> pools, String poolId) {
        return pools.stream()
                .filter(p -> p.poolId().equals(poolId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Pool not found: " + poolId));
    }
}
