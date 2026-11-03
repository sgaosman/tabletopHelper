package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelinkFeatIdsJobTest {

    private static final UUID STALE_ID = UUID.randomUUID();

    @Mock CharacterRepository characterRepository;
    @Mock FeatRepository featRepository;

    private RelinkFeatIdsJob job() {
        return new RelinkFeatIdsJob(characterRepository, featRepository);
    }

    private static List<LevelHistoryEntry> history(String featName) {
        return List.of(new LevelHistoryEntry(4, null, null, 0, 0, null, new LevelChoices(
                new AbilityScoreImprovement("feat", null, featName, STALE_ID, null, null))));
    }

    private static PlayerCharacter character(List<LevelHistoryEntry> levelHistory) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name("Elga").levelHistory(levelHistory).build();
    }

    private static UUID featId(PlayerCharacter pc) {
        return pc.getLevelHistory().getFirst().choices().asi().featId();
    }

    @Test
    @DisplayName("Dry run reports the stale feat ID and writes nothing")
    void dryRunWritesNothing() {
        Feat gwm = Feat.builder().id(UUID.randomUUID()).name("Great Weapon Master").source("PHB").build();
        PlayerCharacter pc = character(history("Great Weapon Master"));
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(List.of(gwm));
        when(characterRepository.findAll()).thenReturn(List.of(pc));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("Great Weapon Master");
        assertThat(featId(pc)).isEqualTo(STALE_ID);
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Apply points the record at the current feat ID")
    void applyRelinks() {
        UUID gwmId = UUID.randomUUID();
        Feat gwm = Feat.builder().id(gwmId).name("Great Weapon Master").source("PHB").build();
        PlayerCharacter pc = character(history("Great Weapon Master"));
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(List.of(gwm));
        when(characterRepository.findAll()).thenReturn(List.of(pc));

        job().run(true);

        assertThat(featId(pc)).isEqualTo(gwmId);
        assertThat(pc.getLevelHistory().getFirst().choices().asi().featName()).isEqualTo("Great Weapon Master");
        verify(characterRepository).save(pc);
    }

    @Test
    @DisplayName("A feat name with no match is reported and left unchanged")
    void unmatchedNameWarns() {
        PlayerCharacter pc = character(history("Homebrew Feat"));
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(characterRepository.findAll()).thenReturn(List.of(pc));

        MaintenanceReport report = job().run(true);

        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).singleElement().asString().contains("Homebrew Feat");
        verify(characterRepository, never()).save(any());
    }
}
