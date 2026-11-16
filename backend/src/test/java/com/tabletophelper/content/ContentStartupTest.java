package com.tabletophelper.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

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
        new ContentStartup(loading, "apply", "").start();

        verify(loading).load();
    }

    @Test
    @DisplayName("Every maintenance start loads no content, whatever content.mode says, preview-content included (D169)")
    void maintenanceStartsAreOff() {
        for (String job : List.of("recompute-characters", "preview-migration", "content-snapshot", "preview-content")) {
            assertThat(ContentStartup.effectiveMode("apply", job)).isEqualTo("off");
            assertThat(ContentStartup.effectiveMode("preview", job)).isEqualTo("off");
        }

        new ContentStartup(loading, "apply", "preview-content").start();

        verifyNoInteractions(loading);
    }

    @Test
    @DisplayName("content.mode=preview lists the loaders that would run and loads nothing; off does nothing")
    void previewOnlyPlans() {
        when(loading.plan()).thenReturn(List.of(new ContentLoading.Planned("spells", "no content version stored")));

        new ContentStartup(loading, "preview", "").start();
        new ContentStartup(loading, "off", "").start();

        verify(loading).plan();
        verify(loading, never()).load();
    }

    @Test
    @DisplayName("A content.mode other than apply, preview or off stops the start")
    void unknownModeStops() {
        assertThatThrownBy(() -> new ContentStartup(loading, "aply", "").start())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apply, preview or off");
    }

    @Test
    @DisplayName("It starts in a lifecycle phase before the web server's, so the content loads before the server accepts a request (D233)")
    void phaseIsBeforeTheWebServer() {
        var startup = new ContentStartup(loading, "apply", "");

        assertThat(startup.isAutoStartup()).isTrue();
        assertThat(startup.getPhase()).isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
                .isLessThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    @DisplayName("A context stopped and started again doesn't load the content again (D233)")
    void loadsOnce() {
        var startup = new ContentStartup(loading, "apply", "");

        startup.start();
        startup.stop();
        assertThat(startup.isRunning()).isFalse();
        startup.start();

        assertThat(startup.isRunning()).isTrue();
        verify(loading).load();
    }

    @Test
    @DisplayName("A load that fails with content.fail-on-error stops the start, as it did from the runner (D155, D233)")
    void failedLoadStopsTheStart() {
        when(loading.load()).thenThrow(new ContentLoading.ContentLoadFailedException("spells failed", null));
        var startup = new ContentStartup(loading, "apply", "");

        assertThatThrownBy(startup::start).isInstanceOf(ContentLoading.ContentLoadFailedException.class);
        assertThat(startup.isRunning()).isFalse();
    }
}
