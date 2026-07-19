package com.tabletophelper.character.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ApplyChoicesRequest {
    private AsiChoice asi;
    private UUID subclassId;

    @Data
    public static class AsiChoice {
        private String type;
        private List<AbilityIncrease> increases;
        private String featName;
        private String featAbility;
    }

    @Data
    public static class AbilityIncrease {
        private String ability;
        private int bonus;
    }
}
