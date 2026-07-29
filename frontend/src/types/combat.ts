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

export interface RepeatSpellEffectRequest {
  targetIds: string[];
  advantage?: boolean | null;
  overrideSpellAttackBonus?: number;
  overrideSpellSaveDC?: number;
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
  legendaryResistanceEligible?: boolean;
  lrTargetId?: string;
  lrDamageDealt?: number;
  lrConditionsApplied?: string;
  lrResolved?: boolean;
  createdAt: string;
}

// ── M12 Monster Action Types ──────────────────────────────────────

export interface MonsterActionRequest {
  monsterParticipantId: string;
  actionName: string;
  actionSource: 'ACTION' | 'LEGENDARY' | 'LAIR';
  targetParticipantIds: string[];
  overrideAttackBonus?: number;
  overrideSaveDC?: number;
  advantage?: boolean | null;
}

export interface MonsterActionTargetResult {
  targetId: string;
  targetName: string;
  damage: number;
  healing: number;
  savedSuccessfully: boolean;
  conditionsApplied: string[];
  attackOutcome: string;
  rollValue?: number;
  rollTotal?: number;
  damageType?: string;
  legendaryResistanceAvailable: boolean;
  legendaryResistanceRemaining: number;
}

export interface MonsterActionResponse {
  resolved: boolean;
  description: string;
  totalDamage: number;
  totalHealing: number;
  targetResults: MonsterActionTargetResult[];
  requiresManualResolution: boolean;
  manualResolutionReason?: string;
  conditionsInflicted: string[];
  legendaryResistanceAvailable: boolean;
  legendaryResistanceRemaining: number;
  encounterState: Encounter;
}

export interface MonsterSpellRequest {
  monsterParticipantId: string;
  spellName: string;
  slotLevel: number;
  targetParticipantIds: string[];
  overrideAttackBonus?: number;
  overrideSaveDC?: number;
  advantage?: boolean | null;
}

export interface MonsterActionTemplate {
  actions: MonsterAction[];
  legendaryActions?: MonsterAction[];
  lairActions?: MonsterAction[];
  spellcasting?: MonsterSpellcasting;
  bonusActions?: MonsterAction[];
  reactions?: MonsterAction[];
  traits?: MonsterTrait[];
  legendaryActionCount?: number;
  legendaryResistanceCount?: number;
  hasLairActions?: boolean;
}

export interface MonsterAction {
  name: string;
  actionType?: string;
  deliveryMethod?: string;
  attackType?: string;
  attackBonus?: number;
  reach?: number;
  range?: number;
  targetCount?: number;
  targetType?: string;
  aoeSize?: number;
  saveDC?: number;
  saveAbility?: string;
  halfOnSave?: boolean;
  effects?: MonsterEffect[];
  isMultiattack?: boolean;
  multiattackDescription?: string;
  multiattackComponents?: string[];
  automatable?: boolean;
  recharge?: string;
  description?: string;
  cost?: number;
  referencesAction?: string;
}

export interface MonsterEffect {
  effectType: string;
  damageDice?: string;
  damageType?: string;
  condition?: string;
  durationRounds?: number;
  durationText?: string;
  saveToEndEachTurn?: boolean;
  saveToEndDC?: number;
  saveToEndAbility?: string;
}

export interface MonsterSpellcasting {
  ability?: string;
  saveDC?: number;
  attackBonus?: number;
  innateSpells?: boolean;
  name?: string;
  slots?: Record<string, number>;
  spellsByLevel?: Record<string, string[]>;
  dailySpells?: Record<string, string[]>;
  atWillSpells?: string[];
}

export interface MonsterTrait {
  name: string;
  description: string;
  combatRelevant?: boolean;
  mechanicType?: string;
}
