package com.tabletophelper.maintenance;

import java.util.List;

public record MaintenanceReport(String job, boolean applied, int examined, List<String> changes, List<String> warnings) {

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ").append(job).append(applied ? " (APPLIED)" : " (DRY RUN: nothing written)").append(" ===\n");
        sb.append("Examined ").append(examined).append(" characters; ")
                .append(changes.size()).append(applied ? " changed" : " would change").append(".\n");
        changes.forEach(c -> sb.append("  - ").append(c).append('\n'));
        if (!warnings.isEmpty()) {
            sb.append("Warnings:\n");
            warnings.forEach(w -> sb.append("  ! ").append(w).append('\n'));
        }
        if (!applied && !changes.isEmpty()) {
            sb.append("Run again with --maintenance.apply=true to write these changes.\n");
        }
        return sb.toString();
    }
}
