package com.tabletophelper.character;

import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a character joins an encounter with (D166): the encounter copies the snapshot into its participant and keeps
 * its totals as what the participant joined with, from which Write-back works out the encounter's changes.
 */
class CombatSnapshotTest {

    private final User owner = User.builder().id(UUID.randomUUID()).username("aria").build();

    @Test
    @DisplayName("The snapshot copies the sheet, with the DM's AC adjustments, and its totals are what Write-back starts from")
    void copiesTheSheet() {
        ResourcePoolEntry rage = ResourcePoolEntry.feat("Rage", "Rage", 2, "longRest").withCurrentUses(1);
        List<ResourcePoolEntry> pools = new ArrayList<>(List.of(rage));
        PlayerCharacter aria = PlayerCharacter.builder().id(UUID.randomUUID()).name("Aria").user(owner)
                .hpMax(30).hpCurrent(24).hpTemp(5).armourClass(14).initiativeBonus(2)
                .spellSlots(Map.of("1", new SpellSlot(4, 1), "pact_1", new SpellSlot(2, 2)))
                .resourcePools(pools)
                .dmAdjustments(List.of(new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "ARMOUR_CLASS", 1,
                        null, null, null, "Ring of Protection", UUID.randomUUID(), Instant.now(), null, null)))
                .build();

        CombatSnapshot snapshot = CombatSnapshot.of(aria);

        assertThat(snapshot.ownerId()).isEqualTo(owner.getId());
        assertThat(snapshot.armourClass()).isEqualTo(15);
        assertThat(snapshot.initiativeBonus()).isEqualTo(2);
        assertThat(snapshot.dead()).isFalse();
        assertThat(snapshot.totals()).isEqualTo(new CombatTotals(24, 5, Map.of("1", 1, "pact_1", 2),
                Map.of(rage.poolId(), 1)));
        // Copies, so the participant never shares the sheet's lists
        assertThat(snapshot.resourcePools()).isEqualTo(pools).isNotSameAs(pools);
    }

    @Test
    @DisplayName("Hit points and slots' used counts are kept within their limits, and a sheet without pools gives an empty list")
    void keepsTotalsWithinLimits() {
        PlayerCharacter aria = PlayerCharacter.builder().id(UUID.randomUUID()).name("Aria").user(owner)
                .hpMax(30).hpCurrent(41).hpTemp(-2).armourClass(12)
                .spellSlots(Map.of("2", new SpellSlot(2, 5)))
                .resourcePools(null)
                .dead(true)
                .build();

        CombatSnapshot snapshot = CombatSnapshot.of(aria);

        assertThat(snapshot.totals()).isEqualTo(new CombatTotals(30, 0, Map.of("2", 2), Map.of()));
        assertThat(snapshot.spellSlots()).isEqualTo(Map.of("2", new SpellSlot(2, 2)));
        assertThat(snapshot.resourcePools()).isEmpty();
        assertThat(snapshot.spellsKnown()).isNull();
        assertThat(snapshot.dead()).isTrue();
    }

    @Test
    @DisplayName("Of two pools with one ID the first counts, and a key without a number is left out of the totals")
    void totalsTakeTheFirstPool() {
        ResourcePoolEntry first = ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest").withCurrentUses(2);
        ResourcePoolEntry second = first.withCurrentUses(0);

        CombatTotals totals = CombatTotals.of(10, 0, null, List.of(first, second));
        java.util.HashMap<String, Integer> withNull = new java.util.HashMap<>();
        withNull.put("1", null);
        withNull.put("2", 1);

        assertThat(totals.poolsCurrentUses()).isEqualTo(Map.of(first.poolId(), 2));
        assertThat(totals.slotsUsed()).isEmpty();
        assertThat(new CombatTotals(10, 0, withNull, null).slotsUsed()).isEqualTo(Map.of("2", 1));
        assertThat(new CombatTotals(10, 0, null, null).poolsCurrentUses()).isEmpty();
    }
}
