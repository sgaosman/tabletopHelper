package com.tabletophelper.campaign.dto;

/**
 * The DM's campaign settings. Each one is optional: a setting left out, or sent as null, keeps its
 * current value.
 *
 * @param showExactMonsterHp         whether players see exact monster HP rather than a Health Status (D157)
 * @param promptPlayerRepeatSaves    whether players roll their characters' Repeat Saves rather than the server
 *                                   (D162)
 * @param allowPlayerManualModifiers whether players may enter Manual Modifiers in combat (D164)
 */
public record CampaignSettingsRequest(Boolean showExactMonsterHp, Boolean promptPlayerRepeatSaves,
                                      Boolean allowPlayerManualModifiers) {}
