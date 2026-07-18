import api from './axiosConfig';
import type { Encounter, EncounterCreateRequest, AddParticipantRequest, BulkInitiativeRequest } from '../types/encounter';

export const encounterApi = {
  create(data: EncounterCreateRequest) {
    return api.post<Encounter>('/encounters', data);
  },

  getByCampaign(campaignId: string) {
    return api.get<Encounter[]>(`/encounters/campaign/${campaignId}`);
  },

  getById(id: string) {
    return api.get<Encounter>(`/encounters/${id}`);
  },

  delete(id: string) {
    return api.delete(`/encounters/${id}`);
  },

  addParticipant(encounterId: string, data: AddParticipantRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/participants`, data);
  },

  removeParticipant(encounterId: string, participantId: string) {
    return api.delete<Encounter>(`/encounters/${encounterId}/participants/${participantId}`);
  },

  setInitiatives(encounterId: string, data: BulkInitiativeRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/initiatives`, data);
  },

  rollInitiatives(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/initiatives/roll`);
  },

  start(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/start`);
  },

  pause(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/pause`);
  },

  resume(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/resume`);
  },

  end(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/end`);
  },

  getBySessionCode(code: string) {
    return api.get<Encounter>(`/encounters/join/${code}`);
  },
};
