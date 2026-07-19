import api from './axiosConfig';
import type { PlayerCharacter, CharacterCreateRequest, CharacterUpdateRequest } from '../types/character';

export const characterApi = {
  create(data: CharacterCreateRequest) {
    return api.post<PlayerCharacter>('/characters', data);
  },

  update(id: string, data: CharacterUpdateRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}`, data);
  },

  getMine() {
    return api.get<PlayerCharacter[]>('/characters');
  },

  getByCampaign(campaignId: string) {
    return api.get<PlayerCharacter[]>(`/characters/campaign/${campaignId}`);
  },

  getById(id: string) {
    return api.get<PlayerCharacter>(`/characters/${id}`);
  },

  delete(id: string) {
    return api.delete(`/characters/${id}`);
  },
};
