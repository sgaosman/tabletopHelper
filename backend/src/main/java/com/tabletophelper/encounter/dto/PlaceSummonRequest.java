package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.SummonRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The DM's creatures for a summoning (D182): those of one that waits for the DM, such as Summon Lesser Demons' (XGE
 * p.167), or new ones in place of a placed summoning's, which disappear.
 *
 * @param effectId       the caster's Spell Effect
 * @param optionId       the option, when it differs from the one the casting chose; Summon Lesser Demons keeps the
 *                       one its d6 rolled (XGE p.167), so any other is a 400
 * @param creatures      the creatures; see {@link SummonChoice}
 * @param variant        a Tasha's spirit's variant
 * @param initiativeMode left out, the rules' own
 * @param creatureType   a familiar's or steed's type
 */
public record PlaceSummonRequest(
        @Schema(requiredMode = REQUIRED) @NotNull UUID effectId,
        String optionId,
        @Valid @Size(max = 32) List<SummonCreature> creatures,
        String variant,
        SummonRules.InitiativeMode initiativeMode,
        String creatureType) {

    /** The request as a choice. */
    public SummonChoice choice() {
        return new SummonChoice(optionId, creatures, variant, initiativeMode, creatureType);
    }
}
