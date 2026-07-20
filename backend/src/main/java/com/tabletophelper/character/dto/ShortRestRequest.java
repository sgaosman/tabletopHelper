package com.tabletophelper.character.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ShortRestRequest {
    private Map<String, Integer> hitDiceToSpend;
}
