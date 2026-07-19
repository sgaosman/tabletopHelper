package com.tabletophelper.character.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class EligibleClassResponse {
    private UUID classId;
    private String className;
    private int currentClassLevel;
    private boolean isCurrentClass;
    private boolean meetsPrerequisites;
    private String prerequisiteDescription;
}
