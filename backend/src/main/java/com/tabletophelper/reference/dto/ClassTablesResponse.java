package com.tabletophelper.reference.dto;

import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.character.ClassTables;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The class tables the server's character rules use (D167, D169), from {@code data/content/class-tables.json}, so the
 * creation wizard and the sheet show the same numbers. Every column has an entry for each level from 0 to 20.
 *
 * @param spellSlots       each caster type's slots (full, half, artificer, third, pact), by class level, each row by
 *                         spell level from 1st; a Pact Magic row holds its count at the slot's level
 * @param proficiencyBonus the proficiency bonus by character level, 0 at level 0
 * @param classes          each class's table, by name
 * @param subclasses       the subclasses with tables of their own, such as the Eldritch Knight, by name
 */
public record ClassTablesResponse(
        @Schema(requiredMode = REQUIRED) Map<String, List<List<Integer>>> spellSlots,
        @Schema(requiredMode = REQUIRED) List<Integer> proficiencyBonus,
        @Schema(requiredMode = REQUIRED) Map<String, ClassTableResponse> classes,
        @Schema(requiredMode = REQUIRED) Map<String, SubclassTableResponse> subclasses) {

    /**
     * One class's table.
     *
     * @param expertise    the proficiencies that gain Expertise, by class level
     * @param spellcasting null for a class without Spellcasting
     * @param spellbook    null for a class without a spellbook
     */
    public record ClassTableResponse(
            @Schema(requiredMode = REQUIRED) int subclassLevel,
            @Schema(requiredMode = REQUIRED) List<Integer> asiLevels,
            @Schema(requiredMode = REQUIRED) Map<String, Integer> expertise,
            @Schema(requiredMode = REQUIRED) List<Integer> fightingStyleLevels,
            @Schema(requiredMode = REQUIRED) List<String> fightingStyles,
            SpellcastingResponse spellcasting,
            SpellbookResponse spellbook) {}

    /** A subclass's own table: its Fighting Style levels and the Spellcasting it gives its class, if any. */
    public record SubclassTableResponse(
            @Schema(requiredMode = REQUIRED) String className,
            @Schema(requiredMode = REQUIRED) List<Integer> fightingStyleLevels,
            SpellcastingResponse spellcasting) {}

    /**
     * A Spellcasting feature's numbers.
     *
     * @param cantripsKnown empty for a class without cantrips
     * @param spellsKnown   empty for a class that prepares its spells
     * @param preparing     NONE, LEVEL (the modifier plus the class level) or HALF_LEVEL (plus half, rounded down)
     */
    public record SpellcastingResponse(
            @Schema(requiredMode = REQUIRED) String casterType,
            @Schema(requiredMode = REQUIRED) List<Integer> cantripsKnown,
            @Schema(requiredMode = REQUIRED) List<Integer> spellsKnown,
            @Schema(requiredMode = REQUIRED) String preparing) {}

    /** The spells a spellbook gains for free at the class's 1st level and at each level after. */
    public record SpellbookResponse(
            @Schema(requiredMode = REQUIRED) int firstLevel,
            @Schema(requiredMode = REQUIRED) int laterLevels) {}

    public static ClassTablesResponse of(ClassTables tables) {
        Map<String, ClassTableResponse> classes = new TreeMap<>();
        tables.classes().forEach((name, table) -> {
            Map<String, Integer> expertise = new LinkedHashMap<>();
            new TreeMap<>(table.expertise()).forEach((level, count) -> expertise.put(String.valueOf(level), count));
            classes.put(name, new ClassTableResponse(table.subclassLevel(), table.asiLevels(), expertise,
                    table.fightingStyleLevels(), table.fightingStyles(), spellcasting(table.spellcasting()),
                    table.spellbook() != null
                            ? new SpellbookResponse(table.spellbook().firstLevel(), table.spellbook().laterLevels())
                            : null));
        });
        Map<String, SubclassTableResponse> subclasses = new TreeMap<>();
        tables.subclasses().forEach((name, table) -> subclasses.put(name, new SubclassTableResponse(
                table.className(), table.fightingStyleLevels(), spellcasting(table.spellcasting()))));
        Map<String, List<List<Integer>>> slots = new TreeMap<>(tables.spellSlots());
        List<Integer> proficiencyBonus = IntStream.rangeClosed(0, ClassTables.MAX_CLASS_LEVEL)
                .map(level -> level == 0 ? 0 : ProficiencyBonus.forLevel(level))
                .boxed().toList();
        return new ClassTablesResponse(slots, proficiencyBonus, classes, subclasses);
    }

    private static SpellcastingResponse spellcasting(ClassTables.Spellcasting spellcasting) {
        return spellcasting == null ? null : new SpellcastingResponse(spellcasting.casterType(),
                spellcasting.cantripsKnown(), spellcasting.spellsKnown(), spellcasting.preparing().name());
    }
}
