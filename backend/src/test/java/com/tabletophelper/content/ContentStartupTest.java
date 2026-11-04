package com.tabletophelper.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentStartupTest {

    private final ContentLoading loading = mock(ContentLoading.class);

    @Test
    @DisplayName("A normal start applies the content by default")
    void normalStartApplies() {
        new ContentStartup(loading, "apply", "").run();

        verify(loading).load();
    }

    @Test
    @DisplayName("Every maintenance start loads no content, whatever content.mode says, preview-content included (D169)")
    void maintenanceStartsAreOff() {
        for (String job : List.of("recompute-characters", "preview-migration", "content-snapshot", "preview-content")) {
            assertThat(ContentStartup.effectiveMode("apply", job)).isEqualTo("off");
            assertThat(ContentStartup.effectiveMode("preview", job)).isEqualTo("off");
        }

        new ContentStartup(loading, "apply", "preview-content").run();

        verifyNoInteractions(loading);
    }

    @Test
    @DisplayName("content.mode=preview lists the loaders that would run and loads nothing; off does nothing")
    void previewOnlyPlans() {
        when(loading.plan()).thenReturn(List.of(new ContentLoading.Planned("spells", "no content version stored")));

        new ContentStartup(loading, "preview", "").run();
        new ContentStartup(loading, "off", "").run();

        verify(loading).plan();
        verify(loading, never()).load();
    }

    @Test
    @DisplayName("A content.mode other than apply, preview or off stops the start")
    void unknownModeStops() {
        assertThatThrownBy(() -> new ContentStartup(loading, "aply", "").run())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apply, preview or off");
    }
}
