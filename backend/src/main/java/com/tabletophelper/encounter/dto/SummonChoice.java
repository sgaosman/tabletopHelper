package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.SummonRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What a summoning spell brings, as the caster or the DM chooses it (D182), checked against the spell before anything
 * is spent. Each field is optional where the spell doesn't need it.
 *
 * @param optionId       the option from the summon options, such as "8xCR1/4"; left out for a spell with one option
 * @param creatures      the creatures, each a monster from the options' candidates with a count, adding up to the
 *                       option's count (up to it for Animate Dead and Tiny Servant); left out for a Tasha's spirit
 * @param variant        a Tasha's spirit's environment, mood or kind, such as "Land" or "Mirthful"
 * @param initiativeMode one of the options' initiative modes; left out, the rules' own
 * @param creatureType   a familiar's or steed's celestial, fey or fiend (PHB p.240)
 */
public record SummonChoice(
        String optionId,
        @Valid @Size(max = 32) List<SummonCreature> creatures,
        String variant,
        SummonRules.InitiativeMode initiativeMode,
        String creatureType) {

    /** The creatures, none when left out. */
    public List<SummonCreature> creaturesOrNone() {
        return creatures != null ? creatures : List.of();
    }
}
