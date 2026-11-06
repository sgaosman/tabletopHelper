package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The recompute-characters job (D167): it compares each character's stored Derived Stats with what CharacterService
 * works out, reports each one that would change with each stat before and after, and writes only with apply.
 */
@ExtendWith(MockitoExtension.class)
class RecomputeCharactersJobTest {

    @Mock CharacterRepository characterRepository;
    @Mock CharacterService characterService;

    private static final ResourcePoolEntry RAGE = new ResourcePoolEntry("class:barbarian-rage", "Rage", "CLASS", null,
            3, "3", 2, "longRest", null, null, null, null, Map.of());

    private RecomputeCharactersJob job() {
        return new RecomputeCharactersJob(characterRepository, characterService);
    }

    // A Barbarian 6 as stored: 65 hit points, +2 initiative, Rage only
    private static PlayerCharacter barbarian(String name) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name(name).characterClass("Barbarian").level(6)
                .isActive(false).hpMax(65).hpCurrent(65).proficiencyBonus(3).initiativeBonus(2).speed(30)
                .hitDice(Map.of("Barbarian", new HitDicePool(12, 6, 6))).resourcePools(List.of(RAGE))
                .build();
    }

    // What the rules give it: the stored stats unless changed
    private static DerivedStats derived(int hpMax, int initiative, List<ResourcePoolEntry> pools, List<String> warnings) {
        return new DerivedStats(3, hpMax, initiative, 30, Map.of(), new AbilityScoreMaximums(20, 20, 20, 20, 20, 20),
                null, Map.of("Barbarian", new HitDicePool(12, 6, 6)), List.of(), null, null, null, pools, warnings);
    }

    @Test
    @DisplayName("A dry run reports each stat that would change, before and after, and writes nothing")
    void dryRunReportsChanges() {
        PlayerCharacter wild = barbarian("TestBarb");
        PlayerCharacter settled = barbarian("asdasd");
        when(characterRepository.findAll()).thenReturn(List.of(wild, settled));
        ResourcePoolEntry innate = ResourcePoolEntry.innate("Faerie Fire", 1, "RACE", "Fairy");
        when(characterService.derivedStats(wild)).thenReturn(derived(65, 3, List.of(RAGE, innate), List.of()));
        when(characterService.derivedStats(settled)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.changes()).singleElement().asString()
                .startsWith("TestBarb (" + wild.getId() + ", level 6 Barbarian, deleted)")
                .contains("initiative: +2 → +3")
                .contains("pool innate:faerie-fire: none → 1/1 longRest")
                .doesNotContain("hit points");
        assertThat(report.render()).contains("Examined 2 characters; 1 would change.");
        verify(characterService, never()).recalculate(any());
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("A higher hit point maximum is reported with current hit points raised by as much")
    void hitPointsBeforeAndAfter() {
        PlayerCharacter hurt = barbarian("Grog");
        hurt.setHpCurrent(40);
        when(characterRepository.findAll()).thenReturn(List.of(hurt));
        when(characterService.derivedStats(hurt)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("hit points: 40/65 → 46/71");
    }

    @Test
    @DisplayName("A dead character's dry run shows it staying at 0 hit points when its maximum rises, as the real run does")
    void theDeadStayAtZero() {
        PlayerCharacter fallen = barbarian("Grog");
        fallen.setHpCurrent(0);
        fallen.setDead(true);
        when(characterRepository.findAll()).thenReturn(List.of(fallen));
        when(characterService.derivedStats(fallen)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("hit points: 0/65 → 0/71");
    }

    @Test
    @DisplayName("A character whose derivation fails in any way is skipped with a warning naming the failure, and the others are still reported")
    void anyFailureSkipsOnlyThatCharacter() {
        PlayerCharacter broken = barbarian("Broken");
        PlayerCharacter hurt = barbarian("Grog");
        hurt.setHpCurrent(40);
        when(characterRepository.findAll()).thenReturn(List.of(broken, hurt));
        when(characterService.derivedStats(broken)).thenThrow(new IllegalStateException("No class data"));
        when(characterService.derivedStats(hurt)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(true);

        assertThat(report.warnings()).containsExactly("Broken (" + broken.getId()
                + ", level 6 Barbarian, deleted): skipped (IllegalStateException: No class data)");
        assertThat(report.changes()).singleElement().asString().contains("hit points: 40/65 → 46/71");
        verify(characterService, never()).recalculate(broken);
        verify(characterService).recalculate(hurt);
    }

    @Test
    @DisplayName("What the sheet has beyond the rules is a warning, and a sheet that can't be worked out is skipped")
    void warnings() {
        PlayerCharacter cleric = barbarian("TestPeace");
        PlayerCharacter broken = barbarian("Broken");
        when(characterRepository.findAll()).thenReturn(List.of(cleric, broken));
        when(characterService.derivedStats(cleric)).thenReturn(derived(65, 2, List.of(RAGE),
                List.of("knows 5 Cleric cantrips, but a level 9 Cleric knows 4")));
        when(characterService.derivedStats(broken)).thenThrow(new ConflictException("Broken has no level history"));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).containsExactly(
                "Broken (" + broken.getId() + ", level 6 Barbarian, deleted): skipped (Broken has no level history)",
                "TestPeace (" + cleric.getId() + ", level 6 Barbarian, deleted): knows 5 Cleric cantrips, but a level 9 "
                        + "Cleric knows 4; left unchanged");
    }

    @Test
    @DisplayName("Apply works the changed characters out again and saves them, and leaves the rest alone")
    void applyWritesChangedCharacters() {
        PlayerCharacter wild = barbarian("TestBarb");
        PlayerCharacter settled = barbarian("asdasd");
        wild.setSpellSlots(Map.of("1", new SpellSlot(2, 0)));
        when(characterRepository.findAll()).thenReturn(List.of(wild, settled));
        when(characterService.derivedStats(wild)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));
        when(characterService.derivedStats(settled)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(true);

        assertThat(report.applied()).isTrue();
        assertThat(report.changes()).singleElement().asString().contains("slots 1: 2/2 → none");
        verify(characterService).recalculate(wild);
        verify(characterRepository).save(wild);
        verify(characterService, never()).recalculate(settled);
    }
}
