package com.tabletophelper.content;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads the game content at startup, before any other startup runner (D169). {@code content.mode} decides what it
 * does: {@code apply}, the default, runs the loaders whose content changed; {@code preview} only logs which would
 * run and why; {@code off} does nothing. A maintenance start ({@code --maintenance.job}) is always {@code off}, so a
 * job sees the content as it stands: {@code preview-content} runs the loaders itself, in a transaction it rolls back.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ContentStartup implements CommandLineRunner {

    private final ContentLoading contentLoading;
    private final String mode;
    private final String maintenanceJob;

    public ContentStartup(ContentLoading contentLoading, @Value("${content.mode:apply}") String mode,
                          @Value("${maintenance.job:}") String maintenanceJob) {
        this.contentLoading = contentLoading;
        this.mode = mode;
        this.maintenanceJob = maintenanceJob;
    }

    /** The mode a start runs in: the one configured, or {@code off} for a maintenance start. */
    static String effectiveMode(String mode, String maintenanceJob) {
        if (!"apply".equals(mode) && !"preview".equals(mode) && !"off".equals(mode)) {
            throw new IllegalArgumentException("content.mode is apply, preview or off, not '" + mode + "'");
        }
        return maintenanceJob != null && !maintenanceJob.isBlank() ? "off" : mode;
    }

    @Override
    public void run(String... args) {
        String effective = effectiveMode(mode, maintenanceJob);
        switch (effective) {
            case "apply" -> contentLoading.load();
            case "preview" -> contentLoading.plan().forEach(planned -> log.info("Content (preview): {} {}",
                    planned.loader(), planned.runs() ? "would run, because " + planned.reason() : "is unchanged"));
            default -> log.info("Content loading is off{}", "off".equals(mode)
                    ? "" : " for the maintenance job " + maintenanceJob);
        }
    }
}
