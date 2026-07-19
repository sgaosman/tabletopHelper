package com.tabletophelper.character.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class LevelUpRequest {
    private UUID classId;
}
