package com.tabletophelper.maintenance;

/**
 * A one-off data correction run by {@link MaintenanceRunner}. Every job reports what it would
 * change, and writes only when {@code apply} is true (D150: dry run before any data rewrite).
 */
public interface MaintenanceJob {

    /** The value passed as {@code --maintenance.job=<name>}. */
    String name();

    MaintenanceReport run(boolean apply);
}
