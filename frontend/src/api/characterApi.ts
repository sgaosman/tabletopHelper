import api from './axiosConfig';
import type { PlayerCharacter, CharacterCreateRequest, CharacterUpdateRequest, LevelUpResponse, ApplyChoicesRequest, EligibleClassResponse } from '../types/character';

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

  levelUp(id: string, classId?: string) {
    return api.post<LevelUpResponse>(`/characters/${id}/level-up`, classId ? { classId } : {});
  },

  levelDown(id: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/level-down`);
  },

  applyChoices(id: string, data: ApplyChoicesRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/apply-choices`, data);
  },

  getEligibleClasses(id: string) {
    return api.get<EligibleClassResponse[]>(`/characters/${id}/eligible-classes`);
  },
};
