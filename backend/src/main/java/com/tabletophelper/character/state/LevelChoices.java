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
 * @param setsSpellcastingAbility true when this level's class stored the character's spellcasting ability (D159): the
 *                      first casting class's, at creation with no race choice, recorded on the class's 1st level, or an
 *                      Eldritch Knight's or Arcane Trickster's Intelligence, recorded on the subclass's level. Levelling
 *                      down past the level works the ability out again. Null otherwise
 * @param metamagic     the Metamagic options a sorcerer learned at this level (PHB p.101–102, M18), whose features,
 *                      named "Metamagic: X", are among the level's {@code featuresGained}
 * @param proficiencies the bonus proficiencies chosen at this level, the subclass's level, from its PROFICIENCY choices,
 *                      such as a College of Lore's three skills (PHB p.54); those the sheet lacked are among the level's
 *                      {@code proficienciesGained}, and a Knowledge Domain's skills are among its {@code expertise} too
 * @param kenseiWeapons the weapons a Way of the Kensei monk chose at this level as kensei weapons (XGE p.34), from its
 *                      PROFICIENCY choices that make them, at Monk 3, 6, 11 and 17; those it wasn't proficient with are
 *                      among the level's {@code proficienciesGained}. The character's kensei weapons are those its
 *                      levels record, so levelling down takes them away
 * @param subclassCantrips the cantrips chosen at this level, the subclass's level, from its SUBCLASS_CANTRIP choices,
 *                      such as an Arcane Archer's prestidigitation or druidcraft (XGE p.28), known with the source
 *                      "subclass:" and the subclass's name; levelling down forgets them
 * @param mysticArcanum the spell a warlock chose at this level as its Mystic Arcanum (PHB p.108), at Warlock 11, 13, 15
 *                      and 17, known with the source "arcanum:Warlock"; levelling down forgets it, and its pool goes
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
        List<FeatTaken> feats,
        Boolean setsSpellcastingAbility,
        List<String> metamagic,
        List<String> proficiencies,
        List<String> kenseiWeapons,
        List<String> subclassCantrips,
        String mysticArcanum
) {

    /** No choices, as each level starts. */
    public static final LevelChoices NONE = new LevelChoices(null, null, null, null, null, null, null, null, null,
            null, null, null, null);

    /** Choices with no Mystic Arcanum. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats,
                        Boolean setsSpellcastingAbility, List<String> metamagic, List<String> proficiencies,
                        List<String> kenseiWeapons, List<String> subclassCantrips) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, setsSpellcastingAbility, metamagic,
                proficiencies, kenseiWeapons, subclassCantrips, null);
    }

    /** Choices with no subclass cantrips. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats,
                        Boolean setsSpellcastingAbility, List<String> metamagic, List<String> proficiencies,
                        List<String> kenseiWeapons) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, setsSpellcastingAbility, metamagic,
                proficiencies, kenseiWeapons, null);
    }

    /** Choices with no kensei weapons. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats,
                        Boolean setsSpellcastingAbility, List<String> metamagic, List<String> proficiencies) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, setsSpellcastingAbility, metamagic,
                proficiencies, null);
    }

    /** Choices with no bonus proficiencies chosen. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats,
                        Boolean setsSpellcastingAbility, List<String> metamagic) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, setsSpellcastingAbility, metamagic, null);
    }

    /** Choices with no Metamagic. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats,
                        Boolean setsSpellcastingAbility) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, setsSpellcastingAbility, null);
    }

    /** Choices that store no spellcasting ability. */
    public LevelChoices(AbilityScoreImprovement asi, List<String> expertise, SubclassChosen subclass,
                        String fightingStyle, List<String> cantrips, SpellsLearned spells, List<FeatTaken> feats) {
        this(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, null);
    }

    public LevelChoices withAsi(AbilityScoreImprovement newAsi) {
        return new LevelChoices(newAsi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withExpertise(List<String> newExpertise) {
        return new LevelChoices(asi, newExpertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withSubclass(SubclassChosen newSubclass) {
        return new LevelChoices(asi, expertise, newSubclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withFightingStyle(String newFightingStyle) {
        return new LevelChoices(asi, expertise, subclass, newFightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withCantrips(List<String> newCantrips) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, newCantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withSpells(SpellsLearned newSpells) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, newSpells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withFeats(List<FeatTaken> newFeats) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, newFeats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withSetsSpellcastingAbility(Boolean sets) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats, sets, metamagic,
                proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withMetamagic(List<String> newMetamagic) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, newMetamagic, proficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withProficiencies(List<String> newProficiencies) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, newProficiencies, kenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withKenseiWeapons(List<String> newKenseiWeapons) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, newKenseiWeapons, subclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withSubclassCantrips(List<String> newSubclassCantrips) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, newSubclassCantrips,
                mysticArcanum);
    }

    public LevelChoices withMysticArcanum(String newMysticArcanum) {
        return new LevelChoices(asi, expertise, subclass, fightingStyle, cantrips, spells, feats,
                setsSpellcastingAbility, metamagic, proficiencies, kenseiWeapons, subclassCantrips,
                newMysticArcanum);
    }
}
