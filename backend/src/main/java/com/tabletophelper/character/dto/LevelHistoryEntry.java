package com.tabletophelper.character.dto;

import java.util.List;
import java.util.Map;

public record LevelHistoryEntry(
    int characterLevel,
    String classId,
    String className,
    int classLevel,
    int hpGained,
    List<FeatureRecord> featuresGained,
    Map<String, Object> choices
) {
    public record FeatureRecord(String name, String description, String source) {}
}
