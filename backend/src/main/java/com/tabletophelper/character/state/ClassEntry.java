package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One of a character's classes and its level, in {@code player_characters.multiclass_entries} (D168). Every
 * character with a class has an entry for it, a single-class character too, in the order the classes were taken.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassEntry(
        @Schema(requiredMode = REQUIRED) UUID classId,
        String className,
        UUID subclassId,
        String subclassName,
        @Schema(requiredMode = REQUIRED) int level
) {

    public ClassEntry withLevel(int newLevel) {
        return new ClassEntry(classId, className, subclassId, subclassName, newLevel);
    }

    public ClassEntry withSubclass(UUID newSubclassId, String newSubclassName) {
        return new ClassEntry(classId, className, newSubclassId, newSubclassName, level);
    }
}
