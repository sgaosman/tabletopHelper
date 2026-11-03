package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The choices a level history entry records (D168): the Ability Score Improvement, or the feat taken instead, and the
 * skills given Expertise (D167). Levelling down reverses them. A choice not made is left out.
 *
 * @param expertise the skills this level gave Expertise, less any that already had it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelChoices(AbilityScoreImprovement asi, List<String> expertise) {

    /** No choices, as each level starts. */
    public static final LevelChoices NONE = new LevelChoices(null, null);

    public LevelChoices withAsi(AbilityScoreImprovement newAsi) {
        return new LevelChoices(newAsi, expertise);
    }

    public LevelChoices withExpertise(List<String> newExpertise) {
        return new LevelChoices(asi, newExpertise);
    }
}
