import type { Encounter } from './encounter';

export interface CastSpellRequest {
  spellName: string;
  slotLevel: number;
  targetIds: string[];
  advantage?: boolean | null;
  usePactSlot?: boolean;
  overrideSpellAttackBonus?: number;
  overrideSpellSaveDC?: number;
}

export interface TargetOutcome {
  targetId: string;
  targetName: string;
  outcome: string;
  damage?: number;
  healing?: number;
  conditionsApplied: string[];
  attackRoll?: number;
  saveRoll?: number;
}

export interface CastSpellResponse {
  encounterState: Encounter;
  spellName: string;
  slotLevelUsed: number;
  autoResolved: boolean;
  resultSummary: string;
  targets: TargetOutcome[];
  manualResolutionReason?: string;
}

export interface CombatLogEntry {
  id: string;
  roundNumber: number;
  actorId?: string;
  actorName?: string;
  targetId?: string;
  targetName?: string;
  actionType: string;
  description: string;
  rollValue?: number;
  rollTotal?: number;
  damageDealt?: number;
  healingDone?: number;
  turnParticipantName?: string;
  createdAt: string;
}
