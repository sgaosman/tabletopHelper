import api from './axiosConfig';
import type { Encounter } from '../types/encounter';
import type { CombatLogEntry } from '../types/combat';

export const combatApi = {
  applyDamage(encounterId: string, targetId: string, amount: number, damageType?: string, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/damage`, { targetId, amount, damageType }, {
      params: actorId ? { actorId } : undefined,
    });
  },

  applyHealing(encounterId: string, targetId: string, amount: number, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/heal`, { targetId, amount }, {
      params: actorId ? { actorId } : undefined,
    });
  },

  setHp(encounterId: string, targetId: string, hpCurrent: number, hpTemp?: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/hp`, { targetId, hpCurrent, hpTemp });
  },

  addCondition(encounterId: string, targetId: string, condition: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/condition/add`, { targetId, condition });
  },

  removeCondition(encounterId: string, targetId: string, condition: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/condition/remove`, { targetId, condition });
  },

  rollDeathSave(encounterId: string, participantId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/death-save`, { participantId });
  },

  setConcentration(encounterId: string, participantId: string, spellName: string | null) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/concentration`, { participantId, spellName });
  },

  advanceTurn(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/turn/next`);
  },

  previousTurn(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/turn/previous`);
  },

  getCombatLog(encounterId: string) {
    return api.get<CombatLogEntry[]>(`/encounters/${encounterId}/combat/log`);
  },
};
