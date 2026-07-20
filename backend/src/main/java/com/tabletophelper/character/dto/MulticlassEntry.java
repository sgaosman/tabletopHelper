package com.tabletophelper.character.dto;

public record MulticlassEntry(
    String classId,
    String className,
    String subclassId,
    String subclassName,
    int level
) {}
