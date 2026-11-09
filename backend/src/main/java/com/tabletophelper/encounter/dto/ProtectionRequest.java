package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The Protection fighting style's reaction (PHB p.72, M17): a creature the defender can see attacks someone else within
 * 5 feet of it, and the defender, wielding a shield, imposes disadvantage on that attack roll. The attack is the DM's to
 * roll again, so this records the reaction.
 *
 * @param participantId the defender
 * @param protectedId   the creature it protects
 */
public record ProtectionRequest(
        @NotNull UUID participantId,
        @NotNull UUID protectedId
) {
}
