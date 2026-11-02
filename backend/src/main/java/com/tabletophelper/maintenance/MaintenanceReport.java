package com.tabletophelper.maintenance;

import java.util.List;

/**
 * What a maintenance job examined and changed, or would change.
 *
 * @param subject  what it examined, such as "characters"
 * @param nextStep what to do after a dry run that found changes
 */
public record MaintenanceReport(String job, boolean applied, String subject, int examined, List<String> changes,
                                List<String> warnings, String nextStep) {

    /** The report of a job that writes its changes when run again with --maintenance.apply=true. */
    public MaintenanceReport(String job, boolean applied, String subject, int examined, List<String> changes,
                             List<String> warnings) {
        this(job, applied, subject, examined, changes, warnings,
                "Run again with --maintenance.apply=true to write these changes.");
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ").append(job).append(applied ? " (APPLIED)" : " (DRY RUN: nothing written)").append(" ===\n");
        sb.append("Examined ").append(examined).append(' ').append(subject).append("; ")
                .append(changes.size()).append(applied ? " changed" : " would change").append(".\n");
        changes.forEach(c -> sb.append("  - ").append(c).append('\n'));
        if (!warnings.isEmpty()) {
            sb.append("Warnings:\n");
            warnings.forEach(w -> sb.append("  ! ").append(w).append('\n'));
        }
        if (!applied && !changes.isEmpty()) {
            sb.append(nextStep).append('\n');
        }
        return sb.toString();
    }
}
