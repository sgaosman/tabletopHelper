package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.Metamagic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A Metamagic option a sorcerer puts on a spell they cast (PHB p.102, M18).
 *
 * @param option    the option, which the sorcerer must know
 * @param targetIds for Careful Spell, the targets that succeed on their saves automatically, up to the sorcerer's
 *                  Charisma modifier (at least one); for Heightened Spell, the one target with disadvantage on its
 *                  first save. Every one must be among the spell's targets. Left out for the other options; Twinned
 *                  Spell's second target is the spell's second target
 */
public record MetamagicChoice(
        @NotNull @Schema(requiredMode = REQUIRED) Metamagic.Option option,
        @Size(max = 20) List<@NotNull UUID> targetIds
) {
}
