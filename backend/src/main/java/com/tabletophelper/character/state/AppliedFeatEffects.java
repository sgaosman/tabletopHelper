package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * What applying a feat changed on the sheet, as the level history records it so that levelling down can reverse
 * exactly that (D168). Only what the feat changed is recorded: an empty list or a bonus of 0 is left out.
 *
 * @param abilityIncreases each ability increased, spelled out or abbreviated in lower case, and by how much: the
 *                         amount actually applied under the maximum (D167), or for a feat taken before R6 the amount
 *                         the feat gives
 * @param resource         the feat's limited uses, which the sheet keeps in a {@code feat:<feat>} pool (D158)
 * @param spellsAdded      the spells the feat added to the known spells
 * @param featName            the feat, whose feature entry levelling down removes
 * @param spellcastingAbility    the race and feat spellcasting ability the feat's spells chose (D159)
 * @param spellcastingAbilitySet whether the feat set the character's race and feat spellcasting ability, which it does
 *                               when the character has none; reversing it passes the ability on to another feat still
 *                               applied that chose one, or clears it. A record with an ability and no flag, as one
 *                               recorded before the flag was, set it
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppliedFeatEffects(
        Map<String, Integer> abilityIncreases,
        List<String> resistancesAdded,
        List<String> armorProficienciesAdded,
        List<String> weaponProficienciesAdded,
        List<String> toolProficienciesAdded,
        List<String> skillProficienciesAdded,
        List<String> languageProficienciesAdded,
        List<String> savingThrowProficienciesAdded,
        List<String> expertiseAdded,
        Integer speedBonus,
        Integer initiativeBonus,
        Integer hpPerLevel,
        Integer passivePerceptionBonus,
        Integer passiveInvestigationBonus,
        FeatResourceEntry resource,
        List<KnownSpell> spellsAdded,
        String featName,
        String spellcastingAbility,
        Boolean spellcastingAbilitySet
) {

    /** Effects that set no spellcasting ability, as every feat's did before the R6 review fixes. */
    public AppliedFeatEffects(Map<String, Integer> abilityIncreases, List<String> resistancesAdded,
                              List<String> armorProficienciesAdded, List<String> weaponProficienciesAdded,
                              List<String> toolProficienciesAdded, List<String> skillProficienciesAdded,
                              List<String> languageProficienciesAdded, List<String> savingThrowProficienciesAdded,
                              List<String> expertiseAdded, Integer speedBonus, Integer initiativeBonus,
                              Integer hpPerLevel, Integer passivePerceptionBonus, Integer passiveInvestigationBonus,
                              FeatResourceEntry resource, List<KnownSpell> spellsAdded, String featName) {
        this(abilityIncreases, resistancesAdded, armorProficienciesAdded, weaponProficienciesAdded,
                toolProficienciesAdded, skillProficienciesAdded, languageProficienciesAdded,
                savingThrowProficienciesAdded, expertiseAdded, speedBonus, initiativeBonus, hpPerLevel,
                passivePerceptionBonus, passiveInvestigationBonus, resource, spellsAdded, featName, null, null);
    }

    /** Whether the feat set the character's race and feat spellcasting ability, so that reversing it passes it on. */
    public boolean setsSpellcastingAbility() {
        return spellcastingAbility != null && !Boolean.FALSE.equals(spellcastingAbilitySet);
    }

    /** The effects as those of the feat that set the race and feat spellcasting ability, after a takeover. */
    public AppliedFeatEffects withSpellcastingAbilitySet() {
        return new AppliedFeatEffects(abilityIncreases, resistancesAdded, armorProficienciesAdded,
                weaponProficienciesAdded, toolProficienciesAdded, skillProficienciesAdded, languageProficienciesAdded,
                savingThrowProficienciesAdded, expertiseAdded, speedBonus, initiativeBonus, hpPerLevel,
                passivePerceptionBonus, passiveInvestigationBonus, resource, spellsAdded, featName,
                spellcastingAbility, true);
    }
}
