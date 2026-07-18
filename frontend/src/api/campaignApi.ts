import api from './axiosConfig';
import type { Campaign, CampaignCreateRequest, CampaignJoinRequest } from '../types/campaign';

export const campaignApi = {
  create(data: CampaignCreateRequest) {
    return api.post<Campaign>('/campaigns', data);
  },

  join(data: CampaignJoinRequest) {
    return api.post<Campaign>('/campaigns/join', data);
  },

  getAll() {
    return api.get<Campaign[]>('/campaigns');
  },

  getById(id: string) {
    return api.get<Campaign>(`/campaigns/${id}`);
  },
};
