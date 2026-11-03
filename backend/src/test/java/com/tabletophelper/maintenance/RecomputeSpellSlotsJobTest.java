package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.SpellSlotCalculator;
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

@ExtendWith(MockitoExtension.class)
class RecomputeSpellSlotsJobTest {

    @Mock CharacterRepository characterRepository;
    @Mock CharacterService characterService;

    private RecomputeSpellSlotsJob job() {
        return new RecomputeSpellSlotsJob(characterRepository, characterService);
    }

    private static PlayerCharacter character(String name, Map<String, SpellSlot> spellSlots) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name(name).spellSlots(spellSlots).build();
    }

    @Test
    @DisplayName("Dry run reports the Paladin 3's missing slot and writes nothing")
    void dryRunWritesNothing() throws Exception {
        PlayerCharacter paladin = character("Aria", Map.of("1", new SpellSlot(2, 1)));
        PlayerCharacter wizard = character("Bram",
                Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 1), "3", new SpellSlot(2, 0)));
        when(characterRepository.findAll()).thenReturn(List.of(paladin, wizard));
        when(characterService.spellSlotEntries(paladin)).thenReturn(Map.of("Paladin", new SpellSlotCalculator.ClassEntry(3, "half")));
        when(characterService.spellSlotEntries(wizard)).thenReturn(Map.of("Wizard", new SpellSlotCalculator.ClassEntry(5, "full")));

        MaintenanceReport report = job().run(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.changes()).singleElement().asString().contains("Aria").contains("level 1 2→3");
        assertThat(report.render()).contains("Examined 2 characters; 1 would change.")
                .contains("Run again with --maintenance.apply=true");
        assertThat(paladin.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 1)));
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Apply gives the Paladin 3 three slots, keeps the used count and leaves the Wizard alone")
    void applyKeepsUsedCounts() throws Exception {
        PlayerCharacter paladin = character("Aria", Map.of("1", new SpellSlot(2, 1)));
        PlayerCharacter wizard = character("Bram",
                Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 1), "3", new SpellSlot(2, 0)));
        when(characterRepository.findAll()).thenReturn(List.of(paladin, wizard));
        when(characterService.spellSlotEntries(paladin)).thenReturn(Map.of("Paladin", new SpellSlotCalculator.ClassEntry(3, "half")));
        when(characterService.spellSlotEntries(wizard)).thenReturn(Map.of("Wizard", new SpellSlotCalculator.ClassEntry(5, "full")));

        job().run(true);

        SpellSlot slot = paladin.getSpellSlots().get("1");
        assertThat(slot.max()).isEqualTo(3);
        assertThat(slot.used()).isEqualTo(1);
        verify(characterRepository).save(paladin);
        verify(characterRepository, never()).save(wizard);
    }

    @Test
    @DisplayName("Used counts are capped when a stored maximum was too high")
    void usedCappedAtNewTotal() throws Exception {
        PlayerCharacter paladin = character("Cass", Map.of("1", new SpellSlot(4, 4)));
        when(characterRepository.findAll()).thenReturn(List.of(paladin));
        when(characterService.spellSlotEntries(paladin)).thenReturn(Map.of("Paladin", new SpellSlotCalculator.ClassEntry(2, "half")));

        job().run(true);

        SpellSlot slot = paladin.getSpellSlots().get("1");
        assertThat(slot.max()).isEqualTo(2);
        assertThat(slot.used()).isEqualTo(2);
    }

    @Test
    @DisplayName("A character with slots but no casting class entries is reported and left unchanged")
    void noEntriesWarns() throws Exception {
        PlayerCharacter legacy = character("Dorn", Map.of("1", new SpellSlot(2, 0)));
        when(characterRepository.findAll()).thenReturn(List.of(legacy));
        when(characterService.spellSlotEntries(legacy)).thenReturn(Map.of());

        MaintenanceReport report = job().run(true);

        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).singleElement().asString().contains("Dorn");
        verify(characterRepository, never()).save(any());
    }
}
