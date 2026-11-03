import api from './axiosConfig';
import type {
  Encounter, EncounterCreateRequest, AddParticipantRequest, BulkInitiativeRequest, TieOrderRequest,
  CombatResultPreview, EndEncounterRequest,
} from '../types/encounter';

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

  renameParticipant(encounterId: string, participantId: string, displayName: string) {
    return api.patch<Encounter>(`/encounters/${encounterId}/participants/${participantId}/name`, { displayName });
  },

  /** Hides a monster from the Player View, or shows it again (D157). */
  setVisibility(encounterId: string, participantId: string, visible: boolean) {
    return api.patch<Encounter>(`/encounters/${encounterId}/participants/${participantId}/visibility`, { visible });
  },

  setInitiatives(encounterId: string, data: BulkInitiativeRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/initiatives`, data);
  },

  rollInitiatives(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/initiatives/roll`);
  },

  /** The DM's order for participants tied on initiative and DEX: every one of them, first to act first (D163). */
  reorderTie(encounterId: string, participantIds: string[]) {
    return api.put<Encounter>(`/encounters/${encounterId}/initiatives/tie-order`, { participantIds } satisfies TieOrderRequest);
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

  /** Completes the encounter, writing its results back to the character sheets unless the DM declines (D166). */
  end(encounterId: string, applyResults: boolean) {
    return api.post<Encounter>(`/encounters/${encounterId}/end`, { applyResults } satisfies EndEncounterRequest);
  },

  /** What ending the encounter with its results applied would write back to each character sheet; DM only. */
  getResults(encounterId: string) {
    return api.get<CombatResultPreview[]>(`/encounters/${encounterId}/results`);
  },

  getBySessionCode(code: string) {
    return api.get<Encounter>(`/encounters/join/${code}`);
  },
};
