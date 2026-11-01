package com.tabletophelper.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
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

    @Mock CharacterRepository characterRepository;
    @Mock FeatRepository featRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RelinkFeatIdsJob job() {
        return new RelinkFeatIdsJob(characterRepository, featRepository, objectMapper);
    }

    private static String history(String featName) {
        return "[{\"characterLevel\":4,\"choices\":{\"asi\":{\"type\":\"feat\",\"featName\":\""
                + featName + "\",\"featId\":\"stale-id\"}}}]";
    }

    private static PlayerCharacter character(String levelHistory) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name("Elga").levelHistory(levelHistory).build();
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
        assertThat(pc.getLevelHistory()).contains("stale-id");
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

        assertThat(pc.getLevelHistory()).contains(gwmId.toString()).doesNotContain("stale-id");
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
