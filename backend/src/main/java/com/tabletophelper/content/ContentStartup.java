package com.tabletophelper.content;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Loads the game content at startup, before the web server accepts a request (D169, D233). {@code content.mode}
 * decides what it does: {@code apply}, the default, runs the loaders whose content changed; {@code preview} only logs
 * which would run and why; {@code off} does nothing. A maintenance start ({@code --maintenance.job}) is always
 * {@code off}, so a job sees the content as it stands: {@code preview-content} runs the loaders itself, in a
 * transaction it rolls back.
 * <p>
 * Why a {@link SmartLifecycle} (D233). It was a {@code CommandLineRunner} until D233, and Spring Boot calls the runners
 * after the context has refreshed, when the web server is already accepting requests: for the seconds the loaders
 * took, the API served the old reference data (a monster added to an encounter got its old action templates). The
 * embedded server only opens its connectors in {@code WebServerStartStopLifecycle.start()}, a {@code SmartLifecycle}
 * at {@link WebServerApplicationContext#START_STOP_LIFECYCLE_PHASE}, and the context starts its lifecycle beans one
 * phase at a time, lowest first, each {@code start()} returning before the next phase begins. So this bean, at
 * {@link #PHASE}, below that phase and below {@code GRACEFUL_SHUTDOWN_PHASE}, finishes loading before the connectors
 * open, and so before {@code /actuator/health} can answer. The alternatives fall short: a
 * {@code ContextRefreshedEvent} is published after the lifecycle beans have started, so after the server; a
 * {@code SmartInitializingSingleton} runs early enough, but beside other beans' own {@code afterSingletonsInstantiated}
 * (the caching and event listener infrastructure finish setting up there), in no order we choose.
 * <p>
 * Every singleton, and so Flyway's migration, is ready before the lifecycle starts. A loader's failure behaves as it
 * did: {@link ContentLoading} logs it and skips the loaders that depend on it, or with {@code content.fail-on-error}
 * throws, and an exception from {@link #start()} fails the context's refresh, so startup fails as a runner's exception
 * did. The runners, {@code MaintenanceRunner} among them, still run after the refresh, so after the content.
 */
@Component
@Slf4j
public class ContentStartup implements SmartLifecycle {

    /** Below the web server's phases, so the content is loaded before it accepts a request (D233). */
    public static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;

    private final ContentLoading contentLoading;
    private final String mode;
    private final String maintenanceJob;
    private volatile boolean loaded;
    private volatile boolean running;

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

    /** Loads the content once; a context stopped and started again doesn't load it again. */
    @Override
    public void start() {
        if (!loaded) {
            load();
            loaded = true;
        }
        running = true;
    }

    private void load() {
        String effective = effectiveMode(mode, maintenanceJob);
        switch (effective) {
            case "apply" -> contentLoading.load();
            case "preview" -> contentLoading.plan().forEach(planned -> log.info("Content (preview): {} {}",
                    planned.loader(), planned.runs() ? "would run, because " + planned.reason() : "is unchanged"));
            default -> log.info("Content loading is off{}", "off".equals(mode)
                    ? "" : " for the maintenance job " + maintenanceJob);
        }
    }

    // Nothing to undo: the content stays loaded
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
