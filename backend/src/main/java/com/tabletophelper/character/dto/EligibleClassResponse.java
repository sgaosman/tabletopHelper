package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@Builder
public class EligibleClassResponse {

    @Schema(requiredMode = REQUIRED)
    private UUID classId;

    @Schema(requiredMode = REQUIRED)
    private String className;

    @Schema(requiredMode = REQUIRED)
    private int currentClassLevel;

    // Named so that the field and its isCurrentClass() getter are the same JSON property, currentClass,
    // and the property keeps this annotation
    @Schema(requiredMode = REQUIRED)
    private boolean currentClass;

    @Schema(requiredMode = REQUIRED)
    private boolean meetsPrerequisites;

    @Schema(requiredMode = REQUIRED)
    private String prerequisiteDescription;
}
