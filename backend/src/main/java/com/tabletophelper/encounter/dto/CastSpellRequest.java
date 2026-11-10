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
import java.util.Map;
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

    // The option a spell with options is cast with (D184), such as "enlarge" or "reduce" for Enlarge/Reduce, or the
    // damage type of Protection from Energy; required for such a spell, refused for any other
    @Size(max = 50)
    private String option;

    // A sorcerer's Metamagic options on the cast (PHB p.102, M18): at most one, plus Empowered Spell; none when left out
    @Valid
    @Size(max = 2)
    private List<MetamagicChoice> metamagic;

    // A summoning spell's creatures, checked before anything is spent (D182). Left out, or for a spell whose creatures
    // the DM chooses (Summon Lesser Demons, and Conjure Animals while the campaign says so), the summoning waits for
    // the DM; any other spell refuses it with a 400
    @Valid
    private SummonChoice summon;

    // The form each creature takes, by its participant ID, for Polymorph, Mass Polymorph, True Polymorph, Shapechange
    // (the caster's) and Animal Shapes (D192): the monster row of the creature it becomes, checked before anything is
    // spent; any other spell refuses it with a 400
    private Map<UUID, UUID> forms;

    // For a target whose trait keeps spells off it "unless it wishes to be" affected, as a rakshasa's Limited Magic
    // Immunity (MM p.257, D198), by its participant ID: true to be affected by this cast, false not to. Only whoever
    // controls the target may choose, the DM or the player a spell gave control to; a target left out takes its standing
    // choice, its spellConsent
    private Map<UUID, Boolean> spellConsents;
}
