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
 * <p>
 * damageType: the damage type the monster chooses for an action whose stat block lets it, one of the action's
 * damageOptions, as a dracohydra's Bite (FTD); required for such an action and refused for any other (D207). A
 * multiattack takes componentDamageTypes instead, one per component in the order its multiattack option lists them,
 * null for an attack without options or one not made; each attack with options that is made needs one.
 * <p>
 * charged, twoHanded and attackerForm (D239): the monster charged before the attack, so its charge's extra damage and
 * knock-prone save come with the hit (an attack without a charge refuses true); a versatile weapon held in two hands, so
 * its two-handed damage is rolled (an attack that isn't versatile refuses true); and the form the monster is in, one of
 * the forms the attack's damage depends on or "usual" for none of them, null for the app to tell. A multiattack takes
 * componentCharged, componentTwoHanded and componentForms instead, one per component, null for nothing chosen.
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
        Boolean pactOfTheChain,
        String damageType,
        List<String> componentDamageTypes,
        Boolean charged,
        Boolean twoHanded,
        String attackerForm,
        List<Boolean> componentCharged,
        List<Boolean> componentTwoHanded,
        List<String> componentForms
) {

    /** An action with no charge, grip or form chosen (D207). */
    public MonsterActionRequest(UUID monsterParticipantId, String actionName, String actionSource,
                                List<UUID> targetParticipantIds, Integer overrideAttackBonus, Integer overrideSaveDC,
                                Boolean advantage, List<List<UUID>> componentTargetIds, Boolean pactOfTheChain,
                                String damageType, List<String> componentDamageTypes) {
        this(monsterParticipantId, actionName, actionSource, targetParticipantIds, overrideAttackBonus, overrideSaveDC,
                advantage, componentTargetIds, pactOfTheChain, damageType, componentDamageTypes, null, null, null,
                null, null, null);
    }

    /** An action that isn't a Pact of the Chain familiar's attack. */
    public MonsterActionRequest(UUID monsterParticipantId, String actionName, String actionSource,
                                List<UUID> targetParticipantIds, Integer overrideAttackBonus, Integer overrideSaveDC,
                                Boolean advantage, List<List<UUID>> componentTargetIds) {
        this(monsterParticipantId, actionName, actionSource, targetParticipantIds, overrideAttackBonus, overrideSaveDC,
                advantage, componentTargetIds, null);
    }

    /** An action with no damage type chosen (D207). */
    public MonsterActionRequest(UUID monsterParticipantId, String actionName, String actionSource,
                                List<UUID> targetParticipantIds, Integer overrideAttackBonus, Integer overrideSaveDC,
                                Boolean advantage, List<List<UUID>> componentTargetIds, Boolean pactOfTheChain) {
        this(monsterParticipantId, actionName, actionSource, targetParticipantIds, overrideAttackBonus, overrideSaveDC,
                advantage, componentTargetIds, pactOfTheChain, null, null);
    }
}
