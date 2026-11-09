package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.SummonRules;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What a summoning spell can bring when this caster casts it at this slot level (D182), for the cast's summon step and
 * the DM's placing.
 *
 * @param spellName             the spell
 * @param kind                  how its creatures are made and governed
 * @param slotLevel             the slot level asked about, at least the spell's own
 * @param spellLevel            the spell's level
 * @param concentration         whether it needs concentration
 * @param castInCombat          whether it can be cast in a turn; the others are placed by the DM, no slot spent
 * @param dmChooses             whether the DM chooses its creatures, so a cast leaves it waiting for the DM: always for
 *                              Summon Lesser Demons (XGE p.167), and for Conjure Animals while the campaign says so
 * @param options               what it can bring, the upcast multiplier included
 * @param multiplier            how many times over the creatures come at the slot level
 * @param variants              a Tasha's spirit's variants; empty for others
 * @param initiativeModes       the initiative modes allowed, the rules' own first
 * @param defaultInitiativeMode the rules' own
 * @param houseRuleModes        those of the allowed modes the rules don't give, to label as a table's choice
 * @param controlledBy          CASTER when the caster's player acts for the creatures, DM when the DM does
 * @param creatureTypes         the types the caster chooses among, such as celestial, fey or fiend; empty for none
 * @param creatureType          the type the creatures count as, such as fey for Conjure Animals; null for their own
 * @param cannotAttack          whether the creatures can't attack (PHB p.240)
 * @param needsCommand          whether the creatures act only on the caster's bonus-action command (PHB p.212)
 * @param vanishesAtZero        whether the creatures disappear at 0 hit points
 * @param hasFamiliar           whether the caster has a familiar already, which costs Flock of Familiars one
 * @param candidates            the creatures that fit any option, by challenge rating then name; for a Tasha's spirit
 *                              its stat block
 * @param spirit                a Tasha's spirit's statistics at the slot level; null for others
 */
public record SummonOptions(
        @Schema(requiredMode = REQUIRED) String spellName,
        @Schema(requiredMode = REQUIRED) SummonRules.Kind kind,
        @Schema(requiredMode = REQUIRED) int slotLevel,
        @Schema(requiredMode = REQUIRED) int spellLevel,
        @Schema(requiredMode = REQUIRED) boolean concentration,
        @Schema(requiredMode = REQUIRED) boolean castInCombat,
        @Schema(requiredMode = REQUIRED) boolean dmChooses,
        @Schema(requiredMode = REQUIRED) List<Option> options,
        @Schema(requiredMode = REQUIRED) int multiplier,
        @Schema(requiredMode = REQUIRED) List<String> variants,
        @Schema(requiredMode = REQUIRED) List<SummonRules.InitiativeMode> initiativeModes,
        @Schema(requiredMode = REQUIRED) SummonRules.InitiativeMode defaultInitiativeMode,
        @Schema(requiredMode = REQUIRED) List<SummonRules.InitiativeMode> houseRuleModes,
        @Schema(requiredMode = REQUIRED) SummonRules.Controller controlledBy,
        @Schema(requiredMode = REQUIRED) List<String> creatureTypes,
        String creatureType,
        @Schema(requiredMode = REQUIRED) boolean cannotAttack,
        @Schema(requiredMode = REQUIRED) boolean needsCommand,
        @Schema(requiredMode = REQUIRED) boolean vanishesAtZero,
        @Schema(requiredMode = REQUIRED) boolean hasFamiliar,
        @Schema(requiredMode = REQUIRED) List<Candidate> candidates,
        SpiritPreview spirit) {

    /**
     * One option.
     *
     * @param id    names it in a request, such as "8xCR1/4"
     * @param count how many creatures
     * @param maxCr the highest challenge rating, such as 0.25; null for no limit
     * @param label in words
     */
    public record Option(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) int count,
            Double maxCr,
            @Schema(requiredMode = REQUIRED) String label) {
    }

    /**
     * A creature that fits.
     *
     * @param monsterId          the monster row a request names
     * @param challengeRating    as the stat block writes it, such as "1/4"; null for none
     * @param challengeRatingValue the same as a number, such as 0.25; null for none
     */
    public record Candidate(
            @Schema(requiredMode = REQUIRED) UUID monsterId,
            @Schema(requiredMode = REQUIRED) String name,
            String challengeRating,
            Double challengeRatingValue,
            @Schema(requiredMode = REQUIRED) int hitPoints,
            @Schema(requiredMode = REQUIRED) int armourClass,
            String type,
            String size,
            String source) {
    }

    /**
     * A Tasha's spirit at the slot level, with the caster's numbers (TCE p.109–114).
     *
     * @param monsterId        its stat block's row
     * @param armourClass      its AC
     * @param hitPoints        its hit points by variant
     * @param attacks          how many attacks its Multiattack makes
     * @param attackBonus      its attack bonus, the caster's spell attack bonus
     * @param saveDc           its save DC, the caster's spell save DC
     */
    public record SpiritPreview(
            @Schema(requiredMode = REQUIRED) UUID monsterId,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) int armourClass,
            @Schema(requiredMode = REQUIRED) Map<String, Integer> hitPoints,
            @Schema(requiredMode = REQUIRED) int attacks,
            @Schema(requiredMode = REQUIRED) int attackBonus,
            @Schema(requiredMode = REQUIRED) int saveDc) {
    }
}
