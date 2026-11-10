package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A new form during Shapechange (PHB p.274) or Animal Shapes (PHB p.212), which the caster gives with an action (D192).
 *
 * @param participantId the caster, whose action it is
 * @param targetId      the creature taking the new form: the caster for Shapechange, a creature its Animal Shapes
 *                      transformed for that spell
 * @param creatureId    the monster row of the new form
 */
public record ChangeFormRequest(
        @NotNull UUID participantId,
        @NotNull UUID targetId,
        @NotNull UUID creatureId
) {
}
