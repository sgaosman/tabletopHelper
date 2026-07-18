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
  members: CampaignMember[];
  createdAt: string;
}
