import type { Schemas } from '../api/schema';

export type CampaignCreateRequest = Schemas['CampaignCreateRequest'];

export type CampaignJoinRequest = Schemas['CampaignJoinRequest'];

// joinedAt is null for a member added by the response's own request
export type CampaignMember = Schemas['MemberResponse'];

// createdAt is null in the response that creates the campaign
export type Campaign = Schemas['CampaignResponse'];

/** The DM's campaign settings; a setting left out, or null, keeps its value. */
export type CampaignSettingsRequest = Schemas['CampaignSettingsRequest'];
