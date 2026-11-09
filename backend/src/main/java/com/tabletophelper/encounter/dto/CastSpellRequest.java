package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.state.SpellSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * A spell cast by a player character or a monster, paid for from a Spell Source (D165). The caster's spell attack
 * bonus and save DC are used unless overridden; an override is a Manual Modifier (D164).
 */
@Data
public class CastSpellRequest {

    @NotBlank
    private String spellName;

    // Left out, a slot level above 0 means a slot, and 0 means nothing for a cantrip or at-will spell, otherwise
    // an innate use
    private SpellSource spellSource;

    // The slot's level for SLOT; the pact slots' level or 0 for PACT_SLOT; the spell's own level or 0 otherwise
    @NotNull
    @Min(0)
    @Max(9)
    private Integer slotLevel;

    @NotNull
    private List<UUID> targetIds;

    private Boolean advantage;

    private Integer overrideSpellAttackBonus;

    private Integer overrideSpellSaveDC;

    // A sorcerer's Metamagic options on the cast (PHB p.102, M18): at most one, plus Empowered Spell; none when left out
    @Valid
    @Size(max = 2)
    private List<MetamagicChoice> metamagic;

    // A summoning spell's creatures, checked before anything is spent (D182). Left out, or for a spell whose creatures
    // the DM chooses (Summon Lesser Demons, and Conjure Animals while the campaign says so), the summoning waits for
    // the DM; any other spell refuses it with a 400
    @Valid
    private SummonChoice summon;
}
