package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A monster's action, bonus action, reaction, legendary action or lair action (D138, D174). A multiattack takes a target for each of its attacks in
 * componentTargetIds (D165), one list per component in the order its multiattack option lists them, with
 * targetParticipantIds empty; an empty list skips that attack. Without componentTargetIds, every attack goes to the
 * one creature named in targetParticipantIds.
 * <p>
 * pactOfTheChain: a Pact of the Chain warlock's familiar makes one attack with its reaction, in place of one of the
 * warlock's own attacks of the Attack action on the warlock's turn (PHB p.107): an attack from the familiar's actions,
 * which a familiar otherwise can't make (PHB p.240).
 */
public record MonsterActionRequest(
        @Schema(requiredMode = REQUIRED) UUID monsterParticipantId,
        @Schema(requiredMode = REQUIRED) String actionName,
        @Schema(requiredMode = REQUIRED, allowableValues = {"ACTION", "BONUS", "REACTION", "LEGENDARY",
                "LAIR"}) String actionSource,
        @Schema(requiredMode = REQUIRED) List<UUID> targetParticipantIds,
        Integer overrideAttackBonus,
        Integer overrideSaveDC,
        Boolean advantage,
        List<List<UUID>> componentTargetIds,
        Boolean pactOfTheChain
) {

    /** An action that isn't a Pact of the Chain familiar's attack. */
    public MonsterActionRequest(UUID monsterParticipantId, String actionName, String actionSource,
                                List<UUID> targetParticipantIds, Integer overrideAttackBonus, Integer overrideSaveDC,
                                Boolean advantage, List<List<UUID>> componentTargetIds) {
        this(monsterParticipantId, actionName, actionSource, targetParticipantIds, overrideAttackBonus, overrideSaveDC,
                advantage, componentTargetIds, null);
    }
}
