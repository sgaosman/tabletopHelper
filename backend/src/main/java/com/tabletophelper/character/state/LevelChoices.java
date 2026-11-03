package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The choices a level history entry records (D168). So far only the Ability Score Improvement, or the feat taken
 * instead, is recorded; a choice not made is left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelChoices(AbilityScoreImprovement asi) {

    /** No choices, as each level starts. */
    public static final LevelChoices NONE = new LevelChoices(null);

    public LevelChoices withAsi(AbilityScoreImprovement newAsi) {
        return new LevelChoices(newAsi);
    }
}
