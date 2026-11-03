package com.tabletophelper.character.dto;

import java.util.UUID;

/**
 * The campaign a character plays in (D158), which its owner must have joined.
 *
 * @param campaignId the campaign, or null to take the character out of its campaign
 */
public record CharacterCampaignRequest(UUID campaignId) {
}
