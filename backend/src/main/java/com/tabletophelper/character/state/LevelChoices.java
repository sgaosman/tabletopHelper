package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The choices a level history entry records (D168): the Ability Score Improvement, or the feat taken instead, and the
 * skills given Expertise (D167), and since R6 workstream F the rest of the Pending Choices made for the level (D159):
 * its subclass, Fighting Style, cantrips and spells, and the feats a race or background gave at creation. Levelling
 * down reverses them. A choice not made is left out.
 *
 * @param expertise     the skills this level gave Expertise, less any that already had it
 * @param subclass      the subclass chosen at this level, the class's subclass level
 * @param fightingStyle the Fighting Style chosen, whose feature is among the level's {@code featuresGained}
 * @param cantrips      the cantrips the class learned at this level
 * @param spells        the spells the class learned at this level, or a wizard copied into its spellbook, and the one
 *                      a known caster replaced
 * @param feats         the feats taken at this level outside an Ability Score Improvement: a race's or background's,
 *                      at creation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelChoices(
        AbilityScoreImprovement asi,
        List<String> expertise,
        SubclassChosen subclass,
        String fightingStyle,
        List<String> cantrips,
        SpellsLearned spells,
        List<FeatTaken> feats
) {

    /** No choices, as each level starts. */
    public static final LevelChoices NONE = new LevelChoices(null, null, null, null, null, null, null);

    public LevelChoices withAsi(AbilityScoreImprovement newAsi) {
        return new LevelChoices(newAsi, expertise, subclass, fightingStyle, cantrips, spells, feats);
    }

    public LevelChoices withExpertise(List<String> newExpertise) {
        return new LevelChoices(asi, newExpertise, subclass, fightingStyle, cantrips, spells, feats);
    }

    public LevelChoices withSubclass(SubclassChosen newSubclass) {
        return new LevelChoices(asi, expertise, newSubclass, fightingStyle, cantrips, spells, feats);
    }

    public LevelChoices withFightingStyle(String newFightingStyle) {
        return new LevelChoices(asi, expertise, subclass, newFightingStyle, cantrips, spells, feats);
    }

    public LevelChoices withCantrips(List<String> newCantrips) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, newCantrips, spells, feats);
    }

    public LevelChoices withSpells(SpellsLearned newSpells) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, newSpells, feats);
    }

    public LevelChoices withFeats(List<FeatTaken> newFeats) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, newFeats);
    }
}
