import api from './axiosConfig';
import type { Encounter } from '../types/encounter';
import type {
  AttackOption,
  AttackRollRequest,
  CombatLogEntry,
  CastSpellRequest,
  CastSpellResponse,
  RepeatSpellEffectRequest,
  MonsterActionRequest,
  MonsterActionResponse,
  MonsterActionTemplate,
  MultiattackOption,
  SpellOption,
  UsePoolRequest,
} from '../types/combat';

export const combatApi = {
  // A weapon attack the server works out, or an attack entered by hand. Extra bonuses, a forced critical and an
  // attack entered by hand are Manual Modifiers, which a player may send only while the campaign allows them
  // (D164). within5Ft decides whether a hit on an unconscious or paralyzed target is a critical hit; left out, it
  // is true, except for a ranged weapon
  rollAttack(encounterId: string, request: AttackRollRequest, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/attack`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // The weapons the participant can attack with, and the numbers for each (D164)
  getAttackOptions(encounterId: string, participantId: string) {
    return api.get<AttackOption[]>(`/encounters/${encounterId}/combat/attack-options`, { params: { participantId } });
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

  // The DM's Set HP / revive: a correction, or the only way back from Dead (D161)
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

  // A Repeat Save the campaign leaves to the player, which the server rolls (D162)
  rollRepeatSave(encounterId: string, participantId: string, conditionId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/repeat-save`, { participantId, conditionId });
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

  // Player characters and monsters cast alike, the caster named by actorId (D165)
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

  // What the participant can cast now, and what can pay for each spell (D164, D165)
  getSpellOptions(encounterId: string, participantId: string) {
    return api.get<SpellOption[]>(`/encounters/${encounterId}/combat/spell-options`, { params: { participantId } });
  },

  repeatSpellEffect(encounterId: string, request: RepeatSpellEffectRequest, actorId?: string) {
    return api.post<CastSpellResponse>(`/encounters/${encounterId}/combat/repeat-spell-effect`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  getCombatLog(encounterId: string) {
    return api.get<CombatLogEntry[]>(`/encounters/${encounterId}/combat/log`);
  },

  // ── M12 Monster Actions ─────────────────────────────────────────

  monsterAction(encounterId: string, request: MonsterActionRequest, actorId?: string) {
    return api.post<MonsterActionResponse>(`/encounters/${encounterId}/combat/monster-action`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // A participant's resource pool, costing its spendActionType from the turn: the DM may use anyone's, a player
  // their own participant's, and an action or bonus action only on its turn. Action Surge adds an action, once per
  // turn (D174)
  usePool(encounterId: string, request: UsePoolRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/use-pool`, request);
  },

  useLegendaryResistance(encounterId: string, combatLogId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/legendary-resistance`, { combatLogId });
  },

  getMonsterActions(encounterId: string, participantId: string) {
    return api.get<MonsterActionTemplate>(`/encounters/${encounterId}/participants/${participantId}/actions`);
  },

  // A monster's multiattacks and the attacks each makes, for a target per attack; DM only (D157, D165)
  getMultiattackOptions(encounterId: string, participantId: string) {
    return api.get<MultiattackOption[]>(`/encounters/${encounterId}/combat/multiattack-options`, {
      params: { participantId },
    });
  },

  // Every monster participant fighting in its lair, which gives the Lair a turn on initiative 20 (D163)
  setLairStatus(encounterId: string, monsterIds: string[]) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/lair-status`, { monsterIds });
  },
};
