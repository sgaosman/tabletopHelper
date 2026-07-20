package com.tabletophelper.character.dto;

public record FeatResourceEntry(
    String featName,
    String name,
    int maxUses,
    int currentUses,
    String resetOn
) {}
