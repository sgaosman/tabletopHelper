package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.SummonRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The DM places a summoning cast before the fight or outside it (D182), such as a familiar (PHB p.240) or Animate
 * Dead's undead (PHB p.212), whose casting takes too long for a turn: no slot is spent. Any summoning spell will do.
 *
 * @param casterParticipantId whose spell it is
 * @param spellName           the summoning spell
 * @param slotLevel           the slot it was cast with; left out, the spell's own level
 * @param optionId            see {@link SummonChoice}
 * @param creatures           see {@link SummonChoice}
 * @param variant             see {@link SummonChoice}
 * @param initiativeMode      see {@link SummonChoice}
 * @param creatureType        see {@link SummonChoice}
 * @param concentrationLinked whether the caster concentrates on it, so its creatures go when the concentration ends;
 *                            left out, whether the spell needs concentration
 */
public record CompanionSummonRequest(
        @Schema(requiredMode = REQUIRED) @NotNull UUID casterParticipantId,
        @Schema(requiredMode = REQUIRED) @NotBlank String spellName,
        @Min(1) @Max(9) Integer slotLevel,
        String optionId,
        @Valid @Size(max = 32) List<SummonCreature> creatures,
        String variant,
        SummonRules.InitiativeMode initiativeMode,
        String creatureType,
        Boolean concentrationLinked) {

    /** The request as a choice. */
    public SummonChoice choice() {
        return new SummonChoice(optionId, creatures, variant, initiativeMode, creatureType);
    }
}
