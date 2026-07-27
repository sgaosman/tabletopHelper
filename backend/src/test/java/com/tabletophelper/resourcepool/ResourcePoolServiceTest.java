package com.tabletophelper.resourcepool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ResourcePoolServiceTest {

    @Mock
    private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Mock
    private MonsterPoolTriggerRepository monsterPoolTriggerRepository;

    private ResourcePoolService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ResourcePoolService(classFeaturePoolRepository,
                monsterPoolTriggerRepository, objectMapper);
    }

    // ── Pool reset: full reset ────────────────────────────────────

    @Test
    @DisplayName("shortRest resets pools with resetOn=shortRest and longRest")
    void shortRestResetsShortAndLongRestPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("ki", 5, 1, "shortRest", null),
                entry("rage", 2, 0, "longRest", null),
                entry("channelDivinity", 1, 0, "shortRest", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of());

        assertEquals(5, findPool(reset, "ki").currentUses(), "Ki should reset to full");
        assertEquals(2, findPool(reset, "rage").currentUses(), "Rage (longRest) should also reset on short");
        assertEquals(1, findPool(reset, "channelDivinity").currentUses(), "CD should reset to full");
    }

    @Test
    @DisplayName("shortRest does NOT reset pools with resetOn=turn")
    void shortRestDoesNotResetTurnPools() {
        List<ResourcePoolEntry> pools = List.of(
                entry("legendaryActions", 3, 0, "turn", null));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of());

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

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", Map.of());

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

        List<ResourcePoolEntry> reset = service.resetPools(pools, "turn", Map.of());

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

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", Map.of());

        assertEquals(4, findPool(reset, "hitDice").currentUses(),
                "floor(8/2) = 4 hit dice recovered (not reset to 8)");
    }

    @Test
    @DisplayName("resetAmount: constant '1' recovers exactly 1")
    void partialResetConstantOne() {
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("psionicEnergy", 4, 0, "shortRest", "1"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of());

        assertEquals(1, findPool(reset, "psionicEnergy").currentUses(),
                "Should recover exactly 1 use per short rest");
    }

    @Test
    @DisplayName("resetAmount: ceil(level/2) with level=5 → ceil(2.5) = 3")
    void partialResetCeilLevelHalved() {
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("arcaneRecovery", 10, 0, "shortRest", "ceil(level/2)"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "shortRest", Map.of("level", 5));

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
            List<ResourcePoolEntry> result = service.resetPools(poolsCopy, "turn", Map.of());
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

        List<ResourcePoolEntry> reset = service.resetPools(pools, "turn", Map.of());

        // currentUses should stay at 1 since pool wasn't empty
        assertEquals(1, findPool(reset, "breathWeapon").currentUses());
    }

    // ── Pool parsing ──────────────────────────────────────────────

    @Test
    @DisplayName("parsePools returns empty list for null")
    void parseNullReturnsEmpty() {
        assertTrue(service.parsePools(null).isEmpty());
    }

    @Test
    @DisplayName("parsePools returns empty list for blank")
    void parseBlankReturnsEmpty() {
        assertTrue(service.parsePools("   ").isEmpty());
    }

    @Test
    @DisplayName("parsePools deserializes valid JSON")
    void parseValidJson() throws Exception {
        String json = objectMapper.writeValueAsString(List.of(
                entry("ki", 5, 3, "shortRest", null)));

        List<ResourcePoolEntry> result = service.parsePools(json);

        assertEquals(1, result.size());
        assertEquals("ki", result.get(0).poolId());
        assertEquals(5, result.get(0).maxUses());
        assertEquals(3, result.get(0).currentUses());
    }

    // ── Context building ─────────────────────────────────────────

    @Test
    @DisplayName("abilityMod calculates correct modifier: charismaModifier=2 → recovers 2")
    void abilityModCalculation() {
        Map<String, Integer> ctx = Map.of("charismaModifier", 2);
        List<ResourcePoolEntry> pools = List.of(
                entryWithResetAmount("inspiration", 5, 0, "longRest", "charismaModifier"));

        List<ResourcePoolEntry> reset = service.resetPools(pools, "longRest", ctx);
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

    private ResourcePoolEntry findPool(List<ResourcePoolEntry> pools, String poolId) {
        return pools.stream()
                .filter(p -> p.poolId().equals(poolId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Pool not found: " + poolId));
    }
}
