import api from './axiosConfig';
import type { Encounter } from '../types/encounter';
import type { CombatLogEntry, CastSpellRequest, CastSpellResponse } from '../types/combat';

export const combatApi = {
  rollAttack(encounterId: string, targetId: string, attackBonus: number, damageDice: string, damageType?: string, advantage?: boolean | null, forceCrit?: boolean, actorId?: string, isRanged?: boolean) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/attack`,
      { targetId, attackBonus, damageDice, damageType, advantage: advantage ?? undefined, forceCrit: forceCrit || undefined, isRanged: isRanged || undefined },
      { params: actorId ? { actorId } : undefined },
    );
  },

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

  addCondition(encounterId: string, targetId: string, condition: string, duration?: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/condition/add`, { targetId, condition, duration: duration ?? undefined });
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

  useSpellSlot(encounterId: string, participantId: string, slotLevel: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-slot/use`, { participantId, slotLevel });
  },

  restoreSpellSlot(encounterId: string, participantId: string, slotLevel: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-slot/restore`, { participantId, slotLevel });
  },

  castSpell(encounterId: string, request: CastSpellRequest, actorId?: string) {
    return api.post<CastSpellResponse>(`/encounters/${encounterId}/combat/cast-spell`, request, {
      params: actorId ? { actorId } : undefined,
    });
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
