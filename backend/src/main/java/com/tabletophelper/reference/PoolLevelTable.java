package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a resource pool's definition changes with the level of one class (D169), stored in
 * {@code resource_pool_definitions.level_table}: Rage's uses by barbarian level, Bardic Inspiration's die and its
 * short rest from Bard 5, the superiority and Psionic Energy dice's sizes. Each row applies from its level until the
 * next one; a field a row leaves out is the definition's own, and below the first row the definition applies
 * unchanged.
 *
 * @param levelOf the class whose level picks the row, such as "Barbarian"
 * @param rows    the rows, by level
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PoolLevelTable(String levelOf, List<Row> rows) {

    /**
     * One row of the table.
     *
     * @param level     the class level it applies from
     * @param maxUses   the maximum's formula, or null for the definition's
     * @param resetOn   the rest that brings the uses back, or null for the definition's
     * @param die       the pool's die, such as "d8", or null for the definition's metadata's
     * @param unlimited whether the pool has no limit from this level, such as Rage at Barbarian 20: spending it is
     *                  never refused
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(int level, String maxUses, String resetOn, String die, Boolean unlimited) {}

    public PoolLevelTable {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    /** The row that applies at the class level: the last one at or below it, or none below the first. */
    public Optional<Row> rowAt(int classLevel) {
        return rows.stream()
                .filter(row -> row.level() <= classLevel)
                .max(Comparator.comparingInt(Row::level));
    }
}
