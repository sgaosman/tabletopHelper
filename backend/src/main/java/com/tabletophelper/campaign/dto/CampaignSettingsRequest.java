package com.tabletophelper.campaign.dto;

/**
 * The DM's campaign settings. Each one is optional: a setting left out, or sent as null, keeps its
 * current value.
 *
 * @param showExactMonsterHp whether players see exact monster HP rather than a Health Status (D157)
 */
public record CampaignSettingsRequest(Boolean showExactMonsterHp) {}
