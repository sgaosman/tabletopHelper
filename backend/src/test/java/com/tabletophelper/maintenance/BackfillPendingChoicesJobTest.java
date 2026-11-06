package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PendingChoiceService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Source;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.shared.ConflictException;
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

/**
 * The backfill-pending-choices job (D159): it asks PendingChoiceService for the Ability Score Improvements,
 * subclasses and Expertise each character's levels missed, reports each character that would gain any with each
 * choice, and adds them only with apply.
 */
@ExtendWith(MockitoExtension.class)
class BackfillPendingChoicesJobTest {

    @Mock CharacterRepository characterRepository;
    @Mock PendingChoiceService pendingChoiceService;

    private BackfillPendingChoicesJob job() {
        return new BackfillPendingChoicesJob(characterRepository, pendingChoiceService);
    }

    private static PlayerCharacter character(String name, String characterClass, int level, boolean active) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name(name).characterClass(characterClass).level(level)
                .isActive(active).build();
    }

    private static PendingChoice missed(Type type, String className, int classLevel, int count) {
        return new PendingChoice(UUID.randomUUID(), type, Source.LEVEL_UP, classLevel, UUID.randomUUID(), className,
                classLevel, count, null, null, null, null);
    }

    @Test
    @DisplayName("A dry run reports each character's missed choices, by type, count and level, and writes nothing")
    void dryRunReports() {
        PlayerCharacter fighter = character("TestBM", "Fighter", 9, false);
        PlayerCharacter bard = character("TestBard", "Bard", 5, false);
        PlayerCharacter settled = character("asdasd", "Barbarian", 5, true);
        when(characterRepository.findAll()).thenReturn(List.of(fighter, bard, settled));
        when(pendingChoiceService.missedChoices(fighter)).thenReturn(List.of(missed(Type.ASI, "Fighter", 6, 1),
                missed(Type.ASI, "Fighter", 8, 1)));
        when(pendingChoiceService.missedChoices(bard)).thenReturn(List.of(missed(Type.EXPERTISE, "Bard", 3, 2)));
        when(pendingChoiceService.missedChoices(settled)).thenReturn(List.of());

        MaintenanceReport report = job().run(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(3);
        assertThat(report.changes()).containsExactly(
                "TestBard (" + bard.getId() + ", level 5 Bard, deleted)\n      + EXPERTISE ×2 at Bard 3 (level 3)",
                "TestBM (" + fighter.getId() + ", level 9 Fighter, deleted)\n      + ASI at Fighter 6 (level 6)"
                        + "\n      + ASI at Fighter 8 (level 8)");
        assertThat(report.render()).contains("Examined 3 characters; 2 would change.");
        verify(pendingChoiceService, never()).addPending(any(), any());
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Applied, it adds each character's missed choices and saves only those that gained any")
    void applyAdds() {
        PlayerCharacter fighter = character("TestFighter", "Fighter", 9, false);
        PlayerCharacter settled = character("asdasd", "Barbarian", 5, true);
        List<PendingChoice> choices = List.of(missed(Type.SUBCLASS, "Fighter", 3, 1));
        when(characterRepository.findAll()).thenReturn(List.of(fighter, settled));
        when(pendingChoiceService.missedChoices(fighter)).thenReturn(choices);
        when(pendingChoiceService.missedChoices(settled)).thenReturn(List.of());

        MaintenanceReport report = job().run(true);

        assertThat(report.applied()).isTrue();
        assertThat(report.changes()).singleElement().asString().endsWith("+ SUBCLASS at Fighter 3 (level 3)");
        verify(pendingChoiceService).addPending(fighter, choices);
        verify(characterRepository).save(fighter);
        verify(characterRepository, never()).save(settled);
    }

    @Test
    @DisplayName("A character whose classes can't be found is skipped with a warning")
    void skipsUnreadableCharacters() {
        PlayerCharacter broken = character("Broken", "Fighter", 4, true);
        when(characterRepository.findAll()).thenReturn(List.of(broken));
        when(pendingChoiceService.missedChoices(broken))
                .thenThrow(new ConflictException("The class Fighter that the character has can't be found"));

        MaintenanceReport report = job().run(true);

        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).containsExactly("Broken (" + broken.getId()
                + ", level 4 Fighter): skipped (The class Fighter that the character has can't be found)");
        verify(characterRepository, never()).save(any());
    }
}
