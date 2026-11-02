export interface CampaignCreateRequest {
  name: string;
  description?: string;
}

export interface CampaignJoinRequest {
  inviteCode: string;
}

export interface CampaignMember {
  userId: string;
  username: string;
  displayName: string;
  role: 'DM' | 'PLAYER';
  joinedAt: string;
}

export interface Campaign {
  id: string;
  name: string;
  description?: string;
  dmUserId: string;
  dmDisplayName: string;
  inviteCode: string;
  isActive: boolean;
  /** Off: players see each monster's HP as a Health Status instead of numbers (D157). */
  showExactMonsterHp: boolean;
  members: CampaignMember[];
  createdAt: string;
}

/** The DM's campaign settings; a setting left out keeps its value. */
export interface CampaignSettingsRequest {
  showExactMonsterHp?: boolean;
}
